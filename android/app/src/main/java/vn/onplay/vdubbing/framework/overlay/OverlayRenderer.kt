package vn.onplay.vdubbing.framework.overlay

import vn.onplay.vdubbing.domain.model.TextBlock

/** What the domain needs from the on-screen translation layer. */
interface OverlayRenderer {
    /** Add the overlay window, or make sure it can be added later. */
    fun attach()

    /** Draw a translation, replacing whatever is on screen. */
    fun show(blocks: List<TextBlock>, screenWidth: Int, screenHeight: Int)

    /** Remove the translation but keep the dismiss/trigger bubble. */
    fun hide()

    /**
     * Show the always-on bubble, independent of any translation.
     *
     * The bubble is the only way to start a translation while another app is in
     * front, and the only way to stop it from outside our own UI.
     */
    fun showBubble(onTranslate: () -> Unit)

    /** Remove the bubble, e.g. when the background service stops. */
    fun hideBubble()
}
