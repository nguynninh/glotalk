package vn.onplay.vdubbing.presentation.feature.history

import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import vn.onplay.vdubbing.ComposeTestActivity
import vn.onplay.vdubbing.di.TranslationModule
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository
import vn.onplay.vdubbing.domain.repository.TranslationRepository
import vn.onplay.vdubbing.presentation.FakeTextTranslation
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry
import vn.onplay.vdubbing.presentation.FakeTranslationHistory
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@HiltAndroidTest
@UninstallModules(TranslationModule::class)
class TranslationHistoryScreenTest {
    private val history = FakeTranslationHistory().apply {
        records += TranslationHistoryEntry(1, "义务", "nghĩa vụ; tình nguyện; miễn phí; không công; công ích",
            Language.CHINESE, Language.VIETNAMESE, 1791248400000, false)
        records += TranslationHistoryEntry(2, "审美", "thẩm mỹ; khiếu thẩm mỹ",
            Language.CHINESE, Language.VIETNAMESE, 1790816400000, false)
    }
    @BindValue @JvmField val historyRepository: TranslationHistoryRepository = history
    private val translation = FakeTextTranslation()
    @BindValue @JvmField val translationRepository: TranslationRepository = translation
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComposeTestActivity>()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)


    @Test fun opensCardsFlipsBookmarksAndReturnsToHistory() {
        compose.setContent { VDubbingTheme { TranslationHistoryUI(onBack = {}) } }
        compose.onNodeWithText("义务").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.history_review)).performClick()
        compose.onNodeWithText("义务").assertIsDisplayed()
        compose.onNodeWithText("义务").performClick()
        compose.onNodeWithText(history.records.first().translated).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.history_bookmark)).performClick()
        compose.runOnIdle { assertTrue(history.records.first().bookmarked) }
        compose.onNodeWithContentDescription(label(R.string.cards_known)).performClick()
        compose.onNodeWithText("审美").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.cards_previous)).performClick()
        compose.onNodeWithText("义务").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.text_back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.history_unbookmark)).assertIsDisplayed()
    }

    @Test fun deletionNeedsConfirmationAndCancelPreservesHistory() {
        compose.setContent { VDubbingTheme { TranslationHistoryUI(onBack = {}) } }
        compose.onNodeWithContentDescription(label(R.string.history_delete)).performClick()
        compose.onNodeWithText(label(R.string.history_cancel)).performClick()
        compose.runOnIdle { assertEquals(2, history.records.size) }
        compose.onNodeWithContentDescription(label(R.string.history_delete)).performClick()
        compose.onNode(hasText(label(R.string.history_delete)) and hasClickAction()).performClick()
        compose.onNodeWithText(label(R.string.history_empty)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(history.records.isEmpty()) }
    }
}
