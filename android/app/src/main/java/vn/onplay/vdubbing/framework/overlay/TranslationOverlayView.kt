package vn.onplay.vdubbing.framework.overlay

import android.annotation.SuppressLint
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
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip

/** Layouts are prepared once; drawing never measures text or reads screenshot pixels. */
@SuppressLint("ViewConstructor") // Created programmatically with prepared text, never inflated from XML.
internal class TranslationOverlayView(context: Context, private val entries: List<OverlayText>) : View(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        entries.forEach { it.draw(canvas) }
    }
}

internal class OverlayText(
    private val left: Int,
    private val top: Int,
    private val boxWidth: Int,
    private val boxHeight: Int,
    text: String,
    preferredSize: Float,
    minSize: Float,
    isParagraph: Boolean,
    private val backgroundColor: Int?,
    private val outlineWidth: Float,
) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        isSubpixelText = true
    }
    private val maskPaint = Paint().apply { color = backgroundColor ?: Color.TRANSPARENT }
    private val fillColor = backgroundColor?.let {
        if (ColorUtils.calculateContrast(Color.BLACK, it) >= ColorUtils.calculateContrast(Color.WHITE, it)) {
            Color.BLACK
        } else Color.WHITE
    } ?: Color.WHITE
    internal val layout: StaticLayout

    init {
        val inset = outlineWidth.toInt() + 1
        val textWidth = (boxWidth - inset * 2).coerceAtLeast(1)
        val textHeight = (boxHeight - inset * 2).coerceAtLeast(1)
        fun build(size: Float, ellipsize: Boolean = false): StaticLayout {
            paint.textSize = size
            val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
                .setAlignment(if (isParagraph) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(true)
                .setLineSpacing(0f, 1f)
            if (ellipsize) {
                val lineHeight = (paint.fontMetrics.bottom - paint.fontMetrics.top).coerceAtLeast(1f)
                builder.setMaxLines((textHeight / lineHeight).toInt().coerceAtLeast(1))
                    .setEllipsize(TextUtils.TruncateAt.END)
            }
            return builder.build()
        }
        fun fits(candidate: StaticLayout): Boolean = candidate.height <= textHeight &&
            (0 until candidate.lineCount).all { candidate.getLineWidth(it) <= textWidth }

        var low = minSize.coerceAtLeast(1f)
        var high = preferredSize.coerceAtLeast(low)
        var best = build(low)
        if (fits(best)) {
            repeat(8) {
                val mid = (low + high) / 2f
                val candidate = build(mid)
                if (fits(candidate)) {
                    best = candidate
                    low = mid
                } else high = mid
            }
            // StaticLayout holds this paint, so restore the size of the selected layout.
            paint.textSize = low
        } else {
            best = build(low, ellipsize = true)
        }
        layout = best
    }

    fun draw(canvas: Canvas) {
        canvas.withClip(left, top, left + boxWidth, top + boxHeight) {
            if (backgroundColor != null) {
                canvas.drawRect(left.toFloat(), top.toFloat(), (left + boxWidth).toFloat(), (top + boxHeight).toFloat(), maskPaint)
            }
            canvas.translate(left + (boxWidth - layout.width) / 2f, top + (boxHeight - layout.height) / 2f)
            if (outlineWidth > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = outlineWidth * 2f
                paint.color = Color.BLACK
                layout.draw(canvas)
            }
            paint.style = Paint.Style.FILL
            paint.color = fillColor
            layout.draw(canvas)
        }
    }
}
