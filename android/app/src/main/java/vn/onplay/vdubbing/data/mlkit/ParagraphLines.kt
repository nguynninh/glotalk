package vn.onplay.vdubbing.data.mlkit

import android.graphics.Rect

internal fun canJoinParagraph(previous: Rect, next: Rect): Boolean {
    val gap = next.top - previous.bottom
    val overlap = minOf(previous.right, next.right) -
        maxOf(previous.left, next.left)
    val minHeight = minOf(previous.height(), next.height()).coerceAtLeast(1)
    val maxHeight = maxOf(previous.height(), next.height())
    return gap >= -minHeight / 4 && gap <= minHeight * 0.8f &&
        maxHeight <= minHeight * 1.6f &&
        overlap >= minOf(previous.width(), next.width()) * 0.5f
}
