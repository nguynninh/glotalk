package vn.onplay.vdubbing.data.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.UUID
import vn.onplay.vdubbing.data.audio.AudioTrace
import vn.onplay.vdubbing.data.audio.traceAudio
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import vn.onplay.vdubbing.data.audio.SpeechRequest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import vn.onplay.vdubbing.domain.repository.VietnameseSpeechRepository

internal class VietnameseSpeechRepositoryImpl(
    context: Context,
    private val steps: Int = 8,
) : VietnameseSpeechRepository {
    private val models = KorvaModelStore(context)
    private val mutex = Mutex()
    private var engine: KorvaTtsEngine? = null

    suspend fun prepare() {
        mutex.withLock { loadEngine {} }
    }

    private suspend fun loadEngine(onProgress: (Float) -> Unit): KorvaTtsEngine {
        val trace = currentCoroutineContext()[AudioTrace] ?: AudioTrace("setup")
        val start = SystemClock.elapsedRealtime()
        trace.log("tts_model_prepare", "loaded=${engine != null} steps=$steps")
        var lastPercent = -10
        val directory = if (engine == null) models.prepare { progress ->
            onProgress(progress)
            val percent = (progress * 100).toInt()
            if (percent >= lastPercent + 10 || percent == 100 && lastPercent != 100) {
                lastPercent = percent
                trace.log("tts_assets_progress", "percent=$percent")
            }
        } else null
        onProgress(1f)
        return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            (engine ?: KorvaTtsEngine(directory!!).also { engine = it }).also {
                trace.log("tts_model_ready", "duration_ms=${SystemClock.elapsedRealtime() - start}")
            }
        }
    }

    override suspend fun speak(text: String, onProgress: (Float) -> Unit, onReady: () -> Unit) {
        val trace = currentCoroutineContext()[AudioTrace] ?: AudioTrace("tts-${UUID.randomUUID().toString().take(8)}")
        withContext(trace) {
            speakRequests(flowOf(SpeechRequest(text, trace)), onProgress, onReady)
        }
    }

    suspend fun speakRequests(
        requests: Flow<SpeechRequest>,
        onProgress: (Float) -> Unit = {},
        onReady: () -> Unit = {},
    ) {
        data class Part(val request: SpeechRequest, val audio: FloatArray, val index: Int, val last: Boolean)
        mutex.withLock {
            val tts = loadEngine(onProgress)
            withContext(Dispatchers.Default) {
                flow {
                    requests.collect { request ->
                        val chunks = KorvaText.chunks(request.text, limit = 90)
                        for ((index, chunk) in chunks.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            val start = SystemClock.elapsedRealtime()
                            request.trace.log("tts_started", "part=${index + 1}/${chunks.size} chars=${chunk.length} steps=$steps")
                            val audio = tts.synthesize(chunk, steps) { step, total ->
                                if (step == 1 || step % 4 == 0 || step == total) {
                                    request.trace.log("tts_progress", "part=${index + 1}/${chunks.size} step=$step/$total")
                                }
                            }
                            request.trace.log("pcm_ready", "part=${index + 1}/${chunks.size} synthesis_ms=${SystemClock.elapsedRealtime() - start} samples=${audio.size} sample_rate=${tts.sampleRate} audio_ms=${audio.size * 1000L / tts.sampleRate} format=float32 file_saved=false")
                            emit(Part(request, audio, index + 1, index == chunks.lastIndex))
                        }
                    }
                }.buffer(0).collect { part ->
                    withContext(part.request.trace) {
                        withContext(Dispatchers.Main) { onReady() }
                        play(part.audio, tts.sampleRate, part.index)
                        if (part.last) part.request.onCompleted()
                    }
                }
            }
        }
    }

    override suspend fun close() = withContext(Dispatchers.Default) {
        mutex.withLock {
            engine?.close()
            engine = null
        }
    }

    private suspend fun play(samples: FloatArray, sampleRate: Int, part: Int) = withContext(Dispatchers.IO) {
        require(samples.isNotEmpty())
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4)
            .build()
        try {
            check(track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) == samples.size)
            check(track.state == AudioTrack.STATE_INITIALIZED)
            val done = CompletableDeferred<Unit>()
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) { done.complete(Unit) }
                override fun onPeriodicNotification(track: AudioTrack) = Unit
            }, Handler(Looper.getMainLooper()))
            check(track.setNotificationMarkerPosition(samples.size) == AudioTrack.SUCCESS)
            currentCoroutineContext().ensureActive()
            track.play()
            traceAudio("playback_started", "part=$part audio_session=${track.audioSessionId} samples=${samples.size}")
            withTimeout(samples.size * 1000L / sampleRate + 5000) { done.await() }
            traceAudio("playback_completed", "part=$part frames=${track.playbackHeadPosition}")
        } finally {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            } finally { track.release() }
        }
    }
}
