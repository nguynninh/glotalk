package vn.onplay.vdubbing.presentation.designsystem.help

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language

@Composable
internal fun Language.translationLabel(): String = stringResource(when (this) {
    Language.ENGLISH -> R.string.text_language_english_full
    Language.VIETNAMESE -> R.string.text_language_vietnamese_full
    Language.JAPANESE -> R.string.text_language_japanese_full
    Language.KOREAN -> R.string.text_language_korean_full
    Language.CHINESE -> R.string.text_language_chinese_full
    Language.AUTO -> R.string.language_auto
    Language.UNKNOWN -> R.string.language_unknown
})

internal fun Language.flagCountryCode(): String? = when (this) {
    Language.CHINESE -> "cn"
    Language.VIETNAMESE -> "vn"
    Language.ENGLISH -> "gb"
    Language.JAPANESE -> "jp"
    Language.KOREAN -> "kr"
    else -> null
}

internal fun Language.flag(): String = flagCountryCode()?.uppercase()?.map {
    String(Character.toChars(0x1F1E6 + it.code - 'A'.code))
}?.joinToString("") ?: code

internal fun Language.flagUrl(): String? = flagCountryCode()?.let(CountryFlags::url)
