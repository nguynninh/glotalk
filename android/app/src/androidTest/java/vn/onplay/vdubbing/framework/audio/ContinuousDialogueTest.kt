package vn.onplay.vdubbing.framework.audio

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.data.audio.LocalWhisper
import vn.onplay.vdubbing.data.audio.PcmSpeechChunker
import vn.onplay.vdubbing.data.audio.WhisperAudioTranslationRepository
import vn.onplay.vdubbing.data.audio.WhisperModelStore
import vn.onplay.vdubbing.data.mlkit.TranslationRepositoryImpl
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.usecase.TranslateAudioSession

@RunWith(AndroidJUnit4::class)
class ContinuousDialogueTest {
    @Test fun continuousSpeechPreservesEveryCapturedSegment(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val whisper = LocalWhisper()
        val translation = TranslationRepositoryImpl()
        val speaker = withContext(Dispatchers.Main) { AudioTranslationSpeaker(context) }
        try {
            val (model, vad) = WhisperModelStore(context).prepare()
            whisper.initialize(model)
            translation.prepare(Language.ENGLISH, Language.VIETNAMESE)
            withContext(Dispatchers.Main) { speaker.initialize(Language.VIETNAMESE) }
            val wav = instrumentation.context.assets.open("whisper-jfk.wav").use { it.readBytes() }
            val pcm = wav.copyOfRange(44, wav.size)
            val chunker = PcmSpeechChunker()
            val packets = mutableListOf<Pair<Long, ByteArray>>()
            var sourceMillis = 0L
            repeat(2) {
                for (offset in pcm.indices step PcmSpeechChunker.FRAME_BYTES) {
                    sourceMillis += 20
                    chunker.accept(pcm.copyOfRange(offset, minOf(offset + PcmSpeechChunker.FRAME_BYTES, pcm.size))
                        .copyOf(PcmSpeechChunker.FRAME_BYTES))?.let { packets += sourceMillis to it }
                }
                repeat(30) {
                    sourceMillis += 20
                    chunker.accept(ByteArray(PcmSpeechChunker.FRAME_BYTES))?.let { packets += sourceMillis to it }
                }
            }
            assertEquals(4, packets.size)
            val repository = WhisperAudioTranslationRepository(
                { audio, source, prompt -> whisper.transcribe(audio, source, prompt, vad) }, translation,
            )
            val session = TranslateAudioSession(repository, Language.AUTO, Language.VIETNAMESE)
            val originals = mutableListOf<String>()
            val spoken = mutableListOf<String>()
            val began = SystemClock.elapsedRealtime()
            withTimeout(90000) {
                playOfflineAudio(flow {
                    for ((readyAt, packet) in packets) {
                        val remaining = readyAt - (SystemClock.elapsedRealtime() - began)
                        if (remaining > 0) delay(remaining)
                        emit(packet)
                    }
                }, translate = {
                    session.translate(it).also { result ->
                        assertNotNull("Speech segment must not disappear", result)
                        originals += result!!.original
                    }
                }, speak = {
                    speaker.speak(it)
                    spoken += it
                }, onStage = {}, speakStream = { requests ->
                    speaker.speakRequests(requests.map { request ->
                        request.copy(onCompleted = {
                            request.onCompleted()
                            spoken += request.text
                        })
                    })
                })
            }
            assertEquals(packets.size, originals.size)
            assertEquals(packets.size, spoken.size)
            repeat(2) { index ->
                val original = originals.subList(index * 2, index * 2 + 2).joinToString(" ")
                assertTrue(original, original.contains("country", ignoreCase = true))
                assertTrue(original, original.contains("ask", ignoreCase = true))
            }
            assertTrue(spoken.all { it.isNotBlank() })
            Log.i("DialogueTest", "source_ms=$sourceMillis captured=${packets.size} recognized=${originals.size} spoken=${spoken.size} completed_ms=${SystemClock.elapsedRealtime() - began}")
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { speaker.close() }
            translation.close()
            withContext(NonCancellable + Dispatchers.Default) { whisper.close() }
        }
    }
}
