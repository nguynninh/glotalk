package vn.onplay.vdubbing.data.audio

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.domain.model.Language

@RunWith(AndroidJUnit4::class)
class GeminiLiveTranslationTest {
    @Test fun smallAudioPacketsCanArriveInBurstsWhilePlaybackIsBusy() = runBlocking {
        val fake = FakeSocket()
        val playing = CompletableDeferred<Unit>()
        val resumePlayback = CompletableDeferred<Unit>()
        val received = mutableListOf<ByteArray>()
        withTimeout(5000) {
            val pending = async {
                GeminiLiveTranslation(fake).audio("AIza-test", Language.VIETNAMESE,
                    flow { emit(ByteArray(3200)); awaitCancellation() })
                    .take(41).collect { pcm ->
                        received += pcm
                        if (received.size == 1) {
                            playing.complete(Unit)
                            resumePlayback.await()
                        }
                    }
            }
            fake.configured.await()
            fake.event("""{"setupComplete":{}}""")
            playing.await()
            repeat(40) { index ->
                val pcm = ByteArray(960) { index.toByte() }
                fake.event(audioEvent(pcm))
            }
            resumePlayback.complete(Unit)
            pending.await()
        }
        assertEquals(41, received.size)
        repeat(40) { index -> assertArrayEquals(ByteArray(960) { index.toByte() }, received[index + 1]) }
        assertTrue(fake.cancelled)
    }

    @Test fun excessiveQueuedAudioStillStopsTheSession() = runBlocking {
        val fake = FakeSocket()
        withTimeout(5000) {
            supervisorScope {
                val playing = CompletableDeferred<Unit>()
                val resumePlayback = CompletableDeferred<Unit>()
                val pending = async {
                    GeminiLiveTranslation(fake).audio("AIza-test", Language.VIETNAMESE,
                        flow { emit(ByteArray(3200)); awaitCancellation() }).collect {
                        playing.complete(Unit)
                        resumePlayback.await()
                    }
                }
                fake.configured.await()
                fake.event("""{"setupComplete":{}}""")
                playing.await()
                val event = audioEvent(ByteArray(48000))
                repeat(5) { fake.event(event) }
                resumePlayback.complete(Unit)
                try {
                    pending.await()
                    fail("Expected backlog failure")
                } catch (error: GeminiLiveAudioException) {
                    assertEquals(GeminiLiveAudioException.Reason.TOO_SLOW, error.reason)
                }
            }
        }
        assertTrue(fake.cancelled)
    }

    @Test fun streamsWithoutWaitingForUtteranceAndCancelsSocket() = runBlocking {
        val fake = FakeSocket()
        var captured = false
        val input = flow {
            captured = true
            emit(ByteArray(3200))
            awaitCancellation()
        }
        withTimeout(5000) {
            supervisorScope {
                val result = async { GeminiLiveTranslation(fake).audio("AIza-test", Language.VIETNAMESE, input).first() }
                fake.configured.await()
                assertFalse(captured)
                fake.event("""{"setupComplete":{}}""")
                assertArrayEquals(byteArrayOf(1, 0, 2, 0), result.await())
            }
        }
        assertTrue(captured)
        assertTrue(fake.cancelled)
        assertNull(fake.request.header("Authorization"))
        assertEquals("generativelanguage.googleapis.com", fake.request.url.host)
        assertEquals("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent", fake.request.url.encodedPath)
        assertEquals("AIza-test", fake.request.url.queryParameter("key"))
        val setup = JSONObject(fake.sent.first()).getJSONObject("setup")
        assertEquals("models/gemini-3.5-live-translate-preview", setup.getString("model"))
        val config = setup.getJSONObject("generationConfig")
        assertEquals("AUDIO", config.getJSONArray("responseModalities").getString(0))
        assertEquals("vi", config.getJSONObject("translationConfig").getString("targetLanguageCode"))
        assertFalse(config.getJSONObject("translationConfig").getBoolean("echoTargetLanguage"))
        val audio = JSONObject(fake.sent.last()).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertEquals(3200, Base64.decode(audio.getString("data"), Base64.NO_WRAP).size)
    }

    @Test fun errorAndBacklogStopInsteadOfRetryingOrGrowingQueues() = runBlocking {
        for ((event, reason) in listOf(
            """{"error":{"status":"UNAUTHENTICATED","code":400}}""" to GeminiLiveAudioException.Reason.KEY,
            """{"error":{"status":"INVALID_ARGUMENT","code":400,"details":[{"reason":"API_KEY_INVALID"}]}}""" to GeminiLiveAudioException.Reason.KEY,
            """{"error":{"status":"RESOURCE_EXHAUSTED","code":400}}""" to GeminiLiveAudioException.Reason.LIMIT,
            """{"error":{"status":"INVALID_ARGUMENT","code":400}}""" to GeminiLiveAudioException.Reason.API,
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=16000","data":"AQACAA=="}}]}}}""" to GeminiLiveAudioException.Reason.API,
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQ=="}}]}}}""" to GeminiLiveAudioException.Reason.API,
            """{"goAway":{"timeLeft":"30s"}}""" to GeminiLiveAudioException.Reason.NETWORK,
        )) {
            val fake = FakeSocket(event)
            assertFailure(fake, reason)
            assertEquals(1, fake.sent.count { JSONObject(it).has("realtimeInput") })
        }
        val backlog = FakeSocket(queued = 86_001)
        assertFailure(backlog, GeminiLiveAudioException.Reason.TOO_SLOW)
        assertEquals(1, backlog.sent.size)
    }

    @Test fun cancellationBeforeSessionIsReadyNeverStartsCapture() = runBlocking {
        val fake = FakeSocket()
        var captured = false
        val pending = async {
            GeminiLiveTranslation(fake).audio("AIza-test", Language.VIETNAMESE,
                flow { captured = true; emit(ByteArray(3200)) }).first()
        }
        withTimeout(5000) { fake.configured.await() }
        pending.cancel()
        pending.join()
        assertFalse(captured)
        assertTrue(fake.cancelled)
    }

    @Test fun handshakeAuthenticationFailureIsNotReportedAsCaptureFailure() = runBlocking {
        val fake = FakeSocket(status = 401)
        assertFailure(fake, GeminiLiveAudioException.Reason.KEY)
    }

    @Test fun handshakeQuotaAndUnknown429ReportLimit() = runBlocking {
        for (code in listOf("RESOURCE_EXHAUSTED", "")) {
            assertFailure(FakeSocket(status = 429, errorCode = code), GeminiLiveAudioException.Reason.LIMIT)
        }
    }

    @Test fun binaryMessagesPreserveAllAudioPartsAndChineseTarget() = runBlocking {
        val fake = FakeSocket(output = """{"serverContent":{"modelTurn":{"parts":[{"text":"ignored"},{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQACAA=="}},{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AwAEAA=="}}]}}}""", binary = true)
        val received = mutableListOf<ByteArray>()
        withTimeout(5000) {
            val pending = async {
                GeminiLiveTranslation(fake).audio("AIza-test", Language.CHINESE,
                    flow { emit(ByteArray(3200)); awaitCancellation() })
                    .take(2).collect { received += it }
            }
            fake.configured.await()
            fake.event("""{"setupComplete":{}}""")
            pending.await()
        }
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), received[0])
        assertArrayEquals(byteArrayOf(3, 0, 4, 0), received[1])
        assertEquals("zh-Hans", JSONObject(fake.sent.first()).getJSONObject("setup")
            .getJSONObject("generationConfig").getJSONObject("translationConfig").getString("targetLanguageCode"))
        assertTrue(fake.cancelled)
    }

    @Test fun rejectedSetupDoesNotStartCapture() = runBlocking {
        for ((code, reason, expected) in listOf(
            Triple(1008, "API key not valid", GeminiLiveAudioException.Reason.KEY),
            Triple(1008, "RESOURCE_EXHAUSTED", GeminiLiveAudioException.Reason.LIMIT),
            Triple(1008, "Invalid model", GeminiLiveAudioException.Reason.API),
        )) {
            val fake = FakeSocket()
            var captured = false
            withTimeout(5000) {
                supervisorScope {
                    val pending = async {
                        GeminiLiveTranslation(fake).audio("AIza-test", Language.VIETNAMESE,
                            flow { captured = true; emit(ByteArray(3200)) }).first()
                    }
                    fake.configured.await()
                    fake.closing(code, reason)
                    try {
                        pending.await()
                        fail("Expected setup failure")
                    } catch (error: GeminiLiveAudioException) { assertEquals(expected, error.reason) }
                }
            }
            assertFalse(captured)
            assertTrue(fake.cancelled)
        }
    }

    @Test fun missingSessionAcknowledgementTimesOutWithoutUploading() = runBlocking {
        val fake = FakeSocket()
        var captured = false
        try {
            withTimeout(18000) {
                GeminiLiveTranslation(fake).audio("AIza-test", Language.VIETNAMESE,
                    flow { captured = true; emit(ByteArray(3200)) }).first()
            }
            fail("Expected connection timeout")
        } catch (error: GeminiLiveAudioException) {
            assertEquals(GeminiLiveAudioException.Reason.NETWORK, error.reason)
        }
        assertFalse(captured)
        assertTrue(fake.cancelled)
    }

    private suspend fun assertFailure(fake: FakeSocket, expected: GeminiLiveAudioException.Reason) {
        withTimeout(5000) {
            supervisorScope {
                val pending = async {
                    GeminiLiveTranslation(fake).audio("AIza-test", Language.VIETNAMESE,
                        flow { emit(ByteArray(3200)); awaitCancellation() }).first()
                }
                if (fake.status == null) {
                    fake.configured.await()
                    fake.event("""{"setupComplete":{}}""")
                }
                try {
                    pending.await()
                    fail("Expected failure")
                } catch (error: GeminiLiveAudioException) { assertEquals(expected, error.reason) }
            }
        }
        assertTrue(fake.cancelled)
    }

    private fun audioEvent(pcm: ByteArray): String = JSONObject().put("serverContent", JSONObject()
        .put("modelTurn", JSONObject().put("parts", org.json.JSONArray().put(JSONObject()
            .put("inlineData", JSONObject().put("mimeType", "audio/pcm;rate=24000")
                .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))))))).toString()

    private class FakeSocket(
        private val output: String = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQACAA=="}}]}}}""",
        private val queued: Long = 0,
        val status: Int? = null,
        val errorCode: String = "",
        private val binary: Boolean = false,
    ) : WebSocket.Factory, WebSocket {
        lateinit var request: Request
        private lateinit var listener: WebSocketListener
        val configured = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        var cancelled = false
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status ?: 101).message("test")
                .body(JSONObject().put("error", JSONObject().put("status", errorCode)).toString().toResponseBody()).build()
            if (status != null) listener.onFailure(this, IOException("test"), response)
            else listener.onOpen(this, response)
            return this
        }
        fun event(text: String) {
            if (binary) listener.onMessage(this, text.encodeUtf8())
            else listener.onMessage(this, text)
        }
        fun closing(code: Int, reason: String) = listener.onClosing(this, code, reason)
        override fun request() = request
        override fun queueSize() = queued
        override fun send(text: String): Boolean {
            sent += text
            when {
                JSONObject(text).has("setup") -> configured.complete(Unit)
                JSONObject(text).optJSONObject("realtimeInput")?.has("audio") == true -> event(output)
            }
            return true
        }
        override fun send(bytes: ByteString) = error("Unexpected binary frame")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
}
