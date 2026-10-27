package vn.onplay.vdubbing.framework.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock

class VerticalBandsTest {

    private val screenHeight = 1000

    private fun block(top: Int, height: Int, left: Int = 0, width: Int = 800) = TextBlock(
        original = "original",
        translated = "translated",
        language = Language.ENGLISH,
        left = left,
        top = top,
        width = width,
        height = height,
    )

    private fun bands(blocks: List<TextBlock>) =
        VerticalBands.of(blocks, drawn = { true }, screenHeight = screenHeight)

    @Test
    fun `neighbouring blocks split the gap between them`() {
        val blocks = listOf(block(top = 100, height = 40), block(top = 200, height = 40))
        val result = bands(blocks)

        assertEquals(0, result[0].top)
        assertEquals(170, result[0].bottom)
        assertEquals(170, result[1].top)
        assertEquals(screenHeight, result[1].bottom)
    }

    @Test
    fun `blocks in separate columns do not constrain each other`() {
        val left = block(top = 100, height = 40, left = 0, width = 400)
        val right = block(top = 100, height = 40, left = 500, width = 400)
        val below = block(top = 300, height = 40, left = 0, width = 400)

        val result = bands(listOf(left, right, below))

        // The right column has nothing below it, so it may use the whole screen.
        assertEquals(0, result[1].top)
        assertEquals(screenHeight, result[1].bottom)
    }

    @Test
    fun `overlapping OCR boxes cannot grow into each other`() {
        val upper = block(top = 100, height = 60)
        val lower = block(top = 140, height = 60)

        val result = bands(listOf(upper, lower))

        assertTrue(result[0].bottom <= upper.top + upper.height)
        assertTrue(result[1].top >= lower.top)
    }

    @Test
    fun `a block that is not drawn is skipped when finding neighbours`() {
        val upper = block(top = 100, height = 40)
        val middle = block(top = 200, height = 40)
        val lower = block(top = 300, height = 40)

        val result = VerticalBands.of(
            listOf(upper, middle, lower),
            drawn = { it !== middle },
            screenHeight = screenHeight,
        )

        assertEquals(Band(0, 0), result[1])
        // The two drawn blocks share the whole gap the skipped one left.
        assertEquals(220, result[0].bottom)
        assertEquals(220, result[2].top)
    }

    @Test
    fun `bands never invert on a screen shorter than the blocks`() {
        val blocks = listOf(block(top = 0, height = 900), block(top = 950, height = 200))
        val result = bands(blocks)

        result.forEach { assertTrue(it.bottom >= it.top) }
    }
}
