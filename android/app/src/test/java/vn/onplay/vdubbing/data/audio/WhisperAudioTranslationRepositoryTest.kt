package vn.onplay.vdubbing.data.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import vn.onplay.vdubbing.domain.model.AudioTranslation
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.TranslationRepository

class WhisperAudioTranslationRepositoryTest {
    private val calls = mutableListOf<Triple<List<String>, Language, Language>>()
    private var detections = 0
    private var detected = Language.ENGLISH
    private var output = listOf("Đừng mua 15 vé.")
    private val translator = object : TranslationRepository {
        override suspend fun detect(text: String): Language { detections++; return detected }
        override suspend fun translate(lines: List<String>, source: Language, target: Language): List<String> {
            calls.add(Triple(lines, source, target))
            return output
        }
    }

    @Test fun `local speech is translated with the existing on device translator without credentials`() = runTest {
        val repository = WhisperAudioTranslationRepository({ _, source, prompt ->
            assertEquals(Language.AUTO, source)
            assertEquals("Previous sentence.", prompt)
            "Do not buy 15 tickets."
        }, translator)
        val result = repository.translate(byteArrayOf(), Language.AUTO, Language.VIETNAMESE,
            listOf(AudioTranslation("Previous sentence.", "Câu trước.")))
        assertEquals(AudioTranslation("Do not buy 15 tickets.", "Đừng mua 15 vé."), result)
        assertEquals(1, detections)
        assertEquals(Triple(listOf("Do not buy 15 tickets."), Language.ENGLISH, Language.VIETNAMESE), calls.single())
    }

    @Test fun `explicit source bypasses detection and bounds the recognition prompt`() = runTest {
        val repository = WhisperAudioTranslationRepository({ _, _, prompt ->
            assertEquals(600, prompt.length)
            "Do not buy 15 tickets."
        }, translator)
        repository.translate(byteArrayOf(), Language.ENGLISH, Language.VIETNAMESE,
            listOf(AudioTranslation("x".repeat(1000), "unused translation")))
        assertEquals(0, detections)
        assertEquals(Language.ENGLISH, calls.single().second)
    }

    @Test fun `silence does not trigger translation or model downloads`() = runTest {
        val repository = WhisperAudioTranslationRepository({ _, _, _ -> " " }, translator)
        assertNull(repository.translate(byteArrayOf(), Language.AUTO, Language.VIETNAMESE, emptyList()))
        assertEquals(0, detections)
        assertTrue(calls.isEmpty())
    }

    @Test fun `same source and target reads original speech without a translation model`() = runTest {
        val repository = WhisperAudioTranslationRepository({ _, _, _ -> "Xin chào" }, translator)
        assertEquals(AudioTranslation("Xin chào", "Xin chào"),
            repository.translate(byteArrayOf(), Language.VIETNAMESE, Language.VIETNAMESE, emptyList()))
        assertTrue(calls.isEmpty())
    }

    @Test fun `unknown source and empty translations fail instead of reading an untranslated result`() = runTest {
        val repository = WhisperAudioTranslationRepository({ _, _, _ -> "unclear" }, translator)
        detected = Language.UNKNOWN
        try {
            repository.translate(byteArrayOf(), Language.AUTO, Language.VIETNAMESE, emptyList())
            fail("must request an explicit source language")
        } catch (_: TranslationException.TranslationFailed) { }
        assertTrue(calls.isEmpty())
        output = emptyList()
        try {
            repository.translate(byteArrayOf(), Language.ENGLISH, Language.VIETNAMESE, emptyList())
            fail("must reject missing translation")
        } catch (_: TranslationException.TranslationFailed) { }
    }

    @Test fun `short ambiguous speech uses recent source context instead of stopping playback`() = runTest {
        val translation = object : TranslationRepository {
            override suspend fun detect(text: String) =
                if (text.contains("你好")) Language.CHINESE else Language.UNKNOWN
            override suspend fun translate(lines: List<String>, source: Language, target: Language): List<String> {
                assertEquals(listOf("嗯"), lines)
                assertEquals(Language.CHINESE, source)
                return listOf("Ừ")
            }
        }
        val repository = WhisperAudioTranslationRepository({ _, _, _ -> "嗯" }, translation)
        assertEquals(AudioTranslation("嗯", "Ừ"), repository.translate(byteArrayOf(), Language.AUTO,
            Language.VIETNAMESE, listOf(AudioTranslation("你好", "Xin chào"))))
    }

    @Test fun `cancelled recognition cannot start translation after returning a late result`() = runTest {
        val began = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repository = WhisperAudioTranslationRepository({ _, _, _ ->
            began.complete(Unit)
            withContext(NonCancellable) { finish.await() }
            "late speech"
        }, translator)
        val work = launch {
            try {
                repository.translate(byteArrayOf(), Language.AUTO, Language.VIETNAMESE, emptyList())
                fail("must propagate cancellation")
            } catch (_: CancellationException) { }
        }
        began.await()
        work.cancel()
        finish.complete(Unit)
        work.join()
        assertEquals(0, detections)
        assertTrue(calls.isEmpty())
    }
}
