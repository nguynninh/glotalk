package vn.onplay.vdubbing.framework.audio

import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import vn.onplay.vdubbing.data.audio.SpeechRequest
import vn.onplay.vdubbing.data.audio.AudioTrace
import vn.onplay.vdubbing.domain.model.AudioTranslation

internal class OfflineAudioBacklogException : IllegalStateException("Offline audio queue is full")

internal suspend fun playOfflineAudio(
    clips: Flow<ByteArray>,
    translate: suspend (ByteArray) -> AudioTranslation?,
    speak: suspend (String) -> Unit,
    onStage: (AudioStage) -> Unit,
    nowMillis: () -> Long = SystemClock::elapsedRealtime,
    speakStream: (suspend (Flow<SpeechRequest>) -> Unit)? = null,
) = coroutineScope {
    data class Clip(val wav: ByteArray, val trace: AudioTrace, val received: Long)
    data class Speech(val text: String, val trace: AudioTrace)
    val session = UUID.randomUUID().toString().take(8)
    var sequence = 0
    val pending = Channel<Clip>(3)
    val translated = Channel<Speech>(1)
    var recognizing = false
    var speechPending = 0
    val stageContext = currentCoroutineContext().minusKey(Job)
    fun updateStage() = onStage(when {
        speechPending > 0 -> AudioStage.SPEAKING
        recognizing -> AudioStage.TRANSLATING
        else -> AudioStage.LISTENING
    })
    val capture = launch {
        try {
            clips.collect {
                val trace = AudioTrace("$session-${++sequence}")
                trace.log("clip_received", "wav_bytes=${it.size} route=local backend=none")
                if (!pending.trySend(Clip(it, trace, nowMillis())).isSuccess) {
                    trace.log("backlog_overflow", "action=stop_session capacity=3")
                    throw OfflineAudioBacklogException()
                }
            }
        } finally {
            pending.close()
        }
    }
    val recognition = launch {
        try {
            for (clip in pending) {
                withContext(clip.trace) {
                    try {
                        recognizing = true
                        updateStage()
                        clip.trace.log("clip_processing", "queue_ms=${nowMillis() - clip.received}")
                        val result = translate(clip.wav)
                        if (result != null) {
                            clip.trace.log("translation_ready", "source_chars=${result.original.length} translated_chars=${result.translated.length}")
                            translated.send(Speech(result.translated, clip.trace))
                        } else clip.trace.log("clip_skipped", "reason=no_speech")
                    } catch (cancelled: CancellationException) {
                        clip.trace.log("clip_cancelled", "stage=recognition")
                        throw cancelled
                    } catch (error: Exception) {
                        clip.trace.log("clip_failed", "type=${error.javaClass.simpleName}")
                        throw error
                    } finally {
                        recognizing = false
                        updateStage()
                    }
                }
            }
        } finally {
            translated.close()
        }
    }
    try {
        updateStage()
        if (speakStream != null) {
            speakStream(translated.receiveAsFlow().map { speech ->
                withContext(stageContext) {
                    speechPending++
                    updateStage()
                }
                SpeechRequest(speech.text, speech.trace) {
                    withContext(stageContext) {
                        speech.trace.log("clip_completed")
                        speechPending--
                        updateStage()
                    }
                }
            })
        } else for (speech in translated) {
            withContext(speech.trace) {
                try {
                    speechPending++
                    updateStage()
                    speak(speech.text)
                    speech.trace.log("clip_completed")
                } catch (cancelled: CancellationException) {
                    speech.trace.log("clip_cancelled", "stage=speech")
                    throw cancelled
                } catch (error: Exception) {
                    speech.trace.log("clip_failed", "type=${error.javaClass.simpleName}")
                    throw error
                } finally {
                    speechPending--
                    updateStage()
                }
            }
        }
    } finally {
        capture.cancel()
        recognition.cancel()
        pending.cancel()
        translated.cancel()
    }
}
