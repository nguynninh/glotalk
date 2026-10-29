package vn.onplay.vdubbing.data.audio

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class WhisperModelStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "whisper")
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun prepare(): Pair<File, File> = mutex.withLock {
        ensure(MODEL) to ensure(VAD)
    }

    private suspend fun ensure(spec: Model): File {
        val target = File(directory, spec.name)
        val valid = withContext(Dispatchers.IO) {
            if (!target.isFile || target.length() != spec.bytes) false
            else target.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().hex() == spec.sha256
            }
        }
        if (valid) return target
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(Request.Builder().url(spec.url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val partial = File(directory, "${spec.name}.${UUID.randomUUID()}.part")
                    val result = runCatching {
                        response.use {
                            check(it.isSuccessful)
                            check(directory.isDirectory || directory.mkdirs())
                            check(directory.usableSpace > spec.bytes + 10 * 1024 * 1024)
                            val body = it.body ?: error("empty model download")
                            val digest = MessageDigest.getInstance("SHA-256")
                            var total = 0L
                            body.byteStream().use { input ->
                                partial.outputStream().use { output ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        check(!continuation.isCancelled)
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        check(total <= spec.bytes)
                                        digest.update(buffer, 0, count)
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            check(total == spec.bytes && digest.digest().hex() == spec.sha256)
                            check(!continuation.isCancelled)
                            check(partial.renameTo(target))
                            target
                        }
                    }
                    partial.delete()
                    if (!continuation.isCancelled) continuation.resumeWith(result)
                }
            })
        }
    }

    private data class Model(val name: String, val url: String, val bytes: Long, val sha256: String)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private val MODEL = Model(
            "ggml-small-q5_1.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-small-q5_1.bin",
            190085487,
            "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        )
        private val VAD = Model(
            "ggml-silero-v6.2.0.bin",
            "https://huggingface.co/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v6.2.0.bin",
            885098,
            "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987",
        )
    }
}
