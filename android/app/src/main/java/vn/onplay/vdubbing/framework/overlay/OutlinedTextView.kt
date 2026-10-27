package vn.onplay.vdubbing.framework.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/**
 * One translated line, drawn one of two ways.
 *
 * *Parallel* is bare text — a white face with a dark outline — hung under the
 * original words, so the screen keeps its layout and the two languages are both
 * visible. The outline is what makes it readable over light and dark content.
 *
 * *Replace* paints a plate the colour of the page over the original words and
 * writes the translation in their place, in the page's own text colour. This is
 * the only mode that stays readable when the translation is a different length
 * from the original, which is most of the time.
 *
 * The text is laid out by [StaticLayout] and painted twice with the same
 * layout: once as a stroke (the outline) and once filled on top, so the stroke
 * only shows around the glyph edges.
 */
class OutlinedTextView(context: Context) : View(context) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        // Medium, not bold: the outline already adds weight, and a bold face
        // plus a stroke closes up the counters of small glyphs.
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        isSubpixelText = true
    }

    private var layout: StaticLayout? = null

    /** Outline thickness per side, in pixels. */
    var outlineWidth: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var outlineColor: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    var fillColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** Weight 100–900; default 400 (normal). MinSdk 30 ≥ API 28, so the 3-arg create is safe. */
    var fontWeight: Int = 400
        set(value) {
            field = value
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, value, false)
            invalidate()
        }

    /**
     * Extra pixels above and below the laid-out text. Measured into the view so
     * the stroke on the top and bottom glyph edges is drawn, not clipped.
     */
    var verticalSlack: Int = 0
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** Height of the laid-out text alone, without [verticalSlack]. */
    var contentHeight: Int = 0
        private set

    /**
     * Background painted over the original words before the text. Null in
     * parallel mode, where the original stays visible underneath.
     */
    var plate: Plate? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Lays [text] out to fit [maxWidth] x [maxHeight], shrinking from
     * [preferredSize] down to [minSize] when the translation is longer than the
     * space the original line occupied.
     *
     * @return the height the text actually needs.
     */
    fun bind(
        text: CharSequence,
        maxWidth: Int,
        maxHeight: Int,
        preferredSize: Float,
        minSize: Float,
        isParagraph: Boolean = false,
    ): Int {
        val width = maxWidth.coerceAtLeast(1)
        val height = maxHeight.coerceAtLeast(1)

        var size = preferredSize.coerceAtLeast(minSize)
        var built = build(text, width, size, height, isParagraph)
        while (size > minSize && (built.height > height || hasEllipsis(built))) {
            size = (size * SHRINK_STEP).coerceAtLeast(minSize)
            built = build(text, width, size, height, isParagraph)
            if (size <= minSize) break
        }

        layout = built
        contentHeight = built.height
        requestLayout()
        invalidate()
        return contentHeight
    }

    private fun hasEllipsis(layout: StaticLayout): Boolean {
        if (layout.lineCount == 0) return false
        return layout.getEllipsisCount(layout.lineCount - 1) > 0
    }

    private fun build(
        text: CharSequence,
        width: Int,
        size: Float,
        maxHeight: Int,
        isParagraph: Boolean,
    ): StaticLayout {
        textPaint.textSize = size
        // The multiplier is part of the line's pitch; measuring the font alone
        // overestimated maxLines by 15%, and the layout then grew taller than the
        // box it was drawn in — spilling over the line above and below.
        val lineHeight = ((textPaint.descent() - textPaint.ascent()) * LINE_SPACING_MULT)
            .coerceAtLeast(1f)
        val maxLines = (maxHeight / lineHeight).toInt().coerceIn(1, MAX_LINES)

        val alignment = if (isParagraph) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER

        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, LINE_SPACING_MULT)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            contentHeight + verticalSlack
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            measuredH,
        )
    }

    override fun onDraw(canvas: Canvas) {
        plate?.let { drawPlate(canvas, it) }
        val layout = layout ?: return

        canvas.save()
        // Centred in the view so a layout narrower than its bounds still lines
        // up with the block it replaces.
        canvas.translate(
            (width - layout.width) / 2f,
            (height - layout.height) / 2f,
        )

        if (outlineWidth > 0f) {
            // Drawn first, then covered by the fill: the stroke survives only
            // as the rim around each glyph.
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeJoin = Paint.Join.ROUND
            textPaint.strokeWidth = outlineWidth * 2f
            textPaint.color = outlineColor
            layout.draw(canvas)
        }

        textPaint.style = Paint.Style.FILL
        textPaint.color = fillColor
        layout.draw(canvas)

        canvas.restore()
    }

    /**
     * The plate covers the whole view, not just the text, so the original words
     * behind it are covered edge to edge; [Plate.inset] shrinks it slightly if requested.
     */
    private fun drawPlate(canvas: Canvas, plate: Plate) {
        platePaint.color = plate.color
        val inset = plate.inset.toFloat()
        canvas.drawRoundRect(
            inset,
            inset,
            width.toFloat() - inset,
            height.toFloat() - inset,
            plate.cornerRadius,
            plate.cornerRadius,
            platePaint,
        )
    }

    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private companion object {
        const val MAX_LINES = 50
        const val SHRINK_STEP = 0.92f
        const val LINE_SPACING_MULT = 1.15f
    }
}

/**
 * Fill drawn behind a translated line, in the colour of the page it covers.
 *
 * @param inset pixels trimmed from each edge. Default is 0 so the plate fully covers text underneath.
 */
data class Plate(val color: Int, val cornerRadius: Float, val inset: Int = 0)
