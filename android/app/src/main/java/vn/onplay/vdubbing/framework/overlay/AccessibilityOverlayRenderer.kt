package vn.onplay.vdubbing.framework.overlay

import android.accessibilityservice.AccessibilityService
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Switch
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

    /** Set by the foreground service; null while it is not running. */
    var host: OverlayHost? = null

    /** Whether translated lines are on screen right now. */
    override val hasTranslation: Boolean get() = textBlocks.isNotEmpty()

    override var translationShownTime: Long = 0L
        private set

    // --- translation text --------------------------------------------------
    private var textRoot: FrameLayout? = null
    private var textBlocks: List<TextBlock> = emptyList()
    private var textScreenWidth = 0
    private var textScreenHeight = 0
    private var textMode: DisplayMode = DisplayMode.REPLACE
    /** What the open panel was built from, so a redraw keeps the language picker open. */
    private var panelState: PanelState? = null
    /** The language whose choices are showing, or null when the picker is closed. */
    private var picker: Picker? = null
    private var pickerStrip: View? = null

    private enum class Picker { SOURCE, TARGET }

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
    private var panelCard: LinearLayout? = null
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
        panelState = null
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
    }

    override fun refresh() {
        refreshContentDescription()
        if (textBlocks.isNotEmpty()) drawTranslation()
        // The panel and the menu are built from strings, so they are rebuilt
        // rather than patched when the interface language changes.
        if (panel != null) openPanel()
        if (menu != null) openMenu()
    }

    private fun drawTranslation() {
        removeTextWindow()
        if (textBlocks.isEmpty()) return
        if (textScreenWidth <= 0 || textScreenHeight <= 0) return

        val container = FrameLayout(service)
        val rows = VerticalBands.of(textBlocks, ::shouldDraw, textScreenHeight)
        val columns = HorizontalBands.of(textBlocks, ::shouldDraw, textScreenWidth)
        val skipped = textBlocks.indices.count { index ->
            addLine(container, textBlocks[index], rows[index], columns[index]).not()
        }
        if (skipped > 0) {
            Log.w(TAG, "skipped $skipped/${textBlocks.size} lines (no translation or same as original)")
        }
        if (container.childCount == 0) {
            val sample = textBlocks.take(3).joinToString { it.original.take(24) }
            Log.w(TAG, "nothing to draw for ${textBlocks.size} blocks, e.g. [$sample]")
            return
        }

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
            .onSuccess { textRoot = container }
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
        val text = block.text.trim()
        return text.isNotEmpty() && text != block.original.trim()
    }

    /** @return true when the line was drawn. */
    private fun addLine(
        container: FrameLayout,
        block: TextBlock,
        row: Band,
        column: Band,
    ): Boolean {
        if (!shouldDraw(block)) return false
        val text = block.text.trim()

        val replace = textMode == DisplayMode.REPLACE
        val preferredSize = textSizeFor(block)
        val slack = ui.px(OUTLINE_SLACK_DP)

        // Horizontal and vertical padding for plate to safely cover original glyphs
        val platePadH = if (replace) ui.px(PLATE_PAD_DP) else 0
        val platePadV = if (replace) ui.px(PLATE_PAD_V_DP) else 0

        // The column is the hard limit: a nav label whose translation runs long
        // must wrap or shrink, not spread over the labels beside it. The gutter
        // on each side keeps a grown block a visible step away from whatever the
        // band shares its edge with, so the two never read as one run of text.
        val gutter = ui.px(BAND_GUTTER_DP)
        val room = (column.end - column.start - gutter * 2).coerceAtLeast(1)
        val width = (block.width + platePadH * 2)
            .coerceIn(ui.px(MIN_TEXT_WIDTH_DP), textScreenWidth)
            .coerceAtMost(room)
        val textWidth = (width - platePadH * 2).coerceAtLeast(ui.px(8f))

        val heightRoom = (row.end - row.start).coerceAtLeast(1)
        val maxHeight = if (replace) {
            (block.height * REPLACE_HEIGHT_RATIO).toInt()
                .coerceAtLeast((preferredSize * 1.3f).toInt())
                .coerceAtMost(heightRoom)
        } else {
            (block.height * PARALLEL_HEIGHT_GROWTH).toInt()
                .coerceAtLeast((preferredSize * 1.6f).toInt())
                .coerceAtMost(heightRoom)
        }

        val left = (block.left - platePadH)
            .coerceIn(
                column.start + gutter,
                (column.end - width - gutter).coerceAtLeast(column.start + gutter),
            )
            .coerceIn(0, (textScreenWidth - width).coerceAtLeast(0))

        val isParagraph = block.lineCount > 1 || block.original.length > 40

        val view = OutlinedTextView(service).apply {
            outlineColor = OUTLINE_COLOR
            // Replace has no outline: a stroke on every glyph over a white plate
            // reads as bold, not as text. Parallel keeps it, because bare white
            // text needs something to separate it from the app behind it.
            outlineWidth = if (replace) 0f else ui.px(OUTLINE_WIDTH_DP).toFloat()
            fillColor = if (replace) PLATE_TEXT_COLOR else Color.WHITE
            verticalSlack = if (replace) 0 else slack
            plate = if (replace) {
                Plate(
                    color = PLATE_COLOR,
                    cornerRadius = ui.px(PLATE_RADIUS_DP).toFloat(),
                    inset = 0,
                )
            } else {
                null
            }
            if (replace) {
                fontWeight = if (!isParagraph && preferredSize > ui.sp(20f)) 700 else 500
            }
        }
        val contentHeight = view.bind(
            text = text,
            maxWidth = textWidth,
            maxHeight = maxHeight,
            preferredSize = preferredSize,
            minSize = preferredSize * MIN_SIZE_RATIO,
            isParagraph = isParagraph,
        )

        val naturalHeight = if (replace) {
            // Must cover at least the original block plus vertical padding to hide ascenders/descenders
            maxOf(block.height + platePadV * 2, contentHeight + platePadV * 2)
        } else {
            contentHeight + view.verticalSlack
        }
        val viewHeight = naturalHeight.coerceAtMost(heightRoom)

        val top = if (replace) {
            block.top + block.height / 2 - viewHeight / 2
        } else {
            block.top + block.height - contentHeight / 4
        }.coerceIn(row.start, (row.end - viewHeight).coerceAtLeast(row.start))

        container.addView(
            view,
            FrameLayout.LayoutParams(width, viewHeight).apply {
                leftMargin = left
                topMargin = top
            },
        )
        return true
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
        picker = null
        closePanel()
        closeMenu()
        val state = host?.panelState() ?: PanelState()
        panelState = state
        val card = buildPanelCard(state)
        val scrim = scrim(card, alignTop = bubbleAtTop()) { closePanel() }
        runCatching { windowManager.addView(scrim, scrimParams()) }
            .onSuccess {
                panel = scrim
                panelCard = card
            }
    }

    private fun closePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelCard = null
        panelState = null
        picker = null
        pickerStrip = null
    }

    private fun buildPanelCard(state: PanelState): LinearLayout {
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Overlay.SURFACE, 24f)
            elevation = ui.px(8f).toFloat()
            setPadding(ui.px(20f), ui.px(18f), ui.px(20f), ui.px(18f))
        }

        // Header: title, then the close that the scrim would also do.
        card.addView(header(ui.string(R.string.panel_title)) { closePanel() })
        card.addView(gap(16f))

        // Language pair. A strip of choices replaces the two chips in place
        // after a tap — no second window, which would fight this one for focus.
        card.addView(
            LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    languageChip(state.source) {
                        openLanguagePicker(card, state.source, Language.SOURCES, isSource = true)
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
                addView(
                    ui.icon(R.drawable.ic_overlay_swap, 20f, Overlay.MUTED).apply {
                        setPadding(ui.px(8f), 0, ui.px(8f), 0)
                        contentDescription = ui.string(R.string.panel_swap_languages)
                        isClickable = true
                        setOnClickListener {
                            // Swapping a detected source makes no sense: there is
                            // nothing to name on the other side.
                            if (state.source != Language.AUTO) {
                                host?.onSourceLanguage(state.target)
                                host?.onTargetLanguage(state.source)
                            }
                            refreshPanel()
                        }
                    },
                )
                addView(
                    languageChip(state.target) {
                        openLanguagePicker(card, state.target, Language.TARGETS, isSource = false)
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
            },
        )

        // Re-open the picker that was showing, so rebuilding the panel after a
        // pick does not close it under the user's finger.
        when (picker) {
            Picker.SOURCE ->
                openLanguagePicker(card, state.source, Language.SOURCES, isSource = true)
            Picker.TARGET ->
                openLanguagePicker(card, state.target, Language.TARGETS, isSource = false)
            null -> Unit
        }

        card.addView(gap(16f))
        card.addView(divider())
        card.addView(gap(4f))

        // Realtime translation.
        card.addView(
            LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.px(58f)
                addView(
                    column(
                        ui.text(ui.string(R.string.panel_realtime_title), 16f, Overlay.TEXT),
                        ui.text(
                            ui.string(R.string.panel_realtime_subtitle),
                            12f,
                            Overlay.MUTED,
                            maxLines = 1,
                        ),
                    ),
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
                addView(
                    Switch(service).apply {
                        isChecked = state.autoTranslate
                        // Redrawn after the change so the subtitle reflects it;
                        // rebuilding from the event would re-enter this switch.
                        setOnCheckedChangeListener { _, checked ->
                            host?.onAutoTranslate(checked)
                            post { refreshPanel() }
                        }
                    },
                )
            },
        )

        card.addView(divider())
        card.addView(gap(14f))

        // Display mode.
        card.addView(ui.text(ui.string(R.string.panel_mode_title), 15f, Overlay.TEXT, bold = true))
        card.addView(gap(10f))
        card.addView(
            LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    modeCard(
                        iconRes = R.drawable.ic_overlay_parallel,
                        title = ui.string(R.string.panel_mode_parallel),
                        subtitle = ui.string(R.string.panel_mode_parallel_desc),
                        selected = state.displayMode == DisplayMode.PARALLEL,
                    ) {
                        host?.onDisplayMode(DisplayMode.PARALLEL)
                        refreshPanel()
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        rightMargin = ui.px(6f)
                    },
                )
                addView(
                    modeCard(
                        iconRes = R.drawable.ic_overlay_replace,
                        title = ui.string(R.string.panel_mode_replace),
                        subtitle = ui.string(R.string.panel_mode_replace_desc),
                        selected = state.displayMode == DisplayMode.REPLACE,
                    ) {
                        host?.onDisplayMode(DisplayMode.REPLACE)
                        refreshPanel()
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        leftMargin = ui.px(6f)
                    },
                )
            },
        )

        card.addView(gap(6f))
        card.addView(
            ui.menuRow(
                iconRes = R.drawable.ic_overlay_settings,
                label = ui.string(R.string.panel_advanced),
            ) {
                closePanel()
                host?.onOpenAppSettings()
            }.apply { setPadding(0, 0, 0, 0) },
        )
        card.addView(gap(10f))

        // Primary action: start, or a progress label while one is running.
        card.addView(
            ui.text(
                if (state.translating) {
                    ui.string(R.string.settings_translating)
                } else {
                    ui.string(R.string.panel_start)
                },
                16f,
                Color.WHITE,
                bold = true,
                maxLines = 1,
                gravity = Gravity.CENTER,
            ).apply {
                gravity = Gravity.CENTER
                background = ui.rounded(Overlay.BLUE, 26f)
                minHeight = ui.px(52f)
                isClickable = true
                isEnabled = !state.translating
                alpha = if (state.translating) 0.7f else 1f
                setOnClickListener {
                    closePanel()
                    host?.onStartTranslation() ?: onTranslateTap?.invoke()
                }
            },
        )
        return card
    }

    /**
     * Swaps in a horizontal strip of language choices under the chips, so the
     * card grows wider than the window rather than being covered by a second
     * one. Re-opening the panel after a pick rebuilds it in the chosen language.
     */
    private fun openLanguagePicker(
        card: LinearLayout,
        selected: Language,
        choices: List<Language>,
        isSource: Boolean,
    ) {
        picker = if (isSource) Picker.SOURCE else Picker.TARGET
        // Only one strip: a second tap on the other chip moves it, not stacks it.
        (pickerStrip?.parent as? android.view.ViewGroup)?.removeView(pickerStrip)
        pickerStrip = null
        val strip = HorizontalScrollView(service).apply {
            isHorizontalScrollBarEnabled = false
            background = ui.rounded(Overlay.CHIP, 14f)
            setPadding(ui.px(6f), ui.px(6f), ui.px(6f), ui.px(6f))
            addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    choices.forEach { language ->
                        addView(languageOption(language, language == selected) {
                            if (isSource) host?.onSourceLanguage(language)
                            else host?.onTargetLanguage(language)
                            refreshPanel()
                        })
                    }
                },
            )
        }
        // After the header, the gap and the chip row.
        card.addView(
            strip,
            PICKER_INDEX.coerceAtMost(card.childCount),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ui.px(12f) },
        )
        pickerStrip = strip
    }

    private fun languageOption(language: Language, selected: Boolean, onClick: () -> Unit): View =
        ui.text(
            language.pickerLabel(),
            13f,
            if (selected) Color.WHITE else Overlay.TEXT,
            bold = selected,
            maxLines = 1,
        ).apply {
            background = ui.rounded(if (selected) Overlay.BLUE else Color.TRANSPARENT, 10f)
            setPadding(ui.px(14f), ui.px(9f), ui.px(14f), ui.px(9f))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun languageChip(language: Language, onClick: () -> Unit): View =
        LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(Overlay.CHIP, 14f)
            setPadding(ui.px(10f), ui.px(10f), ui.px(10f), ui.px(10f))
            isClickable = true
            addView(
                ui.text(OverlayUi.badge(language), 10f, Overlay.BLUE, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = ui.rounded(Overlay.BLUE_SOFT, 6f)
                    minWidth = ui.px(26f)
                    setPadding(ui.px(5f), ui.px(3f), ui.px(5f), ui.px(3f))
                },
            )
            addView(
                ui.text(language.pickerLabel(), 14f, Overlay.TEXT, bold = true, maxLines = 1)
                    .apply {
                        setPadding(ui.px(8f), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    },
            )
            addView(ui.icon(R.drawable.ic_overlay_chevron, 16f, Overlay.MUTED))
            setOnClickListener { onClick() }
        }

    /**
     * Rebuilds the panel in place after a choice, keeping whatever picker was
     * open. Opening a second window instead would replace this one mid-tap.
     */
    private fun refreshPanel() {
        if (panel == null) return
        val state = host?.panelState() ?: return
        panelState = state
        // The old card must stop being panelCard first, or buildPanelCard would
        // add a re-opened picker strip to the card being thrown away.
        panelCard = null
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
        panelCard = card
    }

    private fun panelContent(): android.view.ViewGroup? =
        (panel as? FrameLayout)?.getChildAt(0) as? android.view.ViewGroup

    private fun modeCard(
        iconRes: Int,
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): View = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = ui.rounded(
            if (selected) Overlay.BLUE_SOFT else Overlay.SURFACE,
            14f,
            if (selected) 2f else 1f,
            if (selected) Overlay.BLUE else Overlay.BORDER,
        )
        setPadding(ui.px(10f), ui.px(14f), ui.px(10f), ui.px(14f))
        isClickable = true
        addView(ui.icon(iconRes, 22f, if (selected) Overlay.BLUE else Overlay.MUTED))
        addView(
            ui.text(title, 14f, Overlay.TEXT, bold = true, gravity = Gravity.CENTER)
                .apply { setPadding(0, ui.px(8f), 0, 0) },
        )
        addView(
            ui.text(subtitle, 11f, Overlay.MUTED, gravity = Gravity.CENTER, maxLines = 2)
                .apply { setPadding(0, ui.px(3f), 0, 0) },
        )
        setOnClickListener { onClick() }
    }

    // =======================================================================
    // Quick menu
    // =======================================================================

    private fun openMenu() {
        closeMenu()
        closePanel()
        val state = host?.panelState() ?: PanelState()
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Overlay.SURFACE, 20f)
            elevation = ui.px(8f).toFloat()
            setPadding(ui.px(6f), ui.px(8f), ui.px(6f), ui.px(8f))
        }

        // Identity and status: which app this bubble belongs to, and what it is
        // doing right now — the bubble itself has room for neither.
        card.addView(
            LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.px(12f), ui.px(10f), ui.px(12f), ui.px(14f))
                addView(ui.bubbleAvatar(14f, ui.px(40f)))
                addView(
                    column(
                        ui.text(ui.string(R.string.panel_title), 16f, Overlay.TEXT, bold = true),
                        ui.text(
                            ui.string(
                                when {
                                    state.translating -> R.string.menu_status_translating
                                    state.hasTranslation -> R.string.menu_status_active
                                    else -> R.string.menu_status_idle
                                },
                            ),
                            12f,
                            Overlay.MUTED,
                            maxLines = 1,
                        ),
                    ).apply { setPadding(ui.px(12f), 0, 0, 0) },
                )
            },
        )
        card.addView(divider(inset = 6f))

        // Pause is the contextual action: it is what the menu is for while a
        // translation is on screen, and it is inert otherwise.
        card.addView(
            ui.menuRow(
                iconRes = if (state.hasTranslation) {
                    R.drawable.ic_overlay_pause
                } else {
                    R.drawable.ic_overlay_translate
                },
                label = ui.string(
                    if (state.hasTranslation) R.string.menu_pause else R.string.panel_start,
                ),
            ) {
                closeMenu()
                if (state.hasTranslation) hide() else host?.onStartTranslation()
            },
        )
        card.addView(
            ui.menuRow(
                iconRes = R.drawable.ic_overlay_language,
                label = ui.string(R.string.menu_language),
            ) {
                closeMenu()
                openPanel()
            },
        )
        card.addView(
            ui.menuRow(
                iconRes = R.drawable.ic_overlay_settings,
                label = ui.string(R.string.panel_advanced),
            ) {
                closeMenu()
                host?.onOpenAppSettings()
            },
        )
        card.addView(
            ui.menuRow(
                iconRes = R.drawable.ic_overlay_hide,
                label = ui.string(R.string.menu_hide_bubble),
            ) {
                closeMenu()
                hideBubble()
            },
        )
        card.addView(
            ui.menuRow(
                iconRes = R.drawable.ic_overlay_exit,
                label = ui.string(R.string.menu_exit),
            ) {
                closeMenu()
                host?.onStopService()
            },
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

    private fun header(title: String, onClose: () -> Unit): View =
        LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ui.text(title, 20f, Overlay.TEXT, bold = true)
                    .apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) },
            )
            addView(
                ui.icon(R.drawable.ic_overlay_close, 20f, Overlay.MUTED).apply {
                    contentDescription = ui.string(R.string.action_close)
                    val pad = ui.px(8f)
                    setPadding(pad, pad, pad, pad)
                    isClickable = true
                    setOnClickListener { onClose() }
                },
            )
        }

    private fun gap(dp: Float): View = View(service).apply {
        layoutParams = LinearLayout.LayoutParams(1, ui.px(dp))
    }

    private fun divider(inset: Float = 0f): View = View(service).apply {
        setBackgroundColor(Overlay.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ui.px(1f),
        ).apply {
            leftMargin = ui.px(inset)
            rightMargin = ui.px(inset)
        }
    }

    private fun column(vararg children: View): LinearLayout = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it) }
    }

    /**
     * Language names in the chips are endonyms — a language is always written
     * in itself — except AUTO, which describes the app's behaviour rather than
     * a language and therefore follows the interface language.
     */
    private fun Language.pickerLabel(): String =
        if (this == Language.AUTO) ui.string(R.string.language_auto) else label

    private companion object {
        /** Slot the picker strip takes: after the header, its gap and the chip row. */
        const val PICKER_INDEX = 3

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
        const val MIN_SIZE_RATIO = 0.5f
        const val MIN_TEXT_WIDTH_DP = 28f
        /** Room for the outline and a slightly longer word either side. */
        const val OUTLINE_SLACK_DP = 6f
        const val PLATE_PAD_DP = 6f
        /** Clear space kept between a grown block and the edge of its band. */
        const val BAND_GUTTER_DP = 2f
        const val PLATE_PAD_V_DP = 2f
        const val PLATE_RADIUS_DP = 2f
        const val REPLACE_HEIGHT_RATIO = 1.6f
        const val PARALLEL_HEIGHT_GROWTH = 1.5f

        /**
         * Pure white on purpose. A near-white plate left a faint grey tint over
         * the page that read as a stain rather than as paper; the plate only has
         * to hide the original words, so it may as well match white pages.
         */
        val PLATE_COLOR = 0xFFFFFFFF.toInt()
        val PLATE_TEXT_COLOR = 0xFF202124.toInt()
        /** Dark rim around every glyph in parallel mode — its separation from the app. */
        val OUTLINE_COLOR = 0xE6000000.toInt()
    }
}
