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
 * Emits lines rather than paragraphs: a paragraph's bounding box spans several
 * visual lines, which the overlay cannot place usefully.
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
            block.lines.mapNotNull { line ->
                val content = line.text.trim()
                if (content.isEmpty()) return@mapNotNull null
                val box: Rect = line.boundingBox ?: return@mapNotNull null
                TextBlock(
                    original = content,
                    translated = content,
                    language = Language.fromCode(line.recognizedLanguage.ifEmpty {
                        block.recognizedLanguage
                    }),
                    left = box.left,
                    top = box.top,
                    width = box.width(),
                    height = box.height(),
                    confidence = meanOf(line.elements.map { it.confidence }),
                )
            }
        }
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
