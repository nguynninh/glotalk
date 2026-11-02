package vn.onplay.vdubbing.data.audio

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language

class OnPlaySegmentTranslationTest {
    @Test fun waitsForReadyUploadsPcmAndDrainsSegmentsThroughDone() = runBlocking {
        val socket = Socket()
        var captured = false
        val result = withTimeout(5000) {
            val pending = async {
                OnPlaySegmentTranslation(socket).audio(Language.ENGLISH, Language.VIETNAMESE, flow {
                    captured = true
                    emit(ByteArray(3200))
                }, {}).toList()
            }
            socket.opened.await()
            assertFalse(captured)
            socket.event("ready")
            pending.await()
        }
        assertEquals(2, result.size)
        result.forEach { assertArrayEquals(socket.wav, it) }
        assertTrue(socket.cancelled)
        assertEquals(3200, socket.frames.single().size)
        assertEquals("https://stt.qiverse.top", socket.request.header("Origin"))
        assertEquals("/ws", socket.request.url.encodedPath)
        val config = JSONObject(socket.sent.first())
        assertTrue(config.getBoolean("speech"))
        assertFalse(config.getBoolean("speakers"))
        assertEquals(100, config.getInt("chunk"))
        assertEquals("en", config.getString("source_language"))
        assertEquals("vi", config.getString("target_language"))
        assertEquals("end", JSONObject(socket.sent.last()).getString("type"))
    }

    @Test fun speechFailurePreservesDeliveredAudioAndContinuesUntilDone() = runBlocking {
        val socket = Socket(speechFailure = true)
        var errors = 0
        val result = withTimeout(5000) {
            val pending = async {
                OnPlaySegmentTranslation(socket).audio(Language.AUTO, Language.VIETNAMESE,
                    flow { emit(ByteArray(3200)) }, { errors++ }).toList()
            }
            socket.opened.await()
            socket.event("ready")
            pending.await()
        }
        assertEquals(1, result.size)
        assertEquals(1, errors)
        assertFalse(JSONObject(socket.sent.first()).has("source_language"))
        assertTrue(socket.cancelled)
    }

    @Test fun cancellationDisconnectsAndStopsCapture() = runBlocking {
        val socket = Socket()
        var stopped = false
        withTimeout(5000) {
            val pending = async {
                OnPlaySegmentTranslation(socket).audio(Language.AUTO, Language.VIETNAMESE, flow {
                    try { emit(ByteArray(3200)); awaitCancellation() } finally { stopped = true }
                }, {}).first()
            }
            socket.opened.await()
            socket.event("ready")
            pending.await()
        }
        assertTrue(stopped)
        assertTrue(socket.cancelled)
    }

    @Test fun uploadOverflowStopsInsteadOfDroppingFrames() = runBlocking {
        val socket = Socket(queuedBytes = 64000)
        withTimeout(5000) {
            val pending = async {
                runCatching {
                    OnPlaySegmentTranslation(socket).audio(Language.AUTO, Language.VIETNAMESE,
                        flow { emit(ByteArray(3200)) }, {}).toList()
                }
            }
            socket.opened.await()
            socket.event("ready")
            assertTrue(pending.await().exceptionOrNull() is OnPlayBacklogException)
        }
        assertTrue(socket.cancelled)
        assertTrue(socket.frames.isEmpty())
    }

    private class Socket(
        private val speechFailure: Boolean = false,
        private val queuedBytes: Long = 0,
    ) : WebSocket.Factory, WebSocket {
        lateinit var request: Request
        lateinit var listener: WebSocketListener
        val opened = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        var cancelled = false
        val wav = ByteArray(46).apply {
            "RIFF".toByteArray().copyInto(this)
            "WAVE".toByteArray().copyInto(this, 8)
        }
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("test").build())
            opened.complete(Unit)
            return this
        }
        fun event(type: String) = listener.onMessage(this, JSONObject().put("type", type).toString())
        fun audio(id: Int) = listener.onMessage(this, JSONObject().put("type", "audio").put("id", id)
            .put("revision", "live").put("language", "vi").put("content_type", "audio/wav")
            .put("encoding", "base64").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).toString())
        override fun send(text: String): Boolean {
            sent += text
            if (JSONObject(text).optString("type") == "end") {
                event("translation")
                if (speechFailure) event("audio_error")
                audio(1)
                event("done")
            }
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            frames += bytes.toByteArray()
            event("partial")
            event("final")
            audio(0)
            return true
        }
        override fun request() = request
        override fun queueSize() = queuedBytes
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
}
