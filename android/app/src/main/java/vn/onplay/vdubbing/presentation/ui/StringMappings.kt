package vn.onplay.vdubbing.presentation.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.ThemeMode
import vn.onplay.vdubbing.domain.model.TranslationEngine
import vn.onplay.vdubbing.domain.model.TranslationErrorCode

/**
 * Display names for domain enums. They live here rather than on the enums so the
 * domain stays Context-free; the wire values ([TranslationEngine.wireName], the
 * AIDL error codes) are untouched by anything in this file.
 */

@StringRes
fun TranslationErrorCode.messageRes(): Int = when (this) {
    TranslationErrorCode.ACCESSIBILITY_DISABLED -> R.string.error_accessibility_disabled
    TranslationErrorCode.CAPTURE_FAILED -> R.string.error_capture_failed
    TranslationErrorCode.NO_TEXT_FOUND -> R.string.error_no_text
    TranslationErrorCode.TRANSLATION_FAILED -> R.string.error_translation_failed
    TranslationErrorCode.INVALID_API_KEY -> R.string.error_invalid_api_key
    TranslationErrorCode.CLOUD_QUOTA_EXCEEDED -> R.string.error_quota
    TranslationErrorCode.OFFLINE -> R.string.error_offline
    TranslationErrorCode.BUSY -> R.string.error_busy
}

@StringRes
fun DisplayMode.labelRes(): Int = when (this) {
    DisplayMode.PARALLEL -> R.string.panel_mode_parallel
    DisplayMode.REPLACE -> R.string.panel_mode_replace
}

@StringRes
fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@StringRes
fun TranslationEngine.displayNameRes(): Int = when (this) {
    TranslationEngine.ON_DEVICE -> R.string.engine_on_device
    TranslationEngine.CLOUD -> R.string.engine_cloud
}

/**
 * Target languages are shown as endonyms, which is why [Language.label] is used
 * as-is for them; only the two non-languages need translating.
 */
@Composable
fun Language.displayLabel(): String = when (this) {
    Language.AUTO -> stringResource(R.string.language_auto)
    Language.UNKNOWN -> stringResource(R.string.language_unknown)
    else -> label
}

/** Each choice is written in its own language, so it reads the same in any UI. */
@Composable
fun AppLanguage.displayLabel(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(R.string.app_language_system)
    AppLanguage.VIETNAMESE -> stringResource(R.string.app_language_vietnamese)
    AppLanguage.ENGLISH -> stringResource(R.string.app_language_english)
    AppLanguage.JAPANESE -> stringResource(R.string.app_language_japanese)
    AppLanguage.CHINESE_SIMPLIFIED -> stringResource(R.string.app_language_chinese)
    AppLanguage.KOREAN -> stringResource(R.string.app_language_korean)
}
