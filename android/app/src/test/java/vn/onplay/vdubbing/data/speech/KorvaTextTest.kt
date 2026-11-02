package vn.onplay.vdubbing.data.speech

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KorvaTextTest {
    @Test fun vietnameseDiacriticsMatchDecomposedVocabulary() {
        val expected = Normalizer.normalize("<vi>Tiếng Việt.</vi>", Normalizer.Form.NFKD)
        assertEquals(expected, KorvaText.normalize("Tiếng Việt"))
        assertEquals(expected, KorvaText.normalize(Normalizer.normalize("Tiếng Việt", Normalizer.Form.NFD)))
    }

    @Test fun stripsEmojiAndNormalizesPunctuationLikeUpstream() {
        assertEquals("<vi>Xin chao - \"team\" at Glotalk!</vi>",
            KorvaText.normalize("  Xin chao 😀 — “team” @ Glotalk ! "))
        assertEquals("<vi>for example, that is, test.</vi>", KorvaText.normalize("e.g., i.e., test"))
    }

    @Test fun chunksPreserveLongTextAndBoundModelInput() {
        val text = "Đây là câu thử nghiệm. ".repeat(100).trim()
        val chunks = KorvaText.chunks(text)
        assertTrue(chunks.all { it.isNotBlank() && it.length <= 180 })
        assertEquals(text, chunks.joinToString(" "))
        assertEquals(listOf("abcde", "fghij", "k"), KorvaText.chunks("abcdefghijk", 5))
        assertTrue(KorvaText.chunks("  \n ").isEmpty())
    }

    @Test fun chunkBoundariesDoNotSplitSurrogatePairs() {
        val chunks = KorvaText.chunks("abcd😀efgh😀", 5)
        assertEquals("abcd😀efgh😀", chunks.joinToString(""))
        chunks.forEach { assertFalse(Character.isHighSurrogate(it.last())) }
    }
}
