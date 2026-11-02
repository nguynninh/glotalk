package vn.onplay.vdubbing.data.audio

import android.util.Base64
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
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

class OpenAiRealtimeTranslationTest {
    @Test fun waitsForConfigurationThenStreamsAndCancels() = runBlocking {
        val fake = FakeSocket()
        var captured = false
        withTimeout(5000) {
            val result = async {
                OpenAiRealtimeTranslation(fake).audio("sk-personal-test", Language.CHINESE, flow {
                    captured = true
                    emit(ByteArray(4800))
                    awaitCancellation()
                }).first()
            }
            fake.configured.await()
            assertFalse(captured)
            fake.event("session.created")
            assertFalse(captured)
            fake.event("session.updated")
            assertArrayEquals(byteArrayOf(1, 0, 2, 0), result.await())
        }
        assertTrue(captured)
        assertTrue(fake.cancelled)
        assertEquals("Bearer sk-personal-test", fake.request.header("Authorization"))
        assertEquals("api.openai.com", fake.request.url.host)
        assertEquals("/v1/realtime/translations", fake.request.url.encodedPath)
        assertEquals("gpt-realtime-translate", fake.request.url.queryParameter("model"))
        assertNull(fake.request.url.queryParameter("key"))
        assertEquals("zh", JSONObject(fake.sent.first()).getJSONObject("session")
            .getJSONObject("audio").getJSONObject("output").getString("language"))
        assertEquals(4800, Base64.decode(JSONObject(fake.sent.last()).getString("audio"), Base64.NO_WRAP).size)
    }

    @Test fun finiteInputDrainsFinalAudioBeforeClosing() = runBlocking {
        val fake = FakeSocket()
        val result = withTimeout(5000) {
            val pending = async {
                OpenAiRealtimeTranslation(fake).audio("sk-test", Language.VIETNAMESE,
                    flow { emit(ByteArray(4800)) }).toList()
            }
            fake.configured.await()
            fake.event("session.updated")
            pending.await()
        }
        assertEquals(2, result.size)
        assertArrayEquals(byteArrayOf(3, 0), result.last())
        assertEquals("session.close", JSONObject(fake.sent.last()).getString("type"))
        assertTrue(fake.cancelled)
    }

    @Test fun cancellationBeforeReadyNeverCapturesAudio() = runBlocking {
        val fake = FakeSocket()
        var captured = false
        withTimeout(5000) {
            val pending = async {
                OpenAiRealtimeTranslation(fake).audio("sk-test", Language.ENGLISH,
                    flow { captured = true; emit(ByteArray(4800)) }).first()
            }
            fake.configured.await()
            pending.cancel()
            pending.join()
        }
        assertFalse(captured)
        assertTrue(fake.cancelled)
    }

    @Test fun serverErrorsAndBacklogStopTheSocket() = runBlocking {
        for ((output, reason) in listOf(
            """{"type":"error","error":{"code":"invalid_api_key"}}""" to OpenAiRealtimeAudioException.Reason.KEY,
            """{"type":"error","error":{"code":"insufficient_quota"}}""" to OpenAiRealtimeAudioException.Reason.LIMIT,
            """{"type":"error","error":{"code":"rate_limit_exceeded"}}""" to OpenAiRealtimeAudioException.Reason.LIMIT,
            """{"type":"error","error":{"code":"invalid_request"}}""" to OpenAiRealtimeAudioException.Reason.API,
            """{"type":"session.output_audio.delta","delta":"AQ=="}""" to OpenAiRealtimeAudioException.Reason.API,
            "not json" to OpenAiRealtimeAudioException.Reason.API,
        )) assertFailure(FakeSocket(output = output), reason)
        assertFailure(FakeSocket(queued = 128_001), OpenAiRealtimeAudioException.Reason.TOO_SLOW)
        assertFailure(FakeSocket(status = 401), OpenAiRealtimeAudioException.Reason.KEY)
        assertFailure(FakeSocket(status = 429), OpenAiRealtimeAudioException.Reason.LIMIT)
    }

    private suspend fun assertFailure(fake: FakeSocket, expected: OpenAiRealtimeAudioException.Reason) {
        withTimeout(5000) {
            supervisorScope {
                val pending = async {
                    OpenAiRealtimeTranslation(fake).audio("sk-test", Language.VIETNAMESE,
                        flow { emit(ByteArray(4800)); awaitCancellation() }).first()
                }
                if (fake.status == null) {
                    fake.configured.await()
                    fake.event("session.updated")
                }
                try {
                    pending.await()
                    fail("Expected failure")
                } catch (error: OpenAiRealtimeAudioException) { assertEquals(expected, error.reason) }
            }
        }
        assertTrue(fake.cancelled)
    }

    private class FakeSocket(
        private val output: String = """{"type":"session.output_audio.delta","delta":"AQACAA=="}""",
        private val queued: Long = 0,
        val status: Int? = null,
    ) : WebSocket.Factory, WebSocket {
        lateinit var request: Request
        private lateinit var listener: WebSocketListener
        val configured = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        var cancelled = false
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status ?: 101).message("test").build()
            if (status != null) listener.onFailure(this, IOException("test"), response)
            else listener.onOpen(this, response)
            return this
        }
        fun event(type: String) = listener.onMessage(this, JSONObject().put("type", type).toString())
        override fun request() = request
        override fun queueSize() = queued
        override fun send(text: String): Boolean {
            sent += text
            when (JSONObject(text).getString("type")) {
                "session.update" -> configured.complete(Unit)
                "session.input_audio_buffer.append" -> listener.onMessage(this, output)
                "session.close" -> {
                    listener.onMessage(this, """{"type":"session.output_audio.delta","delta":"AwA="}""")
                    event("session.closed")
                }
            }
            return true
        }
        override fun send(bytes: ByteString) = error("Unexpected binary frame")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
}
