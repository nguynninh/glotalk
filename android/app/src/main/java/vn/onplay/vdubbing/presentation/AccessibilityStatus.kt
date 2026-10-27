package vn.onplay.vdubbing.presentation

/** Reads the system's accessibility setting instead of guessing from a live object. */
interface AccessibilityStatus {
    fun isLive(): Boolean

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
