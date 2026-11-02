package vn.onplay.vdubbing.data.audio

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import vn.onplay.vdubbing.domain.model.Language
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OnPlayAudioTranslation : AutoCloseable {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS).build()

    suspend fun translate(wav: ByteArray, target: Language): ByteArray? {
        require(target in Language.TARGETS)
        require(wav.size in 45..2 * 1024 * 1024)
        val trace = currentCoroutineContext()[AudioTrace]
        val began = SystemClock.elapsedRealtime()
        fun metric(event: String, detail: String = "") {
            trace?.log("http_$event", "at_ms=${SystemClock.elapsedRealtime()} http_ms=${SystemClock.elapsedRealtime() - began} $detail")
        }
        metric("send")
        val request = Request.Builder()
            .url("https://stt.qiverse.top/api/translate-audio?target=${target.code}")
            .header("User-Agent", "ONPlayMobile/1.0")
            .post(wav.toRequestBody("audio/wav".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    metric("failure", "type=${e.javaClass.simpleName}")
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    metric("headers", "status=${response.code} timing=${response.header("Server-Timing").orEmpty()}")
                    val result = runCatching {
                        response.use {
                            if (it.code == 204) return@use null
                            if (!it.isSuccessful) throw IOException("ONPlay HTTP ${it.code}")
                            if (!it.header("Content-Type").orEmpty().contains("audio/wav", true)) {
                                throw IOException("ONPlay expected WAV")
                            }
                            val body = it.body ?: throw IOException("ONPlay empty body")
                            body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (output.size().toLong() + count > 32L * 1024 * 1024) {
                                        throw IOException("ONPlay WAV exceeds size limit")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray().also { audio ->
                                    if (audio.size <= 44 || String(audio, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                                        String(audio, 8, 4, Charsets.US_ASCII) != "WAVE") {
                                        throw IOException("ONPlay invalid WAV")
                                    }
                                }
                            }
                        }
                    }
                    metric("body_complete", "ok=${result.isSuccess}")
                    result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
