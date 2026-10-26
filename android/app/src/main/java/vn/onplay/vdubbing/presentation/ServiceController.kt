package vn.onplay.vdubbing.presentation

import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.Language

/** Narrow port over the foreground service, so the ViewModel stays platform-free. */
interface ServiceController {
    fun start()
    fun stop()
    fun setAutoTranslate(enabled: Boolean, target: Language)
}

interface PermissionChecker {
    fun notificationsGranted(): Boolean
}

/** Environment facts the ViewModel needs but cannot ask Android for itself. */
interface AppEnvironment {
    val packageName: String

    /** Applies the interface language app-wide; recreation is the activity's business. */
    fun applyAppLanguage(language: AppLanguage)
}

/** Reads the system's accessibility setting instead of guessing from a live object. */
interface AccessibilityStatus {
    /**
     * True when the user has enabled this app's accessibility service in system
     * settings. Unlike a live service reference this survives the app's process
     * being dead, which is the normal state right after toggling the switch.
     */
    fun isEnabledInSystemSettings(): Boolean

    /** Raw values behind [isEnabledInSystemSettings], for the in-app diagnostics. */
    fun diagnose(): Snapshot

    fun openSystemSettings()

    /**
     * Shown verbatim in the app when the user reports "I enabled it but the app
     * disagrees": the raw system string identifies a component-name mismatch,
     * which no amount of retrying fixes.
     */
    data class Snapshot(
        val enabled: Boolean,
        val masterSwitchOn: Boolean,
        val expectedComponent: String,
        val rawSystemValue: String,
        val liveServiceAttached: Boolean,
        /** Set when the service connected badly; null when it connected fine. */
        val serviceFailure: String? = null,
    )
}
