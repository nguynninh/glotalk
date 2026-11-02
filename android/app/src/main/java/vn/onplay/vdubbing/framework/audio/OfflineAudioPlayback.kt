package vn.onplay.vdubbing.framework.audio

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.domain.model.AudioTranslation

internal suspend fun playOfflineAudio(
    clips: Flow<ByteArray>,
    translate: suspend (ByteArray) -> AudioTranslation?,
    speak: suspend (String) -> Unit,
    onStage: (AudioStage) -> Unit,
) = coroutineScope {
    // Finish the current speech, then catch up to the latest captured clip.
    val pending = Channel<ByteArray>(1, BufferOverflow.DROP_OLDEST)
    val capture = launch {
        try {
            clips.collect { pending.send(it) }
        } finally {
            pending.close()
        }
    }
    try {
        onStage(AudioStage.LISTENING)
        for (clip in pending) {
            onStage(AudioStage.TRANSLATING)
            val result = translate(clip)
            if (result != null) {
                onStage(AudioStage.SPEAKING)
                speak(result.translated)
            }
            onStage(AudioStage.LISTENING)
        }
    } finally {
        capture.cancel()
        pending.cancel()
    }
}
