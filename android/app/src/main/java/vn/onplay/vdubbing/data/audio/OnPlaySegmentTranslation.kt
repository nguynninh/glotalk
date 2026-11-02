package vn.onplay.vdubbing.data.audio

import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import vn.onplay.vdubbing.domain.model.Language

internal class OnPlaySegmentTranslation(
    private val sockets: WebSocket.Factory = client,
) {
    fun audio(
        source: Language,
        target: Language,
        input: Flow<ByteArray>,
        onSpeechError: () -> Unit,
    ): Flow<ByteArray> = channelFlow {
        require(target in Language.TARGETS)
        val events = Channel<String>(32)
        val queued = AtomicLong()
        val ready = CompletableDeferred<Unit>()
        var done = false
        var speechEnabled = true
        var lastId = -1L
        val request = Request.Builder().url("https://stt.qiverse.top/ws")
            .header("Origin", "https://stt.qiverse.top")
            .header("User-Agent", "ONPlayMobile/1.0").build()
        val socket = sockets.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val config = JSONObject().put("target_language", target.code)
                    .put("speech", true).put("speakers", false).put("chunk", 100)
                if (source.isTranslatable) config.put("source_language", source.code)
                if (!webSocket.send(config.toString())) events.close(IOException("ONPlay configuration failed"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (queued.addAndGet(text.length.toLong()) > MAX_MESSAGE || events.trySend(text).isFailure) {
                    queued.addAndGet(-text.length.toLong())
                    events.close(OnPlayBacklogException())
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events.close(IOException("ONPlay expected JSON"))
                webSocket.cancel()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                events.close(IOException("ONPlay connection failed", t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.close(IOException("ONPlay disconnected"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.close(IOException("ONPlay disconnected"))
            }
        })
        val sender = launch {
            try {
                withTimeout(15000) { ready.await() }
            } catch (failure: TimeoutCancellationException) {
                throw IOException("ONPlay ready timeout", failure)
            }
            input.collect { pcm ->
                require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 32000)
                if (socket.queueSize() + pcm.size > 64000) throw OnPlayBacklogException()
                if (!socket.send(pcm.toByteString())) throw IOException("ONPlay upload failed")
            }
            if (!socket.send("""{"type":"end"}""")) throw IOException("ONPlay end failed")
        }
        try {
            for (raw in events) {
                queued.addAndGet(-raw.length.toLong())
                val event = JSONObject(raw)
                when (event.getString("type")) {
                    "ready" -> ready.complete(Unit)
                    "audio" -> {
                        if (!ready.isCompleted) throw IOException("ONPlay audio before ready")
                        if (!speechEnabled) continue
                        val id = event.getLong("id")
                        if (id <= lastId || event.getString("revision") != "live" ||
                            event.getString("language") != target.code ||
                            event.getString("content_type") != "audio/wav" ||
                            event.getString("encoding") != "base64") throw IOException("ONPlay invalid audio metadata")
                        val encoded = event.getString("data")
                        if (encoded.length > (MAX_WAV + 2L) / 3 * 4) throw IOException("ONPlay WAV too large")
                        val wav = Base64.decode(encoded, Base64.DEFAULT)
                        if (wav.size !in 45..MAX_WAV || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                            String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") throw IOException("ONPlay invalid WAV")
                        lastId = id
                        send(wav)
                    }
                    "audio_error" -> if (speechEnabled) {
                        speechEnabled = false
                        onSpeechError()
                    }
                    "error" -> throw IOException("ONPlay translation failed")
                    "done" -> { done = true; break }
                }
            }
            if (!done) throw IOException("ONPlay ended without done")
        } finally {
            socket.cancel()
            sender.cancel()
            events.cancel()
        }
    }.buffer(0).flowOn(Dispatchers.Default)

    private companion object {
        const val MAX_WAV = 32 * 1024 * 1024
        const val MAX_MESSAGE = 46L * 1024 * 1024
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }
}

internal class OnPlayBacklogException : IOException("ONPlay playback cannot keep up")
