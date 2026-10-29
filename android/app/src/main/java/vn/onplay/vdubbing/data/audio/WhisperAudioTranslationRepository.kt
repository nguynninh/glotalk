package vn.onplay.vdubbing.data.audio

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
        val original = transcribe(wav, source, prompt).trim()
        currentCoroutineContext().ensureActive()
        if (original.isBlank()) return null
        val detected = if (source == Language.AUTO) {
            translation.detect(original).takeIf { it.isTranslatable }
                ?: if (context.isNotEmpty()) translation.detect("$prompt $original".takeLast(1200))
                else Language.UNKNOWN
        } else source
        if (!detected.isTranslatable) {
            throw TranslationException.TranslationFailed("Không xác định được ngôn ngữ lời nói. Hãy chọn ngôn ngữ nguồn.")
        }
        val translated = if (detected == target) original else {
            translation.translate(listOf(original), detected, target).singleOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw TranslationException.TranslationFailed("Không dịch được lời nói.")
        }
        currentCoroutineContext().ensureActive()
        return AudioTranslation(original, translated)
    }
}
