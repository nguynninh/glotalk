package vn.onplay.vdubbing.framework.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import vn.onplay.vdubbing.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

internal class RealtimeAudioPlayer {
    @Volatile private var track: AudioTrack? = null

    suspend fun play(audio: Flow<ByteArray>, onPlaying: () -> Unit) = withContext(Dispatchers.IO) {
        val active = createRealtimeAudioTrack()
        track = active
        try {
            check(active.state == AudioTrack.STATE_INITIALIZED)
            currentCoroutineContext().ensureActive()
            active.play()
            audio.collect { pcm ->
                onPlaying()
                var offset = 0
                while (offset < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    val written = active.write(pcm, offset, minOf(pcm.size - offset, 4800), AudioTrack.WRITE_BLOCKING)
                    check(written > 0) { "AudioTrack write failed" }
                    offset += written
                }
            }
        } finally {
            track = null
            runCatching { active.pause() }
            runCatching { active.flush() }
            active.release()
        }
    }

    fun stop() {
        track?.let { active ->
            runCatching { active.pause() }
            runCatching { active.flush() }
        }
    }
}

internal fun createRealtimeAudioTrack(): AudioTrack {
    val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minimum > 0)
    return AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(minimum, 4800))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build().also { active ->
            if (Build.VERSION.SDK_INT >= 31) {
                val previous = active.startThresholdInFrames
                active.setStartThresholdInFrames(480)
                if (BuildConfig.DEBUG) {
                    Log.d("GeminiLiveAudio", "playback_start_threshold_ms=${previous * 1000 / 24000}->${active.startThresholdInFrames * 1000 / 24000}")
                }
            }
        }
}
