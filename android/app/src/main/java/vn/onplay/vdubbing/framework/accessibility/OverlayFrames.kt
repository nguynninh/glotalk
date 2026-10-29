package vn.onplay.vdubbing.framework.accessibility

import android.view.Choreographer
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal suspend fun awaitOverlayFrames() = withContext(Dispatchers.Main.immediate) {
    repeat(2) {
        suspendCancellableCoroutine { continuation ->
            val choreographer = Choreographer.getInstance()
            val frame = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
            continuation.invokeOnCancellation { choreographer.removeFrameCallback(frame) }
            choreographer.postFrameCallback(frame)
        }
    }
}
