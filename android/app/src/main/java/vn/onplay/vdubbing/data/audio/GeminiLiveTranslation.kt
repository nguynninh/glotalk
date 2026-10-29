package vn.onplay.vdubbing.data.audio

import android.util.Base64
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import vn.onplay.vdubbing.BuildConfig
import vn.onplay.vdubbing.domain.model.Language

internal class GeminiLiveAudioException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { KEY, LIMIT, NETWORK, API, TOO_SLOW }
}

internal class GeminiLiveTranslation(
    private val sockets: WebSocket.Factory = defaultClient(),
) {
    fun audio(
        key: String,
        target: Language,
        input: Flow<ByteArray>,
    ): Flow<ByteArray> = channelFlow {
        require(target in Language.TARGETS)
        if (key.isBlank() || key.length > 512 || key.any { it.code !in 33..126 }) {
            throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.KEY)
        }
        val events = Channel<Pair<String, Long>>(Channel.UNLIMITED)
        val queuedCharacters = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val firstSoundSentAt = AtomicLong()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
                .toHttpUrl().newBuilder().addQueryParameter("key", key).build())
            .build()
        val socket = sockets.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val translation = JSONObject()
                    .put("targetLanguageCode", if (target == Language.CHINESE) "zh-Hans" else target.code)
                    .put("echoTargetLanguage", false)
                val setup = JSONObject().put("model", "models/gemini-3.5-live-translate-preview")
                    .put("generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("translationConfig", translation))
                if (!webSocket.send(JSONObject().put("setup", setup).toString())) {
                    events.close(GeminiLiveAudioException(GeminiLiveAudioException.Reason.NETWORK))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Small audio deltas can arrive in bursts; bound data rather than packet count.
                val weight = maxOf(text.length, 256)
                if (queuedCharacters.addAndGet(weight) > 256_000 ||
                    events.trySend(text to System.nanoTime()).isFailure) {
                    queuedCharacters.addAndGet(-weight)
                    events.close(GeminiLiveAudioException(GeminiLiveAudioException.Reason.TOO_SLOW))
                    webSocket.cancel()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = runCatching {
                    response?.peekBody(8192)?.string()?.let {
                        JSONObject(it).optJSONObject("error")
                    }
                }.getOrNull()
                val reason = errorReason(error, response?.code)
                Log.w("GeminiLiveAudio", "WebSocket failed: http=${response?.code}, reason=$reason")
                response?.body?.close()
                events.close(GeminiLiveAudioException(reason))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.close(GeminiLiveAudioException(closeReason(code, reason)))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.close(GeminiLiveAudioException(closeReason(code, reason)))
            }
        })
        val receiver = launch {
            var receivedAudio = false
            for ((raw, at) in events) {
                queuedCharacters.addAndGet(-maxOf(raw.length, 256))
                if (System.nanoTime() - at > TimeUnit.SECONDS.toNanos(2)) {
                    throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.TOO_SLOW)
                }
                val event = try { JSONObject(raw) } catch (_: Exception) {
                    throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.API)
                }
                when {
                    event.has("error") -> {
                        val error = event.optJSONObject("error")
                        throw GeminiLiveAudioException(errorReason(error, error?.optInt("code", 400)))
                    }
                    event.has("goAway") -> throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.NETWORK)
                    event.has("setupComplete") -> ready.complete(Unit)
                    event.has("serverContent") -> {
                        if (!ready.isCompleted) throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.API)
                        val content = event.getJSONObject("serverContent")
                        if (content.optBoolean("interrupted")) {
                            throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.API)
                        }
                        val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: continue
                        for (index in 0 until parts.length()) {
                            val audio = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
                            if (audio.optString("mimeType") != "audio/pcm;rate=24000") {
                                throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.API)
                            }
                            val pcm = try {
                                Base64.decode(audio.getString("data"), Base64.NO_WRAP)
                            } catch (_: Exception) {
                                throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.API)
                            }
                            if (pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > 96_000) {
                                throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.API)
                            }
                            if (BuildConfig.DEBUG && !receivedAudio) {
                                receivedAudio = true
                                val started = firstSoundSentAt.get()
                                if (started != 0L) {
                                    Log.d("GeminiLiveAudio", "first_sound_to_response_ms=${TimeUnit.NANOSECONDS.toMillis(at - started)}")
                                }
                            }
                            send(pcm)
                        }
                    }
                }
            }
        }
        val sender = launch {
            try {
                withTimeout(15000) { ready.await() }
            } catch (_: TimeoutCancellationException) {
                throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.NETWORK)
            }
            input.collect { pcm ->
                require(pcm.isNotEmpty() && pcm.size % 2 == 0)
                // Two seconds of queued PCM/base64 already defeats live playback.
                if (socket.queueSize() > 86_000) throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.TOO_SLOW)
                val event = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                    .put("mimeType", "audio/pcm;rate=16000")
                    .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))))
                if (BuildConfig.DEBUG && firstSoundSentAt.get() == 0L && PcmSpeechChunker.isAudible(pcm)) {
                    firstSoundSentAt.compareAndSet(0L, System.nanoTime())
                }
                if (!socket.send(event.toString())) throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.NETWORK)
            }
            if (!socket.send(JSONObject().put("realtimeInput", JSONObject()
                    .put("audioStreamEnd", true)).toString())) {
                throw GeminiLiveAudioException(GeminiLiveAudioException.Reason.NETWORK)
            }
        }
        awaitClose {
            socket.cancel()
            sender.cancel()
            receiver.cancel()
            events.cancel()
        }
    }.buffer(0)

    private companion object {
        fun errorReason(error: JSONObject?, status: Int? = null): GeminiLiveAudioException.Reason {
            val details = error?.optJSONArray("details")
            for (index in 0 until (details?.length() ?: 0)) {
                if (details?.optJSONObject(index)?.optString("reason") in
                    listOf("API_KEY_INVALID", "API_KEY_EXPIRED", "API_KEY_SERVICE_BLOCKED")) {
                    return GeminiLiveAudioException.Reason.KEY
                }
            }
            return when (error?.optString("status")) {
                "UNAUTHENTICATED", "PERMISSION_DENIED" -> GeminiLiveAudioException.Reason.KEY
                "RESOURCE_EXHAUSTED" -> GeminiLiveAudioException.Reason.LIMIT
                else -> when (status) {
                    401, 403 -> GeminiLiveAudioException.Reason.KEY
                    429 -> GeminiLiveAudioException.Reason.LIMIT
                    null -> GeminiLiveAudioException.Reason.NETWORK
                    else -> GeminiLiveAudioException.Reason.API
                }
            }
        }

        fun closeReason(code: Int, reason: String): GeminiLiveAudioException.Reason = when {
            reason.contains("API key", ignoreCase = true) ||
                reason.contains("PERMISSION_DENIED") || reason.contains("UNAUTHENTICATED") -> GeminiLiveAudioException.Reason.KEY
            code == 1013 || reason.contains("RESOURCE_EXHAUSTED") ||
                reason.contains("quota", ignoreCase = true) -> GeminiLiveAudioException.Reason.LIMIT
            code == 1007 || code == 1008 -> GeminiLiveAudioException.Reason.API
            else -> GeminiLiveAudioException.Reason.NETWORK
        }

        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
