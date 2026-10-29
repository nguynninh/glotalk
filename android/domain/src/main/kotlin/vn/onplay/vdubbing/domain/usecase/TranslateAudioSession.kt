package vn.onplay.vdubbing.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vn.onplay.vdubbing.domain.model.AudioTranslation
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.repository.AudioTranslationRepository

class TranslateAudioSession(
    private val repository: AudioTranslationRepository,
    private val source: Language,
    private val target: Language,
) {
    private val context = ArrayDeque<AudioTranslation>()

    init {
        require(source in Language.SOURCES && target in Language.TARGETS)
    }

    suspend fun translate(wav: ByteArray): AudioTranslation? {
        val result = repository.translate(wav, source, target, context.toList())
        currentCoroutineContext().ensureActive()
        if (result == null) return null
        require(result.original.isNotBlank() && result.translated.isNotBlank())
        context.addLast(result)
        while (context.size > 3 || context.sumOf { it.original.length + it.translated.length } > 4000) {
            context.removeFirst()
        }
        return result
    }
}
