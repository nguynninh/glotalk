package vn.onplay.vdubbing.data.capture

import vn.onplay.vdubbing.domain.model.TextBlock

internal object BackgroundSampler {
    fun sample(block: TextBlock, width: Int, height: Int, pixel: (Int, Int) -> Int): Int {
        if (width <= 0 || height <= 0 || block.width <= 0 || block.height <= 0) {
            return block.backgroundColor
        }
        val left = (block.left.toLong() - 2).coerceIn(0, width - 1L).toInt()
        val right = (block.left.toLong() + block.width + 1).coerceIn(0, width - 1L).toInt()
        val top = (block.top.toLong() - 2).coerceIn(0, height - 1L).toInt()
        val bottom = (block.top.toLong() + block.height + 1).coerceIn(0, height - 1L).toInt()
        val samples = buildList {
            for (i in 0..16) {
                val x = left + (right - left) * i / 16
                val y = top + (bottom - top) * i / 16
                add(pixel(x, top))
                add(pixel(x, bottom))
                add(pixel(left, y))
                add(pixel(right, y))
            }
        }
        // shortcut: a flat mask cannot restore artwork; use inpainting for textured backgrounds.
        val background = samples.groupBy { color ->
            ((color shr 19) and 31) shl 10 or
                (((color shr 11) and 31) shl 5) or ((color shr 3) and 31)
        }.values.maxBy { it.size }
        fun channel(shift: Int) = background.sumOf { (it shr shift) and 255 } / background.size
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
