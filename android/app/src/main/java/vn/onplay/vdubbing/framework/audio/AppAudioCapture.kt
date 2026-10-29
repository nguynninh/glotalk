package vn.onplay.vdubbing.framework.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.data.audio.PcmSpeechChunker

internal class AppAudioCapture(private val projection: MediaProjection, private val uid: Int) {
    suspend fun collect(onSilence: () -> Unit, onClip: (ByteArray) -> Unit) {
        val chunker = PcmSpeechChunker()
        collectFrames(PcmSpeechChunker.SAMPLE_RATE, 20, onSilence) { frame ->
            chunker.accept(frame)?.let(onClip)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun collectFrames(
        sampleRate: Int,
        frameMillis: Int,
        onSilence: () -> Unit,
        onFrame: suspend (ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val frameBytes = sampleRate * 2 * frameMillis / 1000
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUid(uid)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0)
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum * 2, frameBytes * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED)
            currentCoroutineContext().ensureActive()
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val frame = ByteArray(frameBytes)
            var offset = 0
            var lastSound = SystemClock.elapsedRealtime()
            var reportedSilence = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = record.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                check(count > 0) { "AudioRecord read failed: $count" }
                offset += count
                if (offset < frame.size) continue
                offset = 0
                if (PcmSpeechChunker.isAudible(frame)) {
                    lastSound = SystemClock.elapsedRealtime()
                    reportedSilence = false
                } else if (!reportedSilence && SystemClock.elapsedRealtime() - lastSound > 15000) {
                    onSilence()
                    reportedSilence = true
                }
                onFrame(frame)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}
