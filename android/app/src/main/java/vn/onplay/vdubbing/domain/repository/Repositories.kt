package vn.onplay.vdubbing.domain.repository

import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.Script
import vn.onplay.vdubbing.domain.model.TextBlock
import vn.onplay.vdubbing.domain.model.ThemeMode
import vn.onplay.vdubbing.domain.model.Translation

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

    fun hideOverlay()

    fun showOverlay(blocks: List<TextBlock>, at: ScreenSize)
}

/** A captured frame, referenced but not decoded by the domain. */
class CapturedScreen(val id: String)

data class ScreenSize(val width: Int, val height: Int)

interface OcrRepository {
    suspend fun recognize(screen: CapturedScreen, script: Script): List<TextBlock>
}

interface TranslationRepository {
    suspend fun detect(text: String): Language
    suspend fun translate(lines: List<String>, source: Language, target: Language): List<String>
}

/** Cloud does OCR and translation in one call, so it returns finished blocks. */
interface CloudTranslationRepository {
    /** False when the user has not enabled cloud or has no key. */
    fun isConfigured(): Boolean

    suspend fun translate(
        screen: CapturedScreen,
        source: Language,
        target: Language,
    ): Translation

    suspend fun verifyKey(key: String)
}

interface SettingsRepository {
    var geminiKey: String
    var cloudFallbackEnabled: Boolean
    var targetLanguage: Language
    var autoTranslate: Boolean
    var themeMode: ThemeMode
    var appLanguage: AppLanguage
}

interface ConnectivityRepository {
    fun isOnline(): Boolean
}
