package vn.onplay.vdubbing.presentation.feature.texttranslation

import vn.onplay.vdubbing.presentation.designsystem.components.bottomsheet.LanguageSelectionSheet
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

class LanguageSelectionSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun selectingIsPendingUntilDoneAndCancelDiscardsIt() {
        var selected = Language.CHINESE
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            VDubbingTheme {
                TextButton(onClick = { open = true }) { Text("Open picker") }
                if (open) LanguageSelectionSheet(selected, { selected = it; open = false }, { open = false })
            }
        }
        compose.onNodeWithText(label(R.string.text_language_japanese_full)).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(Language.CHINESE, selected) }
        compose.onNodeWithText(label(R.string.history_cancel)).performClick()
        compose.onNodeWithText("Open picker").performClick()
        compose.onNodeWithText(label(R.string.text_language_chinese_full)).assertIsSelected()
        compose.onNodeWithText(label(R.string.text_language_japanese_full)).performClick()
        compose.onNodeWithText(label(R.string.dialog_done_title)).performClick()
        compose.runOnIdle { assertEquals(Language.JAPANESE, selected) }
    }

    @Test fun searchesNativeNamesAndCodesAndShowsEmptyResult() {
        compose.setContent { VDubbingTheme { LanguageSelectionSheet(Language.CHINESE, {}, {}) } }
        val search = compose.onNodeWithContentDescription(label(R.string.text_choose_language))
        search.performTextInput("日")
        compose.onNodeWithText(label(R.string.text_language_japanese_full)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.text_language_english_full)).assertDoesNotExist()
        search.performTextReplacement("zzzz")
        compose.onNodeWithText(label(R.string.text_language_not_found)).assertIsDisplayed()
        search.performTextReplacement(" vi ")
        compose.onNodeWithText(label(R.string.text_language_vietnamese_full)).assertIsDisplayed()
    }

    @Test fun namesFollowInterfaceLocaleWhenItChanges() {
        val locale = mutableStateOf("vi")
        compose.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(locale.value))
            }
            val localized = context.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                VDubbingTheme { LanguageSelectionSheet(Language.CHINESE, {}, {}) }
            }
        }
        val expected = mapOf(
            "vi" to "Tiếng Trung (Giản thể)",
            "en" to "Chinese (Simplified)",
            "zh-CN" to "中文（简体）",
            "ja" to "中国語（簡体字）",
            "ko" to "중국어(간체)",
        )
        expected.forEach { (tag, name) ->
            compose.runOnIdle { locale.value = tag }
            compose.onNodeWithText(name).assertIsDisplayed().assertIsSelected()
        }
    }
}
