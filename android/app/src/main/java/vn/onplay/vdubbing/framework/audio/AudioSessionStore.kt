package vn.onplay.vdubbing.framework.audio

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.onplay.vdubbing.domain.model.Language

internal enum class AudioStage { PERMISSION, CONNECTING, PREPARING_MODEL, LISTENING, TRANSLATING, SPEAKING, NO_AUDIO }

internal data class AudioSession(
    val id: String,
    val packageName: String,
    val uid: Int,
    val source: Language,
    val target: Language,
    val stage: AudioStage = AudioStage.PERMISSION,
)

internal class AudioSessionStore {
    private val mutable = MutableStateFlow<AudioSession?>(null)
    val state = mutable.asStateFlow()

    fun begin(packageName: String, uid: Int, source: Language, target: Language): AudioSession {
        check(mutable.value == null)
        return AudioSession(UUID.randomUUID().toString(), packageName, uid, source, target)
            .also { mutable.value = it }
    }

    fun update(id: String, stage: AudioStage) {
        val session = mutable.value ?: return
        if (session.id == id) mutable.value = session.copy(stage = stage)
    }

    fun end(id: String) {
        if (mutable.value?.id == id) mutable.value = null
    }
}
