package vn.onplay.vdubbing.presentation

import vn.onplay.vdubbing.domain.model.DisplayMode

/** Narrow port over the foreground service, so the ViewModel stays platform-free. */
interface ServiceController {
    fun start()
    fun hideOverlay()
    fun showBubble(onTranslate: () -> Unit)
    fun setDisplayMode(mode: DisplayMode)
    fun stopAudioTranslation()
    fun stop()
    fun applyAutoTranslate()
}
