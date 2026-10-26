package vn.onplay.vdubbing.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.ThemeMode
import vn.onplay.vdubbing.domain.repository.SettingsRepository

/**
 * Preferences only. The Gemini key is MODE_PRIVATE app storage: never placed in
 * BuildConfig, never logged, never echoed back to a calling app.
 */
class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    // `applicationContext` is still null inside Application.attachBaseContext,
    // which is where the locale controller reads this repository from.
    private val prefs: SharedPreferences =
        (context.applicationContext ?: context)
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var geminiKey: String
        get() = prefs.getString(KEY_GEMINI_KEY, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_GEMINI_KEY, value.trim()) }

    /** Off until the user turns it on: enabling it sends screenshots to Google. */
    override var cloudFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_CLOUD_ENABLED, value) }

    override var targetLanguage: Language
        get() = Language.fromCode(prefs.getString(KEY_TARGET, Language.VIETNAMESE.code))
            .takeIf { it in Language.TARGETS } ?: Language.VIETNAMESE
        set(value) = prefs.edit { putString(KEY_TARGET, value.code) }

    override var autoTranslate: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO, value) }

    /** Defaults to following the device; the app never forces a theme unasked. */
    override var themeMode: ThemeMode
        get() = ThemeMode.fromCode(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.code))
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value.code) }

    /** Same default and same reasoning as [themeMode]: follow the device until told otherwise. */
    override var appLanguage: AppLanguage
        get() = AppLanguage.fromTag(prefs.getString(KEY_APP_LANGUAGE, null))
        set(value) = prefs.edit {
            if (value.tag == null) remove(KEY_APP_LANGUAGE) else putString(KEY_APP_LANGUAGE, value.tag)
        }

    private companion object {
        const val FILE = "v_dubbing_settings"
        const val KEY_GEMINI_KEY = "gemini_key"
        const val KEY_CLOUD_ENABLED = "cloud_fallback_enabled"
        const val KEY_TARGET = "target_language"
        const val KEY_AUTO = "auto_translate"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_APP_LANGUAGE = "app_language"
    }
}
