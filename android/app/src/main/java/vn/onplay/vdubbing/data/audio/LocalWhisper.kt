package vn.onplay.vdubbing.data.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.domain.model.Language

internal class LocalWhisper {
    private val lock = Any()
    private var handle = 0L
    private var cancelled = false

    suspend fun initialize(model: File) = withContext(Dispatchers.Default) {
        val opened = nativeOpen(model.absolutePath)
        synchronized(lock) {
            check(handle == 0L)
            handle = opened
            if (cancelled) nativeCancel(opened)
        }
        currentCoroutineContext().ensureActive()
    }

    suspend fun transcribe(wav: ByteArray, source: Language, prompt: String, vad: File): String =
        coroutineScope {
            val worker = async(Dispatchers.Default) {
                require(wav.size > 44 && (wav.size - 44) % 2 == 0)
                val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN)
                val samples = FloatArray((wav.size - 44) / 2) { pcm.short / 32768f }
                val active = synchronized(lock) { check(handle != 0L); handle }
                val text = nativeTranscribe(active, samples, source.code, prompt.toByteArray(Charsets.UTF_8), vad.absolutePath)
                text.toString(Charsets.UTF_8).trim()
            }
            try {
                worker.await()
            } catch (cancelled: CancellationException) {
                cancel()
                throw cancelled
            }
        }

    fun cancel() = synchronized(lock) {
        cancelled = true
        if (handle != 0L) nativeCancel(handle)
    }

    fun close() {
        val opened = synchronized(lock) { handle.also { handle = 0L } }
        if (opened != 0L) nativeClose(opened)
    }

    private external fun nativeOpen(path: String): Long
    private external fun nativeTranscribe(handle: Long, pcm: FloatArray, language: String, prompt: ByteArray, vad: String): ByteArray
    private external fun nativeCancel(handle: Long)
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("vdubbing-whisper") }
    }
}
