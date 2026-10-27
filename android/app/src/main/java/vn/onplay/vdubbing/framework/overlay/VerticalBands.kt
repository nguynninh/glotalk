package vn.onplay.vdubbing.framework.overlay

import vn.onplay.vdubbing.domain.model.TextBlock

/** Vertical extent one translated block may occupy, in screenshot pixels. */
internal data class Band(val top: Int, val bottom: Int)

/**
 * Splits the screen into one vertical band per block, so no translation can
 * cover the translation of the line above or below it.
 *
 * Every line is drawn at its own block's absolute position in a single
 * full-screen window, and a translated block is routinely taller than the
 * original line it replaces. Without a boundary the extra height ran into the
 * neighbouring block: the translation of a paragraph covered the tail of the
 * heading above it.
 *
 * Only blocks that overlap horizontally count as neighbours — two columns of
 * text at the same height have no reason to constrain each other.
 */
internal object VerticalBands {

    fun of(
        blocks: List<TextBlock>,
        drawn: (TextBlock) -> Boolean,
        screenHeight: Int,
    ): List<Band> {
        val visible = blocks.filter(drawn)
        return blocks.map { block ->
            if (!drawn(block)) return@map Band(0, 0)

            val bottom = block.top + block.height
            val above = visible
                .filter { it !== block && overlapsHorizontally(it, block) && it.top < block.top }
                .maxByOrNull { it.top }
            val below = visible
                .filter { it !== block && overlapsHorizontally(it, block) && it.top > block.top }
                .minByOrNull { it.top }

            // Halfway into the gap on either side, so two neighbouring plates
            // can never meet. Clamped to the block's own box: OCR boxes
            // sometimes already intersect, and then neither may grow at all.
            val top = above
                ?.let { (it.top + it.height + block.top) / 2 }
                ?.coerceAtMost(block.top)
                ?: 0
            val end = below
                ?.let { (bottom + it.top) / 2 }
                ?.coerceAtLeast(bottom)
                ?: screenHeight
            Band(top, end)
        }
    }

    private fun overlapsHorizontally(a: TextBlock, b: TextBlock): Boolean =
        a.left < b.left + b.width && b.left < a.left + a.width
}
