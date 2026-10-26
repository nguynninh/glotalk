package vn.onplay.vdubbing.framework.accessibility

import android.graphics.Bitmap
import java.lang.ref.WeakReference

/** The part of the accessibility service the data layer is allowed to use. */
interface ScreenCaptureSource {
    fun isReady(): Boolean
    suspend fun capture(): Bitmap

    /** Package in front, or null when the service cannot tell. */
    fun foregroundPackage(): String?
}

/**
 * Process-wide handle to the running accessibility service.
 *
 * Only one accessibility service can be connected per app and it outlives any
 * bound service, so a weak singleton is the right shape. Weak because the
 * accessibility service must be free to die without anything holding it.
 */
object AccessibilityBridge {

    @Volatile
    private var ref: WeakReference<ScreenReaderAccessibilityService>? = null

    fun attach(service: ScreenReaderAccessibilityService) {
        ref = WeakReference(service)
        onAttached?.invoke()
        onServiceAvailable?.invoke()
    }

    fun detach(service: ScreenReaderAccessibilityService) {
        if (ref?.get() === service) {
            ref = null
            onDetached?.invoke()
        }
    }

    val service: ScreenReaderAccessibilityService? get() = ref?.get()

    val isReady: Boolean get() = service != null

    /** Fired when the user flips the accessibility toggle, so UI can refresh. */
    @Volatile
    var onAttached: (() -> Unit)? = null

    /**
     * Fired on every service (re)connect. Overlay windows belong to one service
     * instance, so anything drawn on them must be re-created here.
     */
    @Volatile
    var onServiceAvailable: (() -> Unit)? = null

    @Volatile
    var onDetached: (() -> Unit)? = null
}
