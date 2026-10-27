package vn.onplay.vdubbing.framework.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock

class TranslationVisibilityTest {
    private fun block(original: String) = TextBlock(original, "译文", Language.ENGLISH, 0, 0, 100, 40)

    @Test fun `addresses and numeric controls stay untouched`() {
        assertFalse(shouldDrawTranslation(block("% truyenqqko.com/1 +")))
        assertFalse(shouldDrawTranslation(block("https://example.org/path")))
        assertFalse(shouldDrawTranslation(block("(4")))
        assertFalse(shouldDrawTranslation(block("11/12/2022")))
    }

    @Test fun `uncertain icon fragments stay untouched without discarding real short words`() {
        assertFalse(shouldDrawTranslation(block("HH").copy(confidence = 0.6)))
        assertTrue(shouldDrawTranslation(block("OK").copy(confidence = 0.9)))
        assertTrue(shouldDrawTranslation(block("你好").copy(confidence = 0.6)))
    }

    @Test fun `normal translations and cloud blocks without source remain visible`() {
        assertTrue(shouldDrawTranslation(block("Tác giả")))
        assertTrue(shouldDrawTranslation(block("")))
        assertFalse(shouldDrawTranslation(block("same").copy(translated = "same")))
    }
}
