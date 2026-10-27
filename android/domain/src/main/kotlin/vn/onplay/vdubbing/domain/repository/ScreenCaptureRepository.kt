package vn.onplay.vdubbing.domain.repository

import vn.onplay.vdubbing.domain.model.TextBlock

/**
 * What the domain needs from the device. Implemented in the data layer; the
 * domain knows nothing about Bitmap, Binder, or ML Kit.
 *
 * Every suspend function may throw TranslationException subtypes.
 */
interface ScreenCaptureRepository {
    /** False until the user enables the accessibility service in system settings. */
    fun isReady(): Boolean

    /**
     * Opaque handle to a screen capture. Only the OCR/cloud implementations can
     * interpret it, which keeps pixels out of the domain layer.
     */
    suspend fun capture(): CapturedScreen

    /** Pixel dimensions of the last capture, for overlay placement. */
    fun lastScreenSize(): ScreenSize

    /** Free the pixels behind a capture once nothing needs them. */
    fun release(screen: CapturedScreen)

    /**
     * Foreground app's package name, or null when it is unknown. Used to avoid
     * overlaying our own settings screen, which would cover the controls.
     */
    fun foregroundPackage(): String?

    fun showOverlay(blocks: List<TextBlock>, at: ScreenSize)
}
