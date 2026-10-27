package vn.onplay.vdubbing.data.repository

import kotlinx.coroutines.CancellationException
import vn.onplay.vdubbing.data.capture.BitmapCaptureStore
import vn.onplay.vdubbing.domain.model.TextBlock
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.CapturedScreen
import vn.onplay.vdubbing.domain.repository.ScreenCaptureRepository
import vn.onplay.vdubbing.domain.repository.ScreenSize
import vn.onplay.vdubbing.framework.accessibility.ScreenCaptureSource
import vn.onplay.vdubbing.framework.overlay.OverlayRenderer

/**
 * Bridges the accessibility service's screenshot/overlay powers to the domain.
 * Both come in as interfaces so this class is testable and the framework layer
 * stays replaceable.
 */
class ScreenCaptureRepositoryImpl(
    private val source: ScreenCaptureSource,
    private val overlay: OverlayRenderer,
    private val store: BitmapCaptureStore,
) : ScreenCaptureRepository {

    private var lastSize = ScreenSize(0, 0)

    override fun isReady(): Boolean = source.isReady()

    override suspend fun capture(): CapturedScreen {
        val bitmap = try {
            source.capture()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            throw TranslationException.CaptureFailed(e)
        }
        lastSize = ScreenSize(bitmap.width, bitmap.height)
        return store.put(bitmap)
    }

    override fun lastScreenSize(): ScreenSize = lastSize

    override fun release(screen: CapturedScreen) = store.release(screen)

    override fun foregroundPackage(): String? = source.foregroundPackage()

    override fun showOverlay(blocks: List<TextBlock>, at: ScreenSize) {
        overlay.show(blocks, at.width, at.height)
    }
}
