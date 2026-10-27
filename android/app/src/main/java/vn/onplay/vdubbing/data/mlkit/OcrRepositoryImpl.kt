package vn.onplay.vdubbing.data.mlkit

import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.data.capture.BitmapCaptureStore
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.Script
import vn.onplay.vdubbing.domain.model.TextBlock
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.CapturedScreen
import vn.onplay.vdubbing.domain.repository.OcrRepository

/**
 * On-device OCR via ML Kit.
 *
 * Emits coherent paragraph blocks: consecutive lines in the same block are grouped
 * into one logical translation unit, preserving grammar and sentences while drawing
 * a clean, single plate over the entire paragraph.
 */
class OcrRepositoryImpl(
    private val store: BitmapCaptureStore,
) : OcrRepository {

    private val latn: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** One recognizer per script; each loads its own model, so they are cached. */
    private val byScript = HashMap<Script, TextRecognizer>()

    override suspend fun recognize(screen: CapturedScreen, script: Script): List<TextBlock> {
        val frame = store.frame(screen)
            ?: throw TranslationException.CaptureFailed(IllegalStateException("capture expired"))

        val image = InputImage.fromBitmap(frame.bitmap, 0)
        val text = withContext(Dispatchers.Default) {
            recognizerFor(script).process(image).await()
        }

        return text.textBlocks.flatMap { block ->
            val validLines = block.lines.mapNotNull { line ->
                val content = line.text.trim()
                val box = line.boundingBox
                if (content.isEmpty() || box == null) null else line
            }
            if (validLines.isEmpty()) return@flatMap emptyList()

            // Group lines into coherent paragraph chunks. If two consecutive lines
            // have an unusually large vertical gap (> line.height), treat as separate items.
            val groups = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
            var currentGroup = mutableListOf<com.google.mlkit.vision.text.Text.Line>()

            for (line in validLines) {
                if (currentGroup.isEmpty()) {
                    currentGroup.add(line)
                } else {
                    val prevBox = currentGroup.last().boundingBox!!
                    val curBox = line.boundingBox!!
                    val gap = curBox.top - prevBox.bottom
                    val prevHeight = prevBox.height().coerceAtLeast(1)
                    if (gap <= prevHeight * 1.8f) {
                        currentGroup.add(line)
                    } else {
                        groups.add(currentGroup)
                        currentGroup = mutableListOf(line)
                    }
                }
            }
            if (currentGroup.isNotEmpty()) groups.add(currentGroup)

            groups.mapNotNull { group ->
                val content = joinLines(group.map { it.text })
                if (content.isBlank()) return@mapNotNull null

                val left = group.minOf { it.boundingBox!!.left }
                val top = group.minOf { it.boundingBox!!.top }
                val right = group.maxOf { it.boundingBox!!.right }
                val bottom = group.maxOf { it.boundingBox!!.bottom }

                val langCode = block.recognizedLanguage.ifEmpty {
                    group.first().recognizedLanguage
                }

                TextBlock(
                    original = content,
                    translated = content,
                    language = Language.fromCode(langCode),
                    left = left,
                    top = top,
                    width = (right - left).coerceAtLeast(1),
                    height = (bottom - top).coerceAtLeast(1),
                    confidence = meanOf(group.flatMap { it.elements }.map { it.confidence }),
                    lineCount = group.size,
                )
            }
        }
    }

    private fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(trimmed)
            } else if (sb.endsWith("-") && !sb.endsWith(" -")) {
                sb.setLength(sb.length - 1)
                sb.append(trimmed)
            } else {
                sb.append(" ").append(trimmed)
            }
        }
        return sb.toString()
    }

    /**
     * Average of the per-element confidences ML Kit actually produced, or null
     * when it produced none — several scripts never report them. Null must not
     * be read as "poor quality"; the caller then falls back to text volume.
     */
    private fun meanOf(values: List<Float?>): Double? {
        val known = values.filterNotNull()
        return if (known.isEmpty()) null else known.average()
    }

    private fun recognizerFor(script: Script): TextRecognizer = when (script) {
        Script.LATIN -> latn
        else -> byScript.getOrPut(script) {
            TextRecognition.getClient(
                when (script) {
                    Script.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                    Script.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                    Script.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
                    Script.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                    Script.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                },
            )
        }
    }

    fun close() {
        runCatching { latn.close() }
        byScript.values.forEach { runCatching { it.close() } }
        byScript.clear()
    }
}
