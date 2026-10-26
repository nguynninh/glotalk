package vn.onplay.vdubbing.framework.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.concurrent.CopyOnWriteArraySet
import vn.onplay.vdubbing.data.local.SettingsRepositoryImpl
import vn.onplay.vdubbing.domain.model.AppLanguage

/**
 * Applies the interface language picked in settings.
 *
 * The preference is authoritative in our own code, on every API level: components
 * wrap their base context at creation, which makes the language deterministic and
 * avoids depending on when the framework delivers a configuration change.
 *
 * Android 13+ additionally has a platform per-app language, which we mirror in
 * both directions so the choice also shows up (and can be changed) in system
 * Settings. A service instance is never recreated by a preference change, which
 * is why long-lived components must read through [localized] every time and
 * listen for [addListener] to redraw what is already on screen.
 */
object AppLocaleController {

    private val listeners = CopyOnWriteArraySet<(AppLanguage) -> Unit>()

    private fun storedLanguage(context: Context): AppLanguage =
        SettingsRepositoryImpl(context).appLanguage

    /** For `attachBaseContext`: returns a context already bound to the chosen locale. */
    fun wrap(base: Context): Context = base.withLocale(storedLanguage(base))

    /** The context to read strings from at the moment of use, never a cached one. */
    fun localized(base: Context): Context = base.withLocale(storedLanguage(base))

    /** Called after the choice is persisted: applies it and restarts the screen once. */
    fun apply(activity: Activity, language: AppLanguage) {
        notifyChanged(language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Setting the platform locale also recreates the activity, so calling
            // recreate() here as well would restart the screen twice.
            setApplicationLocales(activity, language)
        } else {
            activity.recreate()
        }
    }

    /**
     * Android 13+ exposes the same choice in system Settings. Mirror it into our
     * preferences so the in-app picker and the service notifications agree.
     */
    fun syncFromSystem(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val locales = applicationLocales(context)
        val language = if (locales.isEmpty) {
            AppLanguage.SYSTEM
        } else {
            AppLanguage.fromTag(locales[0].toLanguageTag())
        }
        val repository = SettingsRepositoryImpl(context)
        if (repository.appLanguage != language) {
            repository.appLanguage = language
            notifyChanged(language)
        }
    }

    /**
     * At process start the two stores can disagree: on Android 13+ the platform's
     * per-app language may be empty while our preference holds a choice (the
     * preference predates the upgrade, or system Settings never had it). Push the
     * preference to the platform in that case, so the choice is not silently lost.
     */
    fun syncAtStartup(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val stored = SettingsRepositoryImpl(context).appLanguage
        if (applicationLocales(context).isEmpty && stored != AppLanguage.SYSTEM) {
            // Ours is the only record of the choice; hand it to the platform.
            setApplicationLocales(context, stored)
            notifyChanged(stored)
        } else {
            // The platform may know a language the preference does not (chosen in
            // system Settings before this process started), or name the same one
            // with a different variant (zh-Hans-CN vs zh-CN).
            syncFromSystem(context)
        }
    }

    fun addListener(listener: (AppLanguage) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (AppLanguage) -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged(language: AppLanguage) {
        listeners.forEach { it(language) }
    }

    private fun Context.withLocale(language: AppLanguage): Context {
        val tag = language.tag ?: return this
        val configuration = Configuration(resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return createConfigurationContext(configuration)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setApplicationLocales(context: Context, language: AppLanguage) {
        val locales = language.tag
            ?.let { LocaleList.forLanguageTags(it) }
            ?: LocaleList.getEmptyLocaleList()
        context.getSystemService(LocaleManager::class.java).applicationLocales = locales
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applicationLocales(context: Context): LocaleList =
        context.getSystemService(LocaleManager::class.java).applicationLocales
}
