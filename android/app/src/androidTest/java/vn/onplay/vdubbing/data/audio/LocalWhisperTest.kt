package vn.onplay.vdubbing.data.audio

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.domain.model.Language

@RunWith(AndroidJUnit4::class)
class LocalWhisperTest {
    @Test fun recognizesKnownSpeechRejectsSilenceAndCancelsNativeWork() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val (model, vad) = WhisperModelStore(instrumentation.targetContext).prepare()
        val whisper = LocalWhisper()
        try {
            whisper.initialize(model)
            val wav = instrumentation.context.assets.open("whisper-jfk.wav").use { it.readBytes() }
            val began = SystemClock.elapsedRealtime()
            val text = withTimeout(90000) { whisper.transcribe(wav, Language.ENGLISH, "", vad) }
            val elapsed = SystemClock.elapsedRealtime() - began
            Log.i("WhisperBenchmark", "audio_ms=${(wav.size - 44) / 32} inference_ms=$elapsed")
            assertTrue(text, text.contains("country", ignoreCase = true))
            assertTrue(text, text.contains("ask", ignoreCase = true))
            val vietnamese = instrumentation.context.assets.open("whisper-vietnamese.wav").use { it.readBytes() }
            val viBegan = SystemClock.elapsedRealtime()
            val vi = withTimeout(90000) { whisper.transcribe(vietnamese, Language.VIETNAMESE, "", vad) }
            Log.i("WhisperBenchmark", "vi_audio_ms=${(vietnamese.size - 44) / 32} inference_ms=${SystemClock.elapsedRealtime() - viBegan} transcript=$vi")
            assertTrue(vi, vi.contains("Hà Nội", ignoreCase = true))
            assertTrue(vi, vi.contains("15") || vi.contains("mười lăm", ignoreCase = true))
            assertTrue(vi, vi.contains("đừng", ignoreCase = true) || vi.contains("không", ignoreCase = true))
            assertEquals("", whisper.transcribe(ByteArray(44 + 16000 * 2 * 3), Language.AUTO, "", vad))
            val pending = launch { whisper.transcribe(wav, Language.ENGLISH, "", vad) }
            yield()
            withTimeout(5000) { pending.cancelAndJoin() }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) { whisper.close() }
        }
    }
}
