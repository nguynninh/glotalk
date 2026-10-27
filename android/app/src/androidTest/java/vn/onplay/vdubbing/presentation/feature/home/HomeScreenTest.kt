package vn.onplay.vdubbing.presentation.feature.home

import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import vn.onplay.vdubbing.ComposeTestActivity
import vn.onplay.vdubbing.di.TranslationModule
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository
import vn.onplay.vdubbing.domain.repository.TranslationRepository
import vn.onplay.vdubbing.presentation.FakeTextTranslation
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import vn.onplay.vdubbing.presentation.FakeTranslationHistory
import org.junit.Rule
import org.junit.Test
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@HiltAndroidTest
@UninstallModules(TranslationModule::class)
class HomeScreenTest {
    private val history = FakeTranslationHistory()
    @BindValue @JvmField val historyRepository: TranslationHistoryRepository = history
    private val translation = FakeTextTranslation()
    @BindValue @JvmField val translationRepository: TranslationRepository = translation
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComposeTestActivity>()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun textAndLanguageSelectionSurviveTabChanges() {
        compose.setContent {
            VDubbingTheme { HomeUI() { Text("Screen settings") } }
        }
        compose.onNodeWithText("Screen settings").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.home_text_tab)).performClick()
        compose.onNodeWithContentDescription(label(R.string.text_keyboard)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(label(R.string.text_back)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.text_input_hint)).performTextInput("Hello")
        compose.onNodeWithContentDescription(label(R.string.text_back)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(label(R.string.home_screen_tab)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(label(R.string.panel_swap_languages)).performClick()
        compose.onNode(hasText(label(R.string.text_language_vietnamese)) and hasClickAction()).performClick()
        compose.onNodeWithText(label(R.string.text_language_english_full)).performClick()
        compose.onNodeWithText(label(R.string.dialog_done_title)).performClick()
        compose.onNodeWithText(label(R.string.home_screen_tab)).performClick()
        compose.onNodeWithText("Screen settings").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.home_text_tab)).performClick()
        compose.onNodeWithText("Hello").assertTextEquals("Hello")
        compose.onAllNodes(androidx.compose.ui.test.hasText("English"))[0].assertIsDisplayed()
        compose.onAllNodes(androidx.compose.ui.test.hasText(label(R.string.text_language_chinese)))[0].assertIsDisplayed()
    }

    @Test fun keyboardShowsCompactToolbarAndBackRestoresTabsWithoutLosingText() {
        compose.setContent {
            VDubbingTheme { HomeUI() { Text("Screen settings") } }
        }
        compose.onNodeWithText(label(R.string.home_text_tab)).performClick()
        compose.onNodeWithContentDescription(label(R.string.text_keyboard)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(label(R.string.text_back)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.home_screen_tab)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.text_language_chinese_full)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.text_language_vietnamese_full)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.text_input_hint)).performTextInput("Hello")
        compose.onNodeWithContentDescription(label(R.string.text_back)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(label(R.string.home_screen_tab)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Hello").assertTextEquals("Hello")
        compose.onAllNodes(androidx.compose.ui.test.hasText(label(R.string.text_language_chinese)))[0].assertIsDisplayed()
        compose.onAllNodes(androidx.compose.ui.test.hasText(label(R.string.text_language_vietnamese)))[0].assertIsDisplayed()
    }

    @Test fun historyBackReturnsToTextTab() {
        compose.setContent {
            VDubbingTheme { HomeUI() { Text("Screen settings") } }
        }
        compose.onNodeWithText(label(R.string.home_text_tab)).performClick()
        compose.onNodeWithContentDescription(label(R.string.text_history)).performClick()
        compose.onNodeWithText(label(R.string.history_empty)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.text_back)).performClick()
        compose.onNodeWithText(label(R.string.text_input_hint)).assertIsDisplayed()
        compose.onNodeWithText("Screen settings").assertDoesNotExist()
    }

}
