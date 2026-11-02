package vn.onplay.vdubbing.data.audio

import android.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import vn.onplay.vdubbing.domain.model.Language

internal class OpenAiRealtimeAudioException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { KEY, LIMIT, NETWORK, API, TOO_SLOW }
}

internal class OpenAiRealtimeTranslation(
    private val sockets: WebSocket.Factory = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    fun audio(key: String, target: Language, input: Flow<ByteArray>): Flow<ByteArray> = channelFlow {
        require(target in Language.TARGETS)
        if (key.isBlank() || key.length > 512 || key.any { it.code !in 33..126 }) {
            throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.KEY)
        }
        val events = Channel<Pair<String, Long>>(Channel.UNLIMITED)
        val queuedCharacters = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate")
            .header("Authorization", "Bearer $key")
            .build()
        val socket = sockets.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val session = JSONObject().put("audio", JSONObject()
                    .put("output", JSONObject().put("language", target.code)))
                if (!webSocket.send(JSONObject().put("type", "session.update").put("session", session).toString())) {
                    events.close(OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.NETWORK))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())

            override fun onMessage(webSocket: WebSocket, text: String) {
                val weight = maxOf(text.length, 256)
                if (queuedCharacters.addAndGet(weight) > 256_000 ||
                    events.trySend(text to System.nanoTime()).isFailure) {
                    queuedCharacters.addAndGet(-weight)
                    events.close(OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.TOO_SLOW))
                    webSocket.cancel()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = when (response?.code) {
                    401, 403 -> OpenAiRealtimeAudioException.Reason.KEY
                    429 -> OpenAiRealtimeAudioException.Reason.LIMIT
                    null -> OpenAiRealtimeAudioException.Reason.NETWORK
                    else -> OpenAiRealtimeAudioException.Reason.API
                }
                response?.body?.close()
                events.close(OpenAiRealtimeAudioException(reason))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.close(OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.NETWORK))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.close(OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.NETWORK))
            }
        })
        val receiver = launch {
            for ((raw, at) in events) {
                queuedCharacters.addAndGet(-maxOf(raw.length, 256))
                if (System.nanoTime() - at > TimeUnit.SECONDS.toNanos(2)) {
                    throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.TOO_SLOW)
                }
                val event = try { JSONObject(raw) } catch (_: Exception) {
                    throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.API)
                }
                when (event.optString("type")) {
                    "error" -> {
                        val error = event.optJSONObject("error")
                        val reason = when (error?.optString("code")) {
                            "invalid_api_key", "invalid_authorization", "permission_denied" -> OpenAiRealtimeAudioException.Reason.KEY
                            "insufficient_quota", "rate_limit_exceeded" -> OpenAiRealtimeAudioException.Reason.LIMIT
                            else -> OpenAiRealtimeAudioException.Reason.API
                        }
                        throw OpenAiRealtimeAudioException(reason)
                    }
                    "session.updated" -> ready.complete(Unit)
                    "session.output_audio.delta" -> {
                        if (!ready.isCompleted) throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.API)
                        val pcm = try { Base64.decode(event.getString("delta"), Base64.NO_WRAP) } catch (_: Exception) {
                            throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.API)
                        }
                        if (pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > 96_000) {
                            throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.API)
                        }
                        send(pcm)
                    }
                    "session.closed" -> {
                        finished.complete(Unit)
                        events.close()
                    }
                }
            }
        }
        val sender = launch {
            try {
                withTimeout(15000) { ready.await() }
                input.collect { pcm ->
                    require(pcm.isNotEmpty() && pcm.size % 2 == 0)
                    // Two seconds of queued PCM/base64 already defeats live playback.
                    if (socket.queueSize() > 128_000) {
                        throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.TOO_SLOW)
                    }
                    val event = JSONObject().put("type", "session.input_audio_buffer.append")
                        .put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP))
                    if (!socket.send(event.toString())) {
                        throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.NETWORK)
                    }
                }
                if (!socket.send(JSONObject().put("type", "session.close").toString())) {
                    throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.NETWORK)
                }
                withTimeout(15000) { finished.await() }
            } catch (_: TimeoutCancellationException) {
                throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.NETWORK)
            }
        }
        try {
            receiver.join()
            if (!finished.isCompleted) throw OpenAiRealtimeAudioException(OpenAiRealtimeAudioException.Reason.NETWORK)
        } finally {
            socket.cancel()
            sender.cancel()
            receiver.cancel()
            events.cancel()
        }
    }.buffer(0)
}
