package vn.onplay.vdubbing.di

import android.content.Context
import android.graphics.Bitmap
import vn.onplay.vdubbing.data.capture.BitmapCaptureStore
import vn.onplay.vdubbing.data.local.ConnectivityRepositoryImpl
import vn.onplay.vdubbing.data.local.SettingsRepositoryImpl
import vn.onplay.vdubbing.data.mlkit.OcrRepositoryImpl
import vn.onplay.vdubbing.data.mlkit.TranslationRepositoryImpl
import vn.onplay.vdubbing.data.remote.GeminiTranslationRepository
import vn.onplay.vdubbing.data.repository.ScreenCaptureRepositoryImpl
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.TextBlock
import vn.onplay.vdubbing.domain.usecase.TranslateScreenUseCase
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import vn.onplay.vdubbing.framework.accessibility.ScreenCaptureSource
import vn.onplay.vdubbing.framework.overlay.OverlayRenderer

/**
 * Hand-rolled composition root — one object, because the graph is a dozen nodes
 * and adding Hilt to an app this size buys nothing but a build step.
 *
 * The overlay renderer depends on the accessibility service, which may not be
 * connected yet, so it is resolved lazily through the bridge.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settings = SettingsRepositoryImpl(appContext)
    val captureStore = BitmapCaptureStore()

    val ocr = OcrRepositoryImpl(captureStore)
    val translation = TranslationRepositoryImpl()
    val gemini = GeminiTranslationRepository(captureStore, settings)
    val connectivity = ConnectivityRepositoryImpl(appContext)

    // The accessibility service may connect or disconnect at any time, so both
    // adapters resolve it per call rather than caching it at construction.
    private val overlay = object : OverlayRenderer {
        override fun attach() = Unit

        override fun show(blocks: List<TextBlock>, screenWidth: Int, screenHeight: Int) {
            AccessibilityBridge.service?.overlayRenderer?.show(blocks, screenWidth, screenHeight)
        }

        override fun hide() {
            AccessibilityBridge.service?.overlayRenderer?.hide()
        }

        override val hasTranslation: Boolean
            get() = AccessibilityBridge.service?.overlayRenderer?.hasTranslation == true

        override val translationShownTime: Long
            get() = AccessibilityBridge.service?.overlayRenderer?.translationShownTime ?: 0L

        override fun showBubble(onTranslate: () -> Unit) {
            AccessibilityBridge.service?.overlayRenderer?.showBubble(onTranslate)
        }

        override fun hideBubble() {
            AccessibilityBridge.service?.overlayRenderer?.hideBubble()
        }

        override fun setBusy(busy: Boolean) {
            AccessibilityBridge.service?.overlayRenderer?.setBusy(busy)
        }

        override fun setDisplayMode(mode: DisplayMode) {
            AccessibilityBridge.service?.overlayRenderer?.setDisplayMode(mode)
        }

        override fun refresh() {
            AccessibilityBridge.service?.overlayRenderer?.refresh()
        }

        override fun dismissTransientWindows() {
            AccessibilityBridge.service?.overlayRenderer?.dismissTransientWindows()
        }
    }

    private val screenCapture = ScreenCaptureRepositoryImpl(
        source = object : ScreenCaptureSource {
            override fun isReady(): Boolean = AccessibilityBridge.isReady

            override suspend fun capture(): Bitmap =
                AccessibilityBridge.service?.capture()
                    ?: throw IllegalStateException("accessibility service not connected")

            override fun foregroundPackage(): String? =
                AccessibilityBridge.service?.foregroundPackage()
        },
        overlay = overlay,
        store = captureStore,
    )

    val translateScreen = TranslateScreenUseCase(
        capture = screenCapture,
        ocr = ocr,
        translator = translation,
        cloud = gemini,
        connectivity = connectivity,
    )

    /** Frees OCR/translate models and any undisplayed screenshots. */
    fun releaseEngines() {
        ocr.close()
        translation.close()
        captureStore.clear()
    }
}
