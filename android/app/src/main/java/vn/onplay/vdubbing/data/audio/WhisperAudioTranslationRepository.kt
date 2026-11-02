package vn.onplay.vdubbing.data.audio

import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vn.onplay.vdubbing.domain.model.AudioTranslation
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.AudioTranslationRepository
import vn.onplay.vdubbing.domain.repository.TranslationRepository

internal class WhisperAudioTranslationRepository(
    private val transcribe: suspend (ByteArray, Language, String) -> String,
    private val translation: TranslationRepository,
) : AudioTranslationRepository {
    override suspend fun translate(
        wav: ByteArray,
        source: Language,
        target: Language,
        context: List<AudioTranslation>,
    ): AudioTranslation? {
        val prompt = context.joinToString(" ") { it.original }.takeLast(600)
        val recognitionStart = SystemClock.elapsedRealtime()
        traceAudio("recognition_started", "wav_bytes=${wav.size} source=${source.code}")
        val original = transcribe(wav, source, prompt).trim()
        currentCoroutineContext().ensureActive()
        traceAudio("recognition_completed", "duration_ms=${SystemClock.elapsedRealtime() - recognitionStart} chars=${original.length}")
        if (original.isBlank()) return null
        val detected = if (source == Language.AUTO) {
            val detectionText = if (original.length < 12 && context.isNotEmpty()) {
                "$prompt $original".takeLast(1200)
            } else original
            translation.detect(detectionText).takeIf { it.isTranslatable }
                ?: if (context.isNotEmpty() && detectionText == original) translation.detect("$prompt $original".takeLast(1200))
                else Language.UNKNOWN
        } else source
        if (!detected.isTranslatable) {
            throw TranslationException.TranslationFailed("Không xác định được ngôn ngữ lời nói. Hãy chọn ngôn ngữ nguồn.")
        }
        val translationStart = SystemClock.elapsedRealtime()
        traceAudio("translation_started", "source=${detected.code} target=${target.code}")
        val translated = if (detected == target) original else {
            translation.translate(listOf(original), detected, target).singleOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw TranslationException.TranslationFailed("Không dịch được lời nói.")
        }
        currentCoroutineContext().ensureActive()
        traceAudio("translation_completed", "duration_ms=${SystemClock.elapsedRealtime() - translationStart} chars=${translated.length}")
        return AudioTranslation(original, translated)
    }
}
