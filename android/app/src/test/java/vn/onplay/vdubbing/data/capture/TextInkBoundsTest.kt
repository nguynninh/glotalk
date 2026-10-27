package vn.onplay.vdubbing.data.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock

class TextInkBoundsTest {
    @Test fun `extends through clipped stem and stops before adjacent icon and border`() {
        val block = TextBlock("Text", "Dịch", Language.ENGLISH, 25, 20, 50, 20)
        val refined = refineTextInkBounds(block, 100, 60) { x, y ->
            if ((x in 20..30 && y in 20..39) || (x in 5..12 && y in 18..42) || y == 10 || y == 50) {
                0xFF000000.toInt()
            } else 0xFFFFFFFF.toInt()
        }
        assertEquals(20, refined.left)
        assertEquals(20, refined.top)
        assertEquals(75, refined.left + refined.width)
        assertEquals(40, refined.top + refined.height)
    }

    @Test fun `dark background supports light glyphs at screen edge`() {
        val block = TextBlock("Text", "Dịch", Language.ENGLISH, 2, 2, 20, 10, backgroundColor = 0xFF000000.toInt())
        val refined = refineTextInkBounds(block, 30, 20) { x, y ->
            check(x in 0 until 30 && y in 0 until 20)
            if (x in 0..5 && y in 0..11) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        assertEquals(0, refined.left)
        assertEquals(0, refined.top)
    }
}
