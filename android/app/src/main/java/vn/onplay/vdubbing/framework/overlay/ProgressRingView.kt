package vn.onplay.vdubbing.framework.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The indeterminate arc drawn inside the bubble while a translation runs.
 *
 * Indeterminate on purpose: the pipeline is OCR, then language detection, then
 * translation, and only the last of those can report a percentage — a fake bar
 * that jumps would claim more than the app knows. A spinning arc says "working"
 * and nothing it cannot back up.
 */
class ProgressRingView(context: Context) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x4DFFFFFF
        strokeCap = Paint.Cap.ROUND
    }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }

    private val bounds = RectF()
    private var rotation = 0f

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = SPIN_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotation = it.animatedValue as Float
            invalidate()
        }
    }

    /**
     * The ring is a child of the bubble and stays attached while hidden, so the
     * animation follows [visibility] rather than attach/detach — otherwise a
     * hidden ring keeps invalidating every frame for nothing.
     */
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            if (!spin.isStarted) spin.start()
        } else {
            spin.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && !spin.isStarted) spin.start()
    }

    override fun onDetachedFromWindow() {
        // A window can be removed at any time; an animator left running keeps the
        // whole overlay process awake for a bubble nobody can see.
        spin.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = width * STROKE_RATIO
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val inset = stroke / 2f + width * INSET_RATIO
        bounds.set(inset, inset, width - inset, height - inset)

        canvas.drawArc(bounds, 0f, 360f, false, track)
        canvas.drawArc(bounds, rotation, SWEEP_DEGREES, false, arc)
    }

    private companion object {
        const val SPIN_MS = 900L
        const val SWEEP_DEGREES = 90f
        const val STROKE_RATIO = 0.09f
        const val INSET_RATIO = 0.2f
    }
}
