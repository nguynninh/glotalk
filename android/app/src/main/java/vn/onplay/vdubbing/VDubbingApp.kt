package vn.onplay.vdubbing

import dagger.hilt.android.HiltAndroidApp
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import vn.onplay.vdubbing.di.AppContainer
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import vn.onplay.vdubbing.framework.locale.AppLocaleController

/**
 * Holds the composition root. Services and the activity both pull from the same
 * container, so there is one OCR/translate engine set per process rather than
 * one per component.
 */
@HiltAndroidApp
class VDubbingApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    // First component created in the process, so the base context every other one
    // inherits is already in the chosen language (Android 12 and below).
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleController.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // The language may have been changed from system Settings while no process
        // was alive, so there is no configuration change to react to.
        AppLocaleController.syncAtStartup(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // On Android 13+ the per-app language can also be changed from system
        // Settings; keep our own preference (and the service notifications) in step.
        AppLocaleController.syncFromSystem(this)
        // Overlay windows are long-lived and are not recreated for a language
        // change, so anything already drawn has to be redrawn by hand.
        AccessibilityBridge.service?.overlayRenderer?.refresh()
    }
}
