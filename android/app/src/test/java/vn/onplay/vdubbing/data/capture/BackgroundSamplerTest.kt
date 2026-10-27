package vn.onplay.vdubbing.data.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock

class BackgroundSamplerTest {
    private val block = TextBlock("text", "dịch", Language.ENGLISH, 20, 20, 60, 20)

    @Test fun `colored button keeps its background despite a few dark border pixels`() {
        val green = 0xFF8BC34A.toInt()
        val sampled = BackgroundSampler.sample(block, 100, 60) { x, y ->
            if (x in 48..52 || y == 41) 0xFF202124.toInt() else green
        }
        assertEquals(green, sampled)
    }

    @Test fun `edge samples stay inside the screenshot`() {
        val blue = 0xFF1976D2.toInt()
        val sampled = BackgroundSampler.sample(block.copy(left = -4, top = -3, width = 110, height = 80), 100, 60) { x, y ->
            check(x in 0 until 100 && y in 0 until 60)
            blue
        }
        assertEquals(blue, sampled)
    }

    @Test fun `invalid boxes keep the fallback without reading pixels`() {
        assertEquals(block.backgroundColor, BackgroundSampler.sample(block.copy(width = 0), 100, 60) { _, _ ->
            error("must not sample invalid box")
        })
    }
}
