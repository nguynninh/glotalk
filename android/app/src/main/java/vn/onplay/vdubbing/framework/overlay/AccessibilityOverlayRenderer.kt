package vn.onplay.vdubbing.framework.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock
import vn.onplay.vdubbing.framework.locale.AppLocaleController

/**
 * Everything this app draws on top of another app.
 *
 * Four windows, never one. The translation text is FLAG_NOT_TOUCHABLE so every
 * tap still reaches the app underneath; the bubble, the panel and the menu each
 * get a window of their own, because a touchable window covering the whole
 * screen would make the phone unusable.
 *
 * The bubble is the whole interface while another app is in front:
 *  - tap        → the translation panel (languages, mode, start);
 *  - long-press → the quick menu (settings, language, hide, exit);
 *  - drag       → move it, and it stays where it was put.
 * While a translation is showing the bubble turns into a red ✕, so the most
 * obvious tap is also the way out.
 */
class AccessibilityOverlayRenderer(
    private val service: AccessibilityService,
) : OverlayRenderer {

    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    private val ui = OverlayUi(service)
    private val panelViews = OverlayPanelContent(service, ui, { host }, ::closePanel, ::refreshPanel) {
        onTranslateTap?.invoke()
    }
    private val menuViews = OverlayMenuContent(service, ui)

    /** Set by the foreground service; null while it is not running. */
    var host: OverlayHost? = null

    /** Whether translated lines are on screen right now. */
    override val hasTranslation: Boolean get() = textBlocks.isNotEmpty()

    override var translationShownTime: Long = 0L
        private set

    // --- translation text --------------------------------------------------
    private var textRoot: TranslationOverlayView? = null
    private var textBlocks: List<TextBlock> = emptyList()
    private var textScreenWidth = 0
    private var textScreenHeight = 0
    private var textMode: DisplayMode = DisplayMode.REPLACE

    // --- bubble ------------------------------------------------------------
    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleMode: BubbleMode = BubbleMode.TRIGGER
    /** Kept across service restarts so the bubble does not jump back to its start spot. */
    private var bubblePosition: Pair<Int, Int>? = null
    private var bubbleText: TextView? = null
    private var bubbleRing: ProgressRingView? = null
    /** True between "Bắt đầu dịch" and the result: the bubble shows it. */
    private var bubbleBusy = false

    // --- panel, menu -------------------------------------------------------
    private var panel: View? = null
    private var menu: View? = null

    /** Fallback for the panel's start button when no service is attached. */
    private var onTranslateTap: (() -> Unit)? = null

    private enum class BubbleMode { TRIGGER, DISMISS }

    override fun attach() = Unit

    // =======================================================================
    // Translation text
    // =======================================================================

    override fun show(blocks: List<TextBlock>, screenWidth: Int, screenHeight: Int) {
        if (blocks.isEmpty()) return
        textBlocks = blocks
        textScreenWidth = screenWidth
        textScreenHeight = screenHeight
        host?.panelState()?.let { textMode = it.displayMode }
        translationShownTime = android.os.SystemClock.uptimeMillis()
        drawTranslation()
        setBubbleMode(if (textRoot != null) BubbleMode.DISMISS else BubbleMode.TRIGGER)
    }

    override fun setDisplayMode(mode: DisplayMode) {
        if (textMode == mode) return
        textMode = mode
        if (textBlocks.isNotEmpty()) drawTranslation()
    }

    override fun hide() {
        removeTextWindow()
        // Cleared, not just un-drawn: `hasTranslation` is what the menu and the
        // panel read to decide between "pause" and "start".
        textBlocks = emptyList()
        translationShownTime = 0L
        setBubbleMode(BubbleMode.TRIGGER)
    }

    override fun dismissTransientWindows() {
        closePanel()
        closeMenu()
        hide()
    }

    override fun refresh() {
        refreshContentDescription()
        if (textBlocks.isNotEmpty()) drawTranslation()
        // The panel and the menu are built from strings, so they are rebuilt
        // rather than patched when the interface language changes.
        if (panel != null && !panelViews.isAdjustingVolume) openPanel()
        if (menu != null) openMenu()
    }

    private fun drawTranslation() {
        removeTextWindow()
        if (textBlocks.isEmpty()) return
        if (textScreenWidth <= 0 || textScreenHeight <= 0) return

        val rows = VerticalBands.of(textBlocks, ::shouldDraw, textScreenHeight)
        val entries = textBlocks.mapIndexedNotNull { index, block ->
            prepareText(block, rows[index])
        }
        if (entries.isEmpty()) return
        val container = TranslationOverlayView(service, entries)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // The OCR boxes are pixels of the screenshot, which starts at
                // the top of the display. Without this the window is inset by
                // the status bar and every line lands lower than the words it
                // replaces.
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        edgeToEdge(params)

        runCatching { windowManager.addView(container, params) }
            .onSuccess {
                textRoot = container
                // Separate windows stack by insertion order; elevation cannot lift the bubble above text.
                bubble?.let { view ->
                    runCatching {
                        windowManager.removeViewImmediate(view)
                        windowManager.addView(view, bubbleParams)
                    }.onFailure { Log.e(TAG, "bubble window refused", it) }
                }
            }
            // Swallowed before, which is how an entire translation could vanish
            // with no trace in the log.
            .onFailure { Log.e(TAG, "text window refused", it) }
    }

    private fun removeTextWindow() {
        textRoot?.let { runCatching { windowManager.removeView(it) } }
        textRoot = null
    }

    /**
     * A translation identical to what is already on screen would be drawn
     * straight over the original glyphs — blurred double text, not a
     * translation. Same-language screens and untranslatable scripts produce
     * exactly that, so those lines are left alone.
     */
    private fun shouldDraw(block: TextBlock): Boolean {
        return shouldDrawTranslation(block)
    }

    private fun prepareText(block: TextBlock, row: Band): OverlayText? {
        if (!shouldDraw(block) || block.width <= 0 || block.height <= 0) return null
        val replace = textMode == DisplayMode.REPLACE
        val pad = if (replace) ui.px(1f) else 0
        val left = (block.left.toLong() - pad).coerceIn(0, textScreenWidth.toLong()).toInt()
        val right = (block.left.toLong() + block.width + pad)
            .coerceIn(0, textScreenWidth.toLong()).toInt()
        val top = if (replace) {
            (block.top.toLong() - pad).coerceIn(0, textScreenHeight.toLong()).toInt()
        } else {
            (block.top.toLong() + block.height).coerceIn(0, textScreenHeight.toLong()).toInt()
        }
        val bottom = if (replace) {
            (block.top.toLong() + block.height + pad).coerceIn(0, textScreenHeight.toLong()).toInt()
        } else {
            minOf(row.end, top + (block.height * PARALLEL_HEIGHT_GROWTH).toInt(), textScreenHeight)
        }
        if (right <= left || bottom <= top) return null
        val preferred = textSizeFor(block)
        return OverlayText(
            left = left,
            top = top,
            boxWidth = right - left,
            boxHeight = bottom - top,
            text = block.text.trim(),
            preferredSize = preferred,
            minSize = minOf(preferred, ui.sp(MIN_TEXT_SIZE_SP), (bottom - top) * 0.6f),
            isParagraph = block.lineCount > 1,
            backgroundColor = if (replace) block.backgroundColor else null,
            outlineWidth = if (replace) 0f else ui.px(OUTLINE_WIDTH_DP).toFloat(),
        )
    }

    /**
     * Type size that matches the height of the original line, so the
     * translation reads at the same scale as the text it replaces.
     *
     * Line height is normalized by the block's lineCount so multi-line paragraphs
     * don't inflate font sizes.
     */
    private fun textSizeFor(block: TextBlock): Float {
        val lines = block.lineCount.coerceAtLeast(1)
        val singleLineHeightPx = (block.height.toFloat() / lines).coerceAtLeast(1f)
        val ratio = if (textMode == DisplayMode.REPLACE) REPLACE_GLYPH_RATIO else GLYPH_RATIO
        return (singleLineHeightPx * ratio).coerceIn(
            ui.sp(MIN_TEXT_SIZE_SP),
            ui.sp(MAX_TEXT_SIZE_SP),
        )
    }

    // =======================================================================
    // Bubble
    // =======================================================================

    override fun showBubble(onTranslate: () -> Unit) {
        onTranslateTap = onTranslate
        if (bubble != null) {
            setBubbleMode(bubbleMode)
            return
        }

        val size = ui.px(BUBBLE_DP)
        val view = FrameLayout(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Overlay.BLUE)
            }
            elevation = ui.px(6f).toFloat()
            // Context: without this the bubble is a floating glyph with no meaning.
            contentDescription = bubbleDescription()
        }
        val glyph = ui.text(
            OverlayUi.BUBBLE_TEXT,
            15f,
            Color.WHITE,
            bold = true,
            gravity = Gravity.CENTER,
        )
        view.addView(glyph, FrameLayout.LayoutParams(size, size))
        bubbleText = glyph

        // Same disc, plus a ring: nothing else in the app can say "working"
        // while another app is in front, and a translation takes long enough
        // that a silent bubble looks broken.
        val ring = ProgressRingView(service).apply {
            // Starts hidden: the first translation is what starts the animation.
            visibility = View.GONE
        }
        view.addView(ring, FrameLayout.LayoutParams(size, size))
        bubbleRing = ring

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
            bubblePosition?.let { (x, y) ->
                this.x = x
                this.y = y
            } ?: run {
                x = ui.px(16f)
                y = ui.px(BUBBLE_START_TOP_DP)
            }
            clampBubblePosition(this)
        }
        edgeToEdge(params)

        attachDragAndTap(view, params)

        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                bubble = view
                bubbleParams = params
                setBubbleMode(bubbleMode)
            }
    }

    override fun setBusy(busy: Boolean) {
        bubbleBusy = busy
        applyBubbleAppearance()
    }

    internal fun setBubbleVisible(visible: Boolean) {
        bubble?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    override fun hideBubble() {
        closePanel()
        closeMenu()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleParams = null
        bubbleText = null
        onTranslateTap = null
    }

    private fun setBubbleMode(mode: BubbleMode) {
        bubbleMode = mode
        applyBubbleAppearance()
    }

    /**
     * One place that draws the bubble, because three things can change it —
     * a translation appearing, the busy state, and the language — and each of
     * them alone would otherwise leave the other two stale.
     */
    private fun applyBubbleAppearance() {
        val view = bubble ?: return
        val isDismiss = bubbleMode == BubbleMode.DISMISS

        // Busy wins over both states: a ring around a faded "TA" reads as "still
        // working", whereas a red ✕ beside a ring would offer to cancel a
        // translation that is already running.
        bubbleRing?.visibility = if (bubbleBusy) View.VISIBLE else View.GONE
        bubbleText?.apply {
            text = when {
                bubbleBusy -> OverlayUi.BUBBLE_TEXT
                isDismiss -> "✕"
                else -> OverlayUi.BUBBLE_TEXT
            }
            alpha = if (bubbleBusy) BUSY_TEXT_ALPHA else 1f
        }
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isDismiss && !bubbleBusy) Overlay.DANGER else Overlay.BLUE)
        }
        view.contentDescription = bubbleDescription()
    }

    /** Re-applies the TalkBack label; the service calls this on a language change. */
    fun refreshContentDescription() {
        bubble?.contentDescription = bubbleDescription()
    }

    /**
     * Read at use time, never cached: the service outlives a language change on
     * Android 12 and below, and a cached string would stay in the old language.
     */
    private fun bubbleDescription(): String {
        val strings = AppLocaleController.localized(service)
        val id = when {
            bubbleBusy -> R.string.bubble_translating
            bubbleMode == BubbleMode.DISMISS -> R.string.bubble_clear_translation
            else -> R.string.bubble_open_panel
        }
        return strings.getString(id)
    }

    /**
     * Tap acts, long-press opens the menu, drag moves. A plain OnClickListener
     * does not fire once a touch listener is installed, so both gestures are
     * detected here from the event stream.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTap(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var dragged = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    downAt = event.eventTime
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > ui.px(6f) || abs(dy) > ui.px(6f)) dragged = true
                    if (dragged) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clampBubblePosition(params)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    clampBubblePosition(params)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    bubblePosition = params.x to params.y
                    if (!dragged) {
                        if (event.eventTime - downAt > LONG_PRESS_MS) openMenu()
                        else onBubbleTapped()
                    }
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

    private fun clampBubblePosition(params: WindowManager.LayoutParams) {
        val margin = ui.px(8f)
        val screenW = service.resources.displayMetrics.widthPixels
        val screenH = service.resources.displayMetrics.heightPixels
        val size = ui.px(BUBBLE_DP)
        params.x = params.x.coerceIn(margin, (screenW - size - margin).coerceAtLeast(margin))
        params.y = params.y.coerceIn(margin * 3, (screenH - size - margin * 3).coerceAtLeast(margin * 3))
    }

    private fun onBubbleTapped() {
        // While translating the panel would only offer a disabled start button,
        // so a tap there is a cancel: the work is the only thing in the way.
        when {
            bubbleBusy -> host?.onStopTranslation()
            bubbleMode == BubbleMode.DISMISS -> hide()
            else -> openPanel()
        }
    }

    // =======================================================================
    // Panel
    // =======================================================================

    /** True when the bubble sits in the lower half, so the panel should open above it. */
    private fun bubbleAtTop(): Boolean {
        val y = bubbleParams?.y ?: return true
        return y < service.resources.displayMetrics.heightPixels / 2
    }

    private fun openPanel() {
        closePanel()
        closeMenu()
        val state = host?.panelState() ?: PanelState()
        val card = buildPanelCard(state)
        val scrim = scrim(card, alignTop = bubbleAtTop()) { closePanel() }
        runCatching { windowManager.addView(scrim, scrimParams()) }
            .onSuccess {
                panel = scrim
            }
    }

    private fun closePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelViews.reset()
    }

    private fun buildPanelCard(state: PanelState): LinearLayout = panelViews.build(state)

    /** Rebuilds the panel in place, preserving any open dropdown. */
    private fun refreshPanel() {
        if (panel == null) return
        val state = host?.panelState() ?: return
        val card = buildPanelCard(state)
        val wrapper = panelContent() ?: run {
            openPanel()
            return
        }
        wrapper.removeAllViews()
        wrapper.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun panelContent(): android.view.ViewGroup? =
        (panel as? FrameLayout)?.getChildAt(0) as? android.view.ViewGroup

    // =======================================================================
    // Quick menu
    // =======================================================================

    private fun openMenu() {
        closeMenu()
        closePanel()
        val state = host?.panelState() ?: PanelState()
        val card = menuViews.build(
            state,
            onToggleTranslation = {
                closeMenu()
                when {
                    state.audioActive -> host?.onAudioTranslation(false)
                    state.hasTranslation -> hide()
                    else -> host?.onStartTranslation()
                }
            },
            onLanguage = { closeMenu(); openPanel() },
            onSettings = { closeMenu(); host?.onOpenAppSettings() },
            onHideBubble = { closeMenu(); host?.onAudioTranslation(false); hideBubble() },
            onStopService = { closeMenu(); host?.onStopService() },
        )

        val scrim = scrim(card, alignTop = bubbleAtTop()) { closeMenu() }
        runCatching { windowManager.addView(scrim, scrimParams()) }
            .onSuccess { menu = scrim }
    }

    private fun closeMenu() {
        menu?.let { runCatching { windowManager.removeView(it) } }
        menu = null
    }

    // =======================================================================
    // Window plumbing and small builders
    // =======================================================================

    /**
     * Full-screen dim layer with [card] inside it. Tapping the dim closes the
     * window; the card swallows its own taps so it does not close itself.
     */
    private fun scrim(card: View, alignTop: Boolean, onDismiss: () -> Unit): View {
        val scrim = FrameLayout(service).apply {
            setBackgroundColor(Overlay.SCRIM)
            isClickable = true
            setOnClickListener { onDismiss() }
        }
        val wrapper = FrameLayout(service).apply {
            isClickable = true
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val margin = ui.px(16f)
        scrim.addView(
            wrapper,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = if (alignTop) {
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                } else {
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                leftMargin = margin
                rightMargin = margin
                topMargin = ui.px(52f)
                bottomMargin = ui.px(52f)
            },
        )
        return scrim
    }

    /**
     * Overlay windows must start at pixel 0,0 of the display, because that is
     * where the screenshot starts.
     *
     * FLAG_LAYOUT_NO_LIMITS alone used to be enough; from API 30 the window is
     * still laid out inside the system-bar insets unless it opts out of fitting
     * them, which on this device pushed every translated line down by the status
     * bar's height (96px) — the text landed on the line below its original.
     */
    private fun edgeToEdge(params: WindowManager.LayoutParams): WindowManager.LayoutParams =
        params.apply {
            fitInsetsTypes = 0
            fitInsetsSides = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun scrimParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }.let(::edgeToEdge)

    private companion object {
        const val BUBBLE_DP = 54f
        const val BUBBLE_START_TOP_DP = 132f
        const val LONG_PRESS_MS = 500L
        const val TAG = "VDubbingOverlay"
        const val BUSY_TEXT_ALPHA = 0.35f

        const val OUTLINE_WIDTH_DP = 1.1f
        const val MIN_TEXT_SIZE_SP = 10f
        const val MAX_TEXT_SIZE_SP = 34f
        const val GLYPH_RATIO = 0.66f
        const val REPLACE_GLYPH_RATIO = 0.85f
        const val PARALLEL_HEIGHT_GROWTH = 1.5f
    }
}
