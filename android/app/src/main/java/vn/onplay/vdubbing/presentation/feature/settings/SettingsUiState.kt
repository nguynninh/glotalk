package vn.onplay.vdubbing.presentation.feature.settings

import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.ThemeMode
import vn.onplay.vdubbing.domain.model.Translation
import vn.onplay.vdubbing.presentation.AccessibilityStatus
import vn.onplay.vdubbing.presentation.uimodel.UiMessage

/**
 * One immutable snapshot of everything the screen draws. The ViewModel exposes
 * exactly this through a StateFlow; the screen has no other state of its own
 * beyond text currently being typed.
 */
data class SettingsUiState(
    /** Enabled in system settings — what the UI reports as "đã bật". */
    val accessibilityReady: Boolean = false,

    /** A live service object is attached, so a translation can start right now. */
    val accessibilityLive: Boolean = false,

    /**
     * Enabled in settings but the service never bound back. Android leaves the
     * toggle on after an app update and silently stops rebinding; the only cure
     * is toggling it off and on, which the UI has to say out loud.
     */
    val accessibilityNeedsRebind: Boolean = false,
    val serviceRunning: Boolean = false,
    val notificationsGranted: Boolean = false,
    val cloudEnabled: Boolean = false,
    val savedKey: String = "",
    /** What the screen is assumed to be in; AUTO asks the translator to detect it. */
    val sourceLanguage: Language = Language.AUTO,
    val targetLanguage: Language = Language.VIETNAMESE,
    /** Parallel (original kept) or replace (covered) — see [DisplayMode]. */
    val displayMode: DisplayMode = DisplayMode.REPLACE,
    val autoTranslate: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val translating: Boolean = false,
    val lastTranslation: Translation? = null,
    val message: UiMessage? = null,
    /** Raw system values, shown in the diagnostics panel. */
    val accessibilitySnapshot: AccessibilityStatus.Snapshot? = null,
) {
    val cloudActive: Boolean get() = cloudEnabled && savedKey.isNotBlank()
}
