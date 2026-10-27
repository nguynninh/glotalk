package vn.onplay.vdubbing.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock

class ScreenBoundsTest {
    private val block = TextBlock("text", "dịch", Language.ENGLISH, 100, 200, 300, 40)

    @Test fun `cloud coordinates scale back to original portrait screenshot`() {
        assertEquals(block.copy(left = 150, top = 300, width = 450, height = 60),
            restoreScreenBounds(block, 1080, 2400))
    }

    @Test fun `cloud coordinates scale back to original landscape screenshot`() {
        assertEquals(block.copy(left = 150, top = 300, width = 450, height = 60),
            restoreScreenBounds(block, 2400, 1080))
    }

    @Test fun `small screenshots retain original coordinates`() {
        assertEquals(block, restoreScreenBounds(block, 720, 1280))
    }
}
