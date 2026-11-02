package vn.onplay.vdubbing.framework.audio

import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.data.audio.AudioTrace

internal suspend fun playOnPlayAudio(
    clips: Flow<ByteArray>,
    translate: suspend (ByteArray) -> ByteArray?,
    play: suspend (ByteArray, () -> Unit) -> Unit,
    onStage: (AudioStage) -> Unit,
) = coroutineScope {
    data class Clip(val wav: ByteArray, val trace: AudioTrace, val capturedAt: Long)
    val session = UUID.randomUUID().toString().take(8)
    var sequence = 0
    val pending = Channel<Clip>(3)
    val translated = Channel<Clip>(Channel.RENDEZVOUS)
    var queued = 0
    var translating = false
    var speaking = false
    fun update() = onStage(when {
        speaking -> AudioStage.SPEAKING
        translating -> AudioStage.TRANSLATING
        else -> AudioStage.LISTENING
    })
    val capture = launch {
        try {
            clips.collect { wav ->
                val clip = Clip(wav, AudioTrace("$session-${++sequence}"), SystemClock.elapsedRealtime())
                clip.trace.log("capture_end", "at_ms=${clip.capturedAt} route=onplay")
                queued++
                if (!pending.trySend(clip).isSuccess) {
                    queued--
                    clip.trace.log("backlog_overflow", "capacity=3 action=stop_session")
                    throw OfflineAudioBacklogException()
                }
                clip.trace.log("capture_enqueued", "capture_queue=$queued")
            }
        } finally { pending.close() }
    }
    val requests = launch {
        try {
            for (clip in pending) {
                queued--
                withContext(clip.trace) {
                    translating = true
                    update()
                    clip.trace.log("request_start", "queue_ms=${SystemClock.elapsedRealtime() - clip.capturedAt} capture_queue=$queued")
                    try {
                        val audio = translate(clip.wav)
                        clip.trace.log("response_ready")
                        if (audio != null) {
                            clip.trace.log("playback_wait", "response_queue=0 waiting_handoff=1")
                            translated.send(clip.copy(wav = audio))
                        }
                    } finally {
                        translating = false
                        update()
                    }
                }
            }
        } finally { translated.close() }
    }
    try {
        update()
        for (clip in translated) {
            withContext(clip.trace) {
                try {
                    clip.trace.log("playback_prepare", "response_queue=0 waiting_handoff=0")
                    play(clip.wav) {
                        speaking = true
                        update()
                    }
                    clip.trace.log("clip_completed")
                } finally {
                    speaking = false
                    update()
                }
            }
        }
    } finally {
        capture.cancel()
        requests.cancel()
        pending.cancel()
        translated.cancel()
    }
}
