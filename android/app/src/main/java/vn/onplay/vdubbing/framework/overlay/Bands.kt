package vn.onplay.vdubbing.framework.overlay

import vn.onplay.vdubbing.domain.model.TextBlock

/** A block's usable extent along one axis, in screenshot pixels. */
internal data class Band(val start: Int, val end: Int)

/**
 * Keeps translated text inside the space its own block owns.
 *
 * Every line is drawn at its block's absolute position in a single full-screen
 * window, and a translation is routinely longer than the words it replaces — in
 * either direction. Without a boundary the extra size ran into the neighbouring
 * block: a long paragraph covered the heading above it, and a long nav label
 * spread across the labels beside it.
 *
 * A block only ever shares space with blocks it actually touches along the other
 * axis: text below a line does not constrain it horizontally, and two columns of
 * text at the same height do not constrain each other vertically.
 *
 * A block we do not draw still holds its space: its original words are left
 * untouched on the screen, so a neighbour growing into them reads as a single
 * run of text — "Video" followed immediately by the next label's translation.
 */
internal object VerticalBands {

    fun of(
        blocks: List<TextBlock>,
        drawn: (TextBlock) -> Boolean,
        screenHeight: Int,
    ): List<Band> {
        return blocks.map { block ->
            if (!drawn(block)) return@map Band(0, 0)

            val bottom = block.top + block.height
            val above = blocks
                .filter { it !== block && overlapsHorizontally(it, block) && it.top < block.top }
                .maxByOrNull { it.top }
            val below = blocks
                .filter { it !== block && overlapsHorizontally(it, block) && it.top > block.top }
                .minByOrNull { it.top }

            // Halfway into the gap on either side, so two neighbouring plates
            // can never meet. Clamped to the block's own box: OCR boxes
            // sometimes already intersect, and then neither may grow at all.
            val start = above
                ?.let { (it.top + it.height + block.top) / 2 }
                ?.coerceAtMost(block.top)
                ?: 0
            val end = below
                ?.let { (bottom + it.top) / 2 }
                ?.coerceAtLeast(bottom)
                ?: screenHeight
            Band(start, end)
        }
    }
}

internal object HorizontalBands {

    fun of(
        blocks: List<TextBlock>,
        drawn: (TextBlock) -> Boolean,
        screenWidth: Int,
    ): List<Band> {
        return blocks.map { block ->
            if (!drawn(block)) return@map Band(0, 0)

            val right = block.left + block.width
            val before = blocks
                .filter { it !== block && overlapsVertically(it, block) && it.left < block.left }
                .maxByOrNull { it.left }
            val after = blocks
                .filter { it !== block && overlapsVertically(it, block) && it.left > block.left }
                .minByOrNull { it.left }

            val start = before
                ?.let { (it.left + it.width + block.left) / 2 }
                ?.coerceAtMost(block.left)
                ?: 0
            val end = after
                ?.let { (right + it.left) / 2 }
                ?.coerceAtLeast(right)
                ?: screenWidth
            Band(start.coerceIn(0, screenWidth), end.coerceIn(0, screenWidth))
        }
    }
}

private fun overlapsHorizontally(a: TextBlock, b: TextBlock): Boolean =
    a.left < b.left + b.width && b.left < a.left + a.width

private fun overlapsVertically(a: TextBlock, b: TextBlock): Boolean =
    a.top < b.top + b.height && b.top < a.top + a.height
