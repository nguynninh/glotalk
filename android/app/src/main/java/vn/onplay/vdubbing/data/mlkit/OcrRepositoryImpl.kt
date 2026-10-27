package vn.onplay.vdubbing.data.mlkit

import android.graphics.Rect
import kotlin.math.abs
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.data.capture.BackgroundSampler
import vn.onplay.vdubbing.data.capture.BitmapCaptureStore
import vn.onplay.vdubbing.data.capture.refineTextInkBounds
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
        // ML Kit keeps reading the bitmap after coroutine cancellation.
        val text = withContext(Dispatchers.Default + NonCancellable) {
            recognizerFor(script).process(image).await()
        }
        currentCoroutineContext().ensureActive()

        // ML Kit exposes lazy lists that can create new Line objects on each access.
        val recognizedBlocks = text.textBlocks.map { it to it.lines.toList() }
        val lines = recognizedBlocks.flatMap { it.second }.associateWith { line ->
            val elements = line.elements.mapNotNull { element ->
                val box = element.boundingBox ?: return@mapNotNull null
                Word(element.text, box, element.confidence)
            }.toMutableList()
            if (script == Script.LATIN && elements.size > 1) {
                if (elements.first().text.length == 1 && elements.first().confidence < 0.35f) elements.removeAt(0)
                val first = elements.firstOrNull()
                if (first != null && first.text.firstOrNull() in listOf('+', '|')) {
                    val symbols = line.elements.first().symbols.dropWhile { it.text == "+" || it.text == "|" }
                    if (symbols.isNotEmpty()) {
                        val box = Rect(symbols.first().boundingBox ?: first.box)
                        symbols.forEach { it.boundingBox?.let(box::union) }
                        elements[0] = Word(symbols.joinToString("") { it.text }, box, first.confidence)
                    }
                }
            }
            elements
        }
        val starts = lines.values.filter { it.isNotEmpty() }.map { it.first().box }
        val columns = starts.mapNotNull { candidate ->
            val aligned = starts.filter { other ->
                abs(candidate.left - other.left) <= minOf(candidate.height(), other.height()) * 0.35f
            }
            if (aligned.count { abs(candidate.top - it.top) > maxOf(candidate.height(), it.height()) } < 2) {
                null
            } else candidate.left to (aligned.minOf { it.top }..aligned.maxOf { it.bottom })
        }.distinct()

        return recognizedBlocks.flatMap { (block, blockLines) ->
            val validLines = blockLines.flatMap { line ->
                val words = lines[line].orEmpty()
                val chunks = mutableListOf<MutableList<Word>>()
                for (word in words) {
                    val chunk = chunks.lastOrNull()
                    val previous = chunk?.lastOrNull()
                    val columnBreak = previous != null && columns.any { (column, rows) ->
                        word.box.top in rows && abs(word.box.left - column) <= word.box.height() * 0.35f &&
                            chunk.first().box.left < column - word.box.height() * 2
                    }
                    if (previous == null || columnBreak || word.box.left - previous.box.right > word.box.height() * 1.25f) {
                        chunks.add(mutableListOf(word))
                    } else chunk.add(word)
                }
                chunks.map { Line(it, Language.fromCode(line.recognizedLanguage)) }
            }
            if (validLines.isEmpty()) return@flatMap emptyList()

            // Join wrapped lines only; adjacent columns and separate rows retain their boxes.
            val groups = mutableListOf<MutableList<Line>>()
            var currentGroup = mutableListOf<Line>()

            for (line in validLines) {
                if (currentGroup.isEmpty()) {
                    currentGroup.add(line)
                } else {
                    val prevBox = currentGroup.last().box
                    val curBox = line.box
                    if (canJoinParagraph(prevBox, curBox)) {
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

                val left = group.minOf { it.box.left }
                val top = group.minOf { it.box.top }
                val right = group.maxOf { it.box.right }
                val bottom = group.maxOf { it.box.bottom }

                TextBlock(
                    original = content,
                    translated = content,
                    language = Language.fromCode(block.recognizedLanguage).takeIf { it.isTranslatable }
                        ?: group.first().language,
                    left = left,
                    top = top,
                    width = (right - left).coerceAtLeast(1),
                    height = (bottom - top).coerceAtLeast(1),
                    confidence = meanOf(group.flatMap { it.words }.map { it.confidence }),
                    lineCount = group.size,
                )
            }
        }.map { block ->
            val styled = block.copy(backgroundColor = BackgroundSampler.sample(
                block, frame.width, frame.height, frame.bitmap::getPixel,
            ))
            refineTextInkBounds(styled, frame.width, frame.height, frame.bitmap::getPixel)
        }
    }

    private data class Word(val text: String, val box: Rect, val confidence: Float)

    private class Line(val words: List<Word>, val language: Language) {
        val text = words.joinToString(" ") { it.text }
        val box = Rect(words.first().box).apply { words.forEach { union(it.box) } }
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
