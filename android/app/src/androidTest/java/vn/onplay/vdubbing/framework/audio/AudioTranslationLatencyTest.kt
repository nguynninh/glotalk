package vn.onplay.vdubbing.framework.audio

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.data.audio.LocalWhisper
import vn.onplay.vdubbing.data.audio.WhisperAudioTranslationRepository
import vn.onplay.vdubbing.data.audio.WhisperModelStore
import vn.onplay.vdubbing.data.mlkit.TranslationRepositoryImpl
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.usecase.TranslateAudioSession

@RunWith(AndroidJUnit4::class)
class AudioTranslationLatencyTest {
    @Test fun cachedChineseTranslationAndThreeSecondAudio(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val translation = TranslationRepositoryImpl()
        val whisper = LocalWhisper()
        val speaker = withContext(Dispatchers.Main) { AudioTranslationSpeaker(context) }
        try {
            val preparing = SystemClock.elapsedRealtime()
            withTimeout(60000) { translation.prepare(Language.CHINESE, Language.VIETNAMESE) }
            Log.i("AudioLatency", "zh_model_prepare_ms=${SystemClock.elapsedRealtime() - preparing}")
            repeat(2) { index ->
                val began = SystemClock.elapsedRealtime()
                val result = withTimeout(10000) {
                    translation.translate(listOf("你好，欢迎来到我们的频道。"), Language.CHINESE, Language.VIETNAMESE).single()
                }
                assertTrue(result.isNotBlank())
                assertNotEquals("你好，欢迎来到我们的频道。", result)
                Log.i("AudioLatency", "zh_run=$index translate_ms=${SystemClock.elapsedRealtime() - began}")
            }
            val (model, vad) = WhisperModelStore(context).prepare()
            assertEquals("ggml-base-q5_1.bin", model.name)
            whisper.initialize(model)
            translation.prepare(Language.ENGLISH, Language.VIETNAMESE)
            withContext(Dispatchers.Main) { speaker.initialize(Language.VIETNAMESE) }
            val full = instrumentation.context.assets.open("whisper-jfk.wav").use { it.readBytes() }
            val clip = full.copyOf(44 + 3 * 16000 * 2)
            ByteBuffer.wrap(clip).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(4, clip.size - 8)
                putInt(40, clip.size - 44)
            }
            val repository = WhisperAudioTranslationRepository(
                { wav, source, prompt -> whisper.transcribe(wav, source, prompt, vad) }, translation,
            )
            val session = TranslateAudioSession(repository, Language.AUTO, Language.VIETNAMESE)
            val began = SystemClock.elapsedRealtime()
            var translated = false
            withTimeout(30000) {
                playOfflineAudio(flowOf(clip), translate = {
                    session.translate(it).also { output ->
                        assertNotNull(output)
                        assertTrue(output!!.original.isNotBlank())
                        assertNotEquals(output.original, output.translated)
                        translated = true
                    }
                }, speak = { speaker.speak(it) }, onStage = {})
            }
            assertTrue(translated)
            Log.i("AudioLatency", "three_second_clip_completed_ms=${SystemClock.elapsedRealtime() - began}")
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { speaker.close() }
            translation.close()
            withContext(NonCancellable + Dispatchers.Default) { whisper.close() }
        }
    }
}
