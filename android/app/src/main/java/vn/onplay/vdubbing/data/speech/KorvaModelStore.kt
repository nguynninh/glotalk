package vn.onplay.vdubbing.data.speech

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
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
import org.json.JSONObject

internal class KorvaModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun prepare(onProgress: (Float) -> Unit = {}): File = mutex.withLock {
        withContext(Dispatchers.IO) {
            val manifest = appContext.assets.open("korva-model.json").bufferedReader().use {
                JSONObject(it.readText())
            }
            val directory = File(appContext.noBackupFilesDir, "korva/${manifest.getString("revision")}")
            val files = manifest.getJSONArray("files")
            val total = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("bytes") }
            var completed = 0L
            for (index in 0 until files.length()) {
                currentCoroutineContext().ensureActive()
                val spec = files.getJSONObject(index)
                val path = spec.getString("path")
                val bytes = spec.getLong("bytes")
                val hash = spec.getString("sha256")
                val target = File(directory, path)
                val valid = target.isFile && target.length() == bytes && target.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().hex() == hash
                }
                if (!valid) {
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    check(directory.usableSpace > bytes + 16 * 1024 * 1024)
                    download(target, bytes, hash,
                        "https://huggingface.co/dogenthq/KorvaTTS/resolve/${manifest.getString("revision")}/$path") {
                        onProgress((completed + it).toFloat() / total)
                    }
                }
                completed += bytes
                onProgress(completed.toFloat() / total)
            }
            directory
        }
    }

    private suspend fun download(target: File, bytes: Long, hash: String, url: String, progress: (Long) -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val call = http.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    val partial = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.part")
                    val result = runCatching {
                        response.use {
                            check(it.isSuccessful) { "Korva download HTTP ${it.code}" }
                            val digest = MessageDigest.getInstance("SHA-256")
                            var count = 0L
                            (it.body ?: error("Empty model response")).byteStream().use { input ->
                                partial.outputStream().use { output ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        check(!continuation.isCancelled)
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        count += read
                                        check(count <= bytes)
                                        digest.update(buffer, 0, read)
                                        output.write(buffer, 0, read)
                                        progress(count)
                                    }
                                }
                            }
                            check(count == bytes && digest.digest().hex() == hash) { "Invalid Korva asset" }
                            check(!continuation.isCancelled)
                            check(partial.renameTo(target))
                        }
                    }
                    partial.delete()
                    if (!continuation.isCancelled) continuation.resumeWith(result)
                }
            })
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private val mutex = Mutex()
    }
}
