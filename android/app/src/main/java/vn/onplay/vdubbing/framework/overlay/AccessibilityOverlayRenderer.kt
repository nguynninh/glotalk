package vn.onplay.vdubbing.framework.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.TextBlock
import vn.onplay.vdubbing.framework.locale.AppLocaleController

/**
 * The on-screen layer: translated lines, plus one floating bubble.
 *
 * Two windows, never one. The text window is FLAG_NOT_TOUCHABLE so every tap
 * still reaches the app underneath; the bubble is a separate small window that
 * *does* take touches. A single window cannot be both — and a touchable one
 * covering the whole screen would make the phone unusable.
 *
 * The bubble doubles as the way out: while a translation is showing it turns
 * into a "✕" that clears it; otherwise it is a "文A" that starts one. Either way
 * a short tap acts and a drag moves it.
 */
class AccessibilityOverlayRenderer(
    private val service: AccessibilityService,
) : OverlayRenderer {

    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    private val density = service.resources.displayMetrics.density

    private var textRoot: FrameLayout? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleMode: BubbleMode = BubbleMode.TRIGGER
    private var onTranslateTap: (() -> Unit)? = null

    private enum class BubbleMode { TRIGGER, DISMISS }

    override fun attach() = Unit

    // --- translation -------------------------------------------------------

    override fun show(blocks: List<TextBlock>, screenWidth: Int, screenHeight: Int) {
        removeTextWindow()
        if (blocks.isEmpty()) return

        val container = FrameLayout(service)
        blocks.forEach { block -> addLine(container, block, screenWidth) }
        if (container.childCount == 0) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { windowManager.addView(container, params) }
            .onSuccess {
                textRoot = container
                setBubbleMode(BubbleMode.DISMISS)
            }
    }

    override fun hide() {
        removeTextWindow()
        setBubbleMode(BubbleMode.TRIGGER)
    }

    private fun removeTextWindow() {
        textRoot?.let { runCatching { windowManager.removeView(it) } }
        textRoot = null
    }

    private fun addLine(container: FrameLayout, block: TextBlock, screenWidth: Int) {
        val text = block.text.trim()
        if (text.isEmpty()) return

        val view = TextView(service).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            setBackgroundColor(BACKGROUND)
            setPadding(dp(3), dp(1), dp(3), dp(1))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeFor(block))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 6
        }

        val width = block.width.coerceIn(dp(24), screenWidth)
        val params = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = block.left.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            topMargin = block.top.coerceAtLeast(0)
        }
        container.addView(view, params)
    }

    // --- bubble ------------------------------------------------------------

    override fun showBubble(onTranslate: () -> Unit) {
        onTranslateTap = onTranslate
        val existing = bubble
        if (existing != null) {
            setBubbleMode(bubbleMode)
            return
        }

        val size = dp(BUBBLE_DP)
        val view = TextView(service).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            elevation = dp(6).toFloat()
            // Context: without this the bubble is a floating glyph with no meaning.
            contentDescription = bubbleDescription()
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable would steal the keyboard from the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(BUBBLE_START_TOP_DP)
        }

        attachDragAndTap(view, params)

        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                bubble = view
                bubbleParams = params
                setBubbleMode(BubbleMode.TRIGGER)
            }
    }

    override fun hideBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleParams = null
        onTranslateTap = null
    }

    private fun setBubbleMode(mode: BubbleMode) {
        bubbleMode = mode
        val view = bubble ?: return
        val isDismiss = mode == BubbleMode.DISMISS
        view.text = if (isDismiss) "✕" else "文A"
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isDismiss) DISMISS_COLOR else TRIGGER_COLOR)
            setStroke(dp(2), Color.WHITE)
        }
        view.contentDescription = bubbleDescription()
    }

    /** Re-applies the TalkBack label; the accessibility service calls this on a language change. */
    fun refreshContentDescription() {
        bubble?.contentDescription = bubbleDescription()
    }

    /**
     * Read at use time, never cached: the service outlives a language change on
     * Android 12 and below, and a cached string would stay in the old language.
     */
    private fun bubbleDescription(): String {
        val strings = AppLocaleController.localized(service)
        val id = if (bubbleMode == BubbleMode.DISMISS) {
            R.string.bubble_clear_translation
        } else {
            R.string.bubble_translate_screen
        }
        return strings.getString(id)
    }

    /**
     * Tap acts, drag moves. A plain OnClickListener does not fire once a touch
     * listener is installed, so the tap is detected on ACTION_UP.
     */
    private fun attachDragAndTap(view: TextView, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragged = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) onBubbleTapped()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragged = false
                    true
                }

                else -> false
            }
        }
    }

    private fun onBubbleTapped() {
        if (bubbleMode == BubbleMode.DISMISS) {
            hide()
        } else {
            onTranslateTap?.invoke()
        }
    }

    /** Smaller type for short lines, so the overlay does not cover neighbours. */
    private fun textSizeFor(block: TextBlock): Float {
        val heightDp = block.height / density
        return when {
            heightDp > 60f -> 16f
            heightDp > 36f -> 13f
            heightDp > 22f -> 11f
            else -> 9f
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        val BACKGROUND = 0xC0000000.toInt()
        val DISMISS_COLOR = 0xE6D32F2F.toInt()
        val TRIGGER_COLOR = 0xE61976D2.toInt()
        const val BUBBLE_DP = 52
        const val BUBBLE_START_TOP_DP = 140
    }
}
