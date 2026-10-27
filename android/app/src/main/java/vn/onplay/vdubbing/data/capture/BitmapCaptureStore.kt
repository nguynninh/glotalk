package vn.onplay.vdubbing.data.capture

import android.graphics.Bitmap
import vn.onplay.vdubbing.domain.repository.CapturedScreen

/**
 * The pixel side of a capture, kept in the data layer.
 *
 * The domain only ever holds the opaque [CapturedScreen] id; implementations
 * look the bitmap up here and recycle it once the request finishes.
 */
class BitmapCaptureStore {

    private val frames = mutableMapOf<String, Frame>()
    private var counter = 0

    class Frame(val bitmap: Bitmap, val width: Int, val height: Int)

    fun put(bitmap: Bitmap): CapturedScreen {
        val id = "capture-${counter++}"
        frames[id] = Frame(bitmap, bitmap.width, bitmap.height)
        return CapturedScreen(id)
    }

    fun frame(screen: CapturedScreen): Frame? = frames[screen.id]

    /** Frees the pixels of one frame; safe to call twice. */
    fun release(screen: CapturedScreen) {
        frames.remove(screen.id)?.bitmap?.recycle()
    }

    /** Drops every frame, e.g. when the service is destroyed. */
    fun clear() {
        frames.values.forEach { it.bitmap.recycle() }
        frames.clear()
    }
}
