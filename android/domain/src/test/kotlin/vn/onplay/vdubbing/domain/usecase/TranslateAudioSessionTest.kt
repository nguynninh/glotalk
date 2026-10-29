package vn.onplay.vdubbing.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.onplay.vdubbing.domain.model.AudioTranslation
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.repository.AudioTranslationRepository

class TranslateAudioSessionTest {
    @Test fun `context is bounded ordered and isolated between listening sessions`() = runTest {
        val contexts = mutableListOf<List<AudioTranslation>>()
        var count = 0
        val repository = object : AudioTranslationRepository {
            override suspend fun translate(wav: ByteArray, source: Language, target: Language, context: List<AudioTranslation>): AudioTranslation {
                assertEquals(Language.AUTO, source)
                assertEquals(Language.VIETNAMESE, target)
                contexts.add(context)
                return AudioTranslation("source ${++count}", "translation $count")
            }
        }
        val session = TranslateAudioSession(repository, Language.AUTO, Language.VIETNAMESE)
        repeat(5) { session.translate(byteArrayOf()) }
        assertEquals(listOf("source 2", "source 3", "source 4"), contexts.last().map { it.original })
        TranslateAudioSession(repository, Language.AUTO, Language.VIETNAMESE).translate(byteArrayOf())
        assertTrue(contexts.last().isEmpty())
    }

    @Test fun `unclear clips are not added to context and cancellation propagates`() = runTest {
        var calls = 0
        val repository = object : AudioTranslationRepository {
            override suspend fun translate(wav: ByteArray, source: Language, target: Language, context: List<AudioTranslation>): AudioTranslation? {
                assertTrue(context.isEmpty())
                if (++calls == 1) return null
                throw CancellationException()
            }
        }
        val session = TranslateAudioSession(repository, Language.AUTO, Language.VIETNAMESE)
        assertNull(session.translate(byteArrayOf()))
        try {
            session.translate(byteArrayOf())
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
    }
}
