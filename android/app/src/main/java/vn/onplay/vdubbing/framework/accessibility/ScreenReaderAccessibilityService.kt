package vn.onplay.vdubbing.framework.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.framework.locale.AppLocaleController
import vn.onplay.vdubbing.framework.overlay.AccessibilityOverlayRenderer
import vn.onplay.vdubbing.framework.audio.AudioTranslationService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns the two capabilities only an accessibility service has: takeScreenshot()
 * and TYPE_ACCESSIBILITY_OVERLAY windows.
 *
 * Deliberately thin — it exposes those powers and forwards window changes. All
 * OCR, translation and placement decisions live in the layers above.
 */
class ScreenReaderAccessibilityService : AccessibilityService(), ScreenCaptureSource {

    /** Created on connect; the composition root reaches it through here. */
    var overlayRenderer: AccessibilityOverlayRenderer? = null
        private set

    /** Set by whoever wants auto-translate; cleared on unbind. */
    @Volatile
    var onWindowChanged: (() -> Unit)? = null

    /** Invoked when screen scrolls underneath, e.g. in Chrome, ListView, WebView. */
    @Volatile
    var onScreenScrolled: (() -> Unit)? = null

    @Volatile
    private var lastForegroundPackage: String? = null

    /** Last connect failure, surfaced in the app's diagnostics instead of dying silently. */
    @Volatile
    var connectionFailure: String? = null
        private set

    /** The bubble's label must follow a language change without rebinding the service. */
    private val localeListener: (AppLanguage) -> Unit = {
        overlayRenderer?.refreshContentDescription()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleController.wrap(base))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Nothing in here may throw: an exception out of onServiceConnected makes
        // Android mark the service "not working properly" and stop rebinding it,
        // which looks to the user like the toggle is on but nothing happens.
        // Every step is individually recoverable, so each one is guarded.
        try {
            overlayRenderer = AccessibilityOverlayRenderer(this)
            AppLocaleController.addListener(localeListener)
        } catch (t: Throwable) {
            recordFailure("overlay", t)
        }

        try {
            AccessibilityBridge.attach(this)
        } catch (t: Throwable) {
            recordFailure("bridge", t)
        }

        connectionFailure = null
    }

    private fun recordFailure(stage: String, error: Throwable) {
        connectionFailure = "$stage: ${error.javaClass.simpleName}: ${error.message}"
        Log.w(TAG, "accessibility $stage failed", error)
    }

    internal fun recordRuntimeFailure(stage: String, error: Throwable) = recordFailure(stage, error)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return
        // Our own overlay windows — bubble, panel, menu — are windows too, and
        // they fire this event with our package. Recording them made the app look
        // like the foreground app right after the panel closed, so the next
        // translation decided it was covering our own screen and drew nothing.
        // Our activity is tracked by [AppForeground] instead, which an overlay
        // window cannot enter.
        if (packageName == this.packageName) return

        when (ev.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                try {
                    onScreenScrolled?.invoke()
                } catch (t: Throwable) {
                    recordFailure("screenScrolled", t)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastForegroundPackage = applicationPackage() ?: lastForegroundPackage
                // A throw on this path is attributed to the service and gets it disabled
                // by the system, so a callback bug must not escape here.
                try {
                    onWindowChanged?.invoke()
                } catch (t: Throwable) {
                    recordFailure("windowChanged", t)
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        AudioTranslationService.stop(this)
        onWindowChanged = null
        onScreenScrolled = null
        AppLocaleController.removeListener(localeListener)
        overlayRenderer?.hide()
        overlayRenderer = null
        AccessibilityBridge.detach(this)
    }

    override fun isReady(): Boolean = true

    override fun foregroundPackage(): String? =
        if (AppForeground.isOurs) packageName else applicationPackage() ?: lastForegroundPackage

    private fun applicationPackage(): String? {
        val applications = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = applications.firstOrNull { it.isFocused }
            ?: applications.firstOrNull { it.isActive }
            ?: applications.maxByOrNull { it.layer }
            ?: return null
        val root = window.root ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    /**
     * Screenshots arrive as hardware bitmaps, which ML Kit cannot read. Copy to
     * ARGB_8888 and close the hardware buffer immediately so the compositor
     * gets its memory back.
     */
    override suspend fun capture(): Bitmap {
        overlayRenderer?.setBubbleVisible(false)
        try {
            awaitOverlayFrames()
            return captureDisplay()
        } finally {
            overlayRenderer?.setBubbleVisible(true)
        }
    }

    private suspend fun captureDisplay(): Bitmap = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val software = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        buffer.close()
                    }
                    if (software == null) {
                        cont.resumeWithException(IllegalStateException("screenshot had no pixels"))
                    } else {
                        cont.resume(software) { _, bitmap, _ -> bitmap.recycle() }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    val error = IllegalStateException("takeScreenshot failed: $errorCode")
                    recordFailure("capture", error)
                    cont.resumeWithException(error)
                }
            },
        )
    }

    private companion object {
        const val TAG = "VDubbingA11y"
    }
}
