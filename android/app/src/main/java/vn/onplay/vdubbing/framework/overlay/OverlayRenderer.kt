package vn.onplay.vdubbing.framework.overlay

import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock

/** What the domain needs from the on-screen translation layer. */
interface OverlayRenderer {
    /** Add the overlay window, or make sure it can be added later. */
    fun attach()

    /** Draw a translation, replacing whatever is on screen. */
    fun show(blocks: List<TextBlock>, screenWidth: Int, screenHeight: Int)

    /** Remove the translation but keep the dismiss/trigger bubble. */
    fun hide()

    /** Whether translated lines are on screen right now. */
    val hasTranslation: Boolean

    /** Timestamp (SystemClock.uptimeMillis) when the current translation was shown, or 0. */
    val translationShownTime: Long

    /**
     * Show the always-on bubble, independent of any translation.
     *
     * The bubble is the only way to start a translation while another app is in
     * front, and the only way to stop it from outside our own UI. A tap opens
     * the panel; [onTranslate] is the fallback its start button runs when no
     * [OverlayHost] is attached.
     */
    fun showBubble(onTranslate: () -> Unit)

    /** Remove the bubble, e.g. when the background service stops. */
    fun hideBubble()

    /** Show or hide the progress ring on the bubble. */
    fun setBusy(busy: Boolean)

    /** Draw the current translation the other way round (parallel/replace). */
    fun setDisplayMode(mode: DisplayMode)

    /** Redraw open windows after a language or preference change. */
    fun refresh()

    /**
     * Close the panel, menu and previous translation before taking a screenshot.
     *
     * takeScreenshot() captures the whole display, our own overlays included.
     * The panel is full of interface text in the *app's* language, so leaving it
     * up made an English page read as Vietnamese, and since the target was also
     * Vietnamese the translation came back unchanged and nothing was drawn.
     */
    fun dismissTransientWindows()
}

/**
 * What the floating windows need from the service behind them.
 *
 * Implemented by the foreground service, which owns the settings and the
 * translation pipeline; the windows only draw and report what was chosen.
 */
interface OverlayHost {
    /** Settings as they stand right now, read each time the panel is opened. */
    fun panelState(): PanelState

    fun onSourceLanguage(language: Language)

    fun onTargetLanguage(language: Language)

    fun onDisplayMode(mode: DisplayMode)

    fun onAutoTranslate(enabled: Boolean)

    fun onAudioTranslation(enabled: Boolean)
    fun onOriginalVolume(volume: Int)
    fun onTranslationVolume(volume: Int)

    /** "Bắt đầu dịch" — translate the screen now. */
    fun onStartTranslation()

    /** Stop the translation: hide what is on screen, or abandon one in flight. */
    fun onStopTranslation()

    /** "Cài đặt" — bring our own settings screen to the front. */
    fun onOpenAppSettings()

    /** "Tắt" — stop the background service and remove the bubble. */
    fun onStopService()
}

/**
 * The values the panel draws, and the state its buttons reflect. Every field
 * has a default so the renderer can draw a usable panel even with no service
 * attached (the accessibility service can outlive it by a moment).
 */
data class PanelState(
    val source: Language = Language.AUTO,
    val target: Language = Language.VIETNAMESE,
    val displayMode: DisplayMode = DisplayMode.REPLACE,
    val autoTranslate: Boolean = false,
    val translating: Boolean = false,
    val hasTranslation: Boolean = false,
    val audioActive: Boolean = false,
    val audioStatusRes: Int = 0,
    val originalVolume: Int = 100,
    val translationVolume: Int = 100,
)
