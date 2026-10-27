package vn.onplay.vdubbing.framework.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock

class BandsTest {

    private val screenWidth = 1000
    private val screenHeight = 1000

    private fun block(
        top: Int,
        height: Int = 40,
        left: Int = 0,
        width: Int = 800,
    ) = TextBlock(
        original = "original",
        translated = "translated",
        language = Language.ENGLISH,
        left = left,
        top = top,
        width = width,
        height = height,
    )

    private fun rows(blocks: List<TextBlock>) =
        VerticalBands.of(blocks, drawn = { true }, screenHeight = screenHeight)

    private fun columns(blocks: List<TextBlock>) =
        HorizontalBands.of(blocks, drawn = { true }, screenWidth = screenWidth)

    // --- vertical -----------------------------------------------------------

    @Test
    fun `neighbouring blocks split the gap between them`() {
        val result = rows(listOf(block(top = 100), block(top = 200)))

        assertEquals(Band(0, 170), result[0])
        assertEquals(Band(170, screenHeight), result[1])
    }

    @Test
    fun `blocks in separate columns do not constrain each other vertically`() {
        val left = block(top = 100, left = 0, width = 400)
        val right = block(top = 100, left = 500, width = 400)
        val below = block(top = 300, left = 0, width = 400)

        val result = rows(listOf(left, right, below))

        assertEquals(Band(0, screenHeight), result[1])
    }

    @Test
    fun `overlapping OCR boxes cannot grow into each other`() {
        val upper = block(top = 100, height = 60)
        val lower = block(top = 140, height = 60)

        val result = rows(listOf(upper, lower))

        assertTrue(result[0].end <= 160)
        assertTrue(result[1].start >= 140)
    }

    @Test
    fun `a block that is not drawn still holds its space`() {
        val upper = block(top = 100)
        val middle = block(top = 200)
        val lower = block(top = 300)

        val result = VerticalBands.of(
            listOf(upper, middle, lower),
            drawn = { it !== middle },
            screenHeight = screenHeight,
        )

        assertEquals(Band(0, 0), result[1])
        // `middle` keeps its original words on screen, so the drawn neighbours
        // stop halfway to it rather than reaching across it.
        assertEquals(170, result[0].end)
        assertEquals(270, result[2].start)
    }

    @Test
    fun `rows never invert on a screen shorter than the blocks`() {
        val result = rows(listOf(block(top = 0, height = 900), block(top = 950, height = 200)))

        result.forEach { assertTrue(it.end >= it.start) }
    }

    // --- horizontal ---------------------------------------------------------

    @Test
    fun `labels on the same row split the gap between them`() {
        val first = block(top = 100, left = 100, width = 100)
        val second = block(top = 100, left = 400, width = 100)

        val result = columns(listOf(first, second))

        assertEquals(Band(0, 300), result[0])
        assertEquals(Band(300, screenWidth), result[1])
    }

    @Test
    fun `blocks on different rows do not constrain each other horizontally`() {
        val upper = block(top = 100, left = 100, width = 100)
        val lower = block(top = 400, left = 100, width = 100)
        val beside = block(top = 100, left = 700, width = 100)

        val result = columns(listOf(upper, lower, beside))

        // `lower` shares no row with anything, so it may use the whole width.
        assertEquals(Band(0, screenWidth), result[1])
        // `upper` is stopped halfway to `beside`, not by the block below it.
        assertEquals(Band(0, 450), result[0])
    }

    @Test
    fun `a label with no neighbour to its right may reach the screen edge`() {
        val only = block(top = 100, left = 100, width = 100)

        assertEquals(Band(0, screenWidth), columns(listOf(only)).single())
    }

    @Test
    fun `an untranslated label still stops its neighbour from spreading over it`() {
        // A tab bar: "Video" translates to itself and is left alone, so only the
        // label after it is drawn — and it must not run into "Video".
        val video = block(top = 100, left = 200, width = 100)
        val next = block(top = 100, left = 400, width = 100)

        val result = HorizontalBands.of(
            listOf(video, next),
            drawn = { it !== video },
            screenWidth = screenWidth,
        )

        assertEquals(Band(0, 0), result[0])
        // Halfway across the gap to "Video", not the whole way to the screen edge.
        assertEquals(350, result[1].start)
    }
}
