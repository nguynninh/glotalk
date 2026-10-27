package vn.onplay.vdubbing.data.capture

import kotlin.math.abs
import vn.onplay.vdubbing.domain.model.TextBlock

internal fun refineTextInkBounds(block: TextBlock, width: Int, height: Int, pixel: (Int, Int) -> Int): TextBlock {
    if (block.width <= 0 || block.height <= 0 || width <= 0 || height <= 0) return block
    var left = block.left.coerceIn(0, width - 1)
    var right = (block.left.toLong() + block.width).coerceIn(left + 1L, width.toLong()).toInt()
    var top = block.top.coerceIn(0, height - 1)
    var bottom = (block.top.toLong() + block.height).coerceIn(top + 1L, height.toLong()).toInt()
    val margin = (block.height / block.lineCount.coerceAtLeast(1) / 2).coerceIn(1, 48)
    val background = block.backgroundColor
    fun ink(x: Int, y: Int): Boolean {
        val color = pixel(x, y)
        return maxOf(abs((color shr 16 and 255) - (background shr 16 and 255)),
            abs((color shr 8 and 255) - (background shr 8 and 255)),
            abs((color and 255) - (background and 255))) > 48
    }
    fun column(x: Int) = (top until bottom).any { ink(x, it) }
    fun row(y: Int) = (left until right).any { ink(it, y) }
    val minLeft = (left - margin).coerceAtLeast(0)
    val maxRight = (right + margin).coerceAtMost(width)
    val minTop = (top - margin).coerceAtLeast(0)
    val maxBottom = (bottom + margin).coerceAtMost(height)
    // Stop at whitespace so missing stems are covered without swallowing adjacent icons or borders.
    while (left > minLeft && column(left - 1)) left--
    while (right < maxRight && column(right)) right++
    while (top > minTop && row(top - 1)) top--
    while (bottom < maxBottom && row(bottom)) bottom++
    return block.copy(left = left, top = top, width = right - left, height = bottom - top)
}
