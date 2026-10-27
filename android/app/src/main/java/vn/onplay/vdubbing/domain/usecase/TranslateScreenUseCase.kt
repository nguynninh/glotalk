package vn.onplay.vdubbing.domain.usecase

import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock
import vn.onplay.vdubbing.domain.model.Translation
import vn.onplay.vdubbing.domain.model.TranslationEngine
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.CapturedScreen
import vn.onplay.vdubbing.domain.repository.CloudTranslationRepository
import vn.onplay.vdubbing.domain.repository.ConnectivityRepository
import vn.onplay.vdubbing.domain.repository.OcrRepository
import vn.onplay.vdubbing.domain.repository.ScreenCaptureRepository
import vn.onplay.vdubbing.domain.repository.TranslationRepository

/**
 * Capture → OCR → (detect) → translate, choosing on-device or cloud.
 *
 * Policy lives here alone:
 *  1. Local OCR is good enough  → translate on device.
 *  2. Too poor and cloud ready  → let the cloud do OCR + translation in one call.
 *  3. Cloud unavailable/failed  → still return the local text, translated.
 *
 * Only key/quota problems abort, because those need the user's attention;
 * a transient cloud failure must not cost them the local result.
 */
class TranslateScreenUseCase(
    private val capture: ScreenCaptureRepository,
    private val ocr: OcrRepository,
    private val translator: TranslationRepository,
    private val cloud: CloudTranslationRepository,
    private val connectivity: ConnectivityRepository,
) {

    /**
     * @param ownPackage this app's package name. When it is the foreground app,
     * the overlay is suppressed: covering our own settings screen with a
     * translation leaves the user unable to reach the controls. Callers in
     * another app are unaffected.
     */
    suspend operator fun invoke(
        source: Language,
        target: Language,
        showOverlay: Boolean = true,
        ownPackage: String? = null,
    ): Translation {
        if (!capture.isReady()) throw TranslationException.AccessibilityDisabled()

        val onOurOwnScreen = ownPackage != null && capture.foregroundPackage() == ownPackage
        val overlay = showOverlay && !onOurOwnScreen

        val screen = capture.capture()
        try {
            return translateCaptured(screen, source, target, overlay)
        } finally {
            // The screenshot can hold anything that was on screen; drop it as soon
            // as the translation no longer needs the pixels.
            capture.release(screen)
        }
    }

    private suspend fun translateCaptured(
        screen: CapturedScreen,
        source: Language,
        target: Language,
        showOverlay: Boolean,
    ): Translation {
        // Source and target match: translating would just repeat the screen, and
        // drawing an identical overlay is pure noise. Report the text only.
        if (source == target && source.isTranslatable) {
            val blocks = ocr.recognize(screen, target.script)
            return finish(blocks, TranslationEngine.ON_DEVICE, target, target, showOverlay = false)
        }

        val localBlocks = runCatching {
            ocr.recognize(screen, source.script)
        }.getOrDefault(emptyList())

        if (isGoodEnough(localBlocks)) {
            return translateLocally(localBlocks, source, target, showOverlay)
        }

        if (cloud.isConfigured() && connectivity.isOnline()) {
            try {
                val result = cloud.translate(screen, source, target)
                if (showOverlay) capture.showOverlay(result.blocks, capture.lastScreenSize())
                return result
            } catch (e: TranslationException.InvalidApiKey) {
                throw e
            } catch (e: TranslationException.QuotaExceeded) {
                throw e
            } catch (e: TranslationException) {
                // Fall through to whatever the device managed to read.
            }
        }

        if (localBlocks.isEmpty()) throw TranslationException.NoTextFound()
        return translateLocally(localBlocks, source, target, showOverlay)
    }

    private suspend fun translateLocally(
        blocks: List<TextBlock>,
        requestedSource: Language,
        target: Language,
        showOverlay: Boolean,
    ): Translation {
        if (blocks.isEmpty()) throw TranslationException.NoTextFound()

        val source = if (requestedSource == Language.AUTO) {
            val detected = translator.detect(blocks.joinToString(" ") { it.original })
            if (detected.isTranslatable && detected != target) {
                detected
            } else {
                // When language detection returns UNKNOWN (e.g. langid model downloading or low confidence)
                // or matches target language, check OCR blocks or fall back to English for Latin text.
                blocks.map { it.language }.firstOrNull { it.isTranslatable && it != target }
                    ?: Language.ENGLISH
            }
        } else {
            requestedSource
        }

        val lines = when {
            !source.isTranslatable -> blocks.map { it.original }
            source == target -> blocks.map { it.original }
            else -> translator.translate(blocks.map { it.original }, source, target)
        }

        val merged = blocks.mapIndexed { index, block ->
            block.copy(translated = lines.getOrElse(index) { block.original })
        }
        return finish(merged, TranslationEngine.ON_DEVICE, source, target, showOverlay)
    }

    private fun finish(
        blocks: List<TextBlock>,
        engine: TranslationEngine,
        source: Language,
        target: Language,
        showOverlay: Boolean,
    ): Translation {
        if (blocks.isEmpty()) throw TranslationException.NoTextFound()
        if (showOverlay) capture.showOverlay(blocks, capture.lastScreenSize())
        return Translation(
            blocks = blocks,
            engine = engine,
            source = if (source == Language.UNKNOWN) Language.AUTO else source,
            target = target,
        )
    }

    /**
     * Volume of text times mean confidence. Confidence is null for scripts that
     * never report it; then only volume counts rather than scoring everything 0.
     */
    private fun isGoodEnough(blocks: List<TextBlock>): Boolean {
        val chars = blocks.sumOf { it.original.length }
        if (chars == 0) return false

        val volume = (chars / VOLUME_FULL_SCORE_CHARS).coerceAtMost(1.0)
        val confidences = blocks.mapNotNull { it.confidence }
        val score = if (confidences.isEmpty()) volume else volume * confidences.average()
        return score >= QUALITY_THRESHOLD
    }

    private companion object {
        const val QUALITY_THRESHOLD = 0.55
        const val VOLUME_FULL_SCORE_CHARS = 20.0
    }
}
