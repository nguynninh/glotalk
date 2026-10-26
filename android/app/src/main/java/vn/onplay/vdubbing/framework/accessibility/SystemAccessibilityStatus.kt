package vn.onplay.vdubbing.framework.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import vn.onplay.vdubbing.presentation.AccessibilityStatus

/**
 * Asks the system whether our accessibility service is switched on.
 *
 * This is the authoritative answer. [AccessibilityBridge.isReady] only reflects
 * whether a service object happens to be alive in this process, which is false
 * in two ordinary situations: right after the user flips the toggle while our
 * process was not running, and during the moment the service is being
 * destroyed and recreated.
 */
class SystemAccessibilityStatus(private val context: Context) : AccessibilityStatus {

    private val component: ComponentName
        get() = ComponentName(context, ScreenReaderAccessibilityService::class.java)

    override fun isEnabledInSystemSettings(): Boolean = diagnose().enabled

    /** Everything needed to explain a mismatch, including the raw system value. */
    override fun diagnose(): AccessibilityStatus.Snapshot {
        val expected = component.flattenToString()
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        val masterEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1

        return AccessibilityStatus.Snapshot(
            enabled = masterEnabled && contains(raw, expected),
            masterSwitchOn = masterEnabled,
            expectedComponent = expected,
            rawSystemValue = raw,
            liveServiceAttached = AccessibilityBridge.isReady,
            serviceFailure = AccessibilityBridge.service?.connectionFailure,
        )
    }

    /**
     * The setting is a ':'-separated list of flattened ComponentNames. Some OEM
     * builds store entries with a different case or with the short class name,
     * so compare case-insensitively and also accept a short-name suffix.
     */
    private fun contains(raw: String, expected: String): Boolean {
        if (raw.isBlank()) return false
        if (raw.equals(expected, ignoreCase = true)) return true

        val shortSuffix = "/${ScreenReaderAccessibilityService::class.java.simpleName}"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(raw)
        for (entry in splitter) {
            val candidate = entry.trim()
            if (candidate.equals(expected, ignoreCase = true)) return true
            if (candidate.startsWith("${context.packageName}/", ignoreCase = true) &&
                (candidate.endsWith(shortSuffix) ||
                    candidate.endsWith(ScreenReaderAccessibilityService::class.java.name))
            ) {
                return true
            }
        }
        return false
    }

    override fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
