package vn.onplay.vdubbing.presentation.feature.texttranslation

import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import vn.onplay.vdubbing.ComposeTestActivity
import vn.onplay.vdubbing.di.TranslationModule
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository
import vn.onplay.vdubbing.domain.repository.TranslationRepository
import vn.onplay.vdubbing.presentation.FakeTextTranslation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import vn.onplay.vdubbing.presentation.FakeTranslationHistory
import org.junit.Rule
import org.junit.Test
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@HiltAndroidTest
@UninstallModules(TranslationModule::class)
class TextTranslationScreenTest {
    private val history = FakeTranslationHistory()
    @BindValue @JvmField val historyRepository: TranslationHistoryRepository = history
    private val translation = FakeTextTranslation()
    @BindValue @JvmField val translationRepository: TranslationRepository = translation
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComposeTestActivity>()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun translatesAfterCountdownAndRetranslatesAfterLanguageChangeAndClearsResult() {
        val calls = mutableListOf<String>()
        translation.translateText = { text, source, target ->
            "$text:${source.code}:${target.code}".also { calls += it }
        }
        compose.setContent {
            VDubbingTheme {
                TextTranslationUI(onHistory = {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("ab")
        compose.waitUntil(5_000) { calls.size == 1 }
        compose.runOnIdle { assertEquals(listOf("ab:zh:vi"), calls) }
        compose.onNodeWithText("ab:zh:vi").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.panel_swap_languages)).performClick()
        compose.waitUntil(5_000) { calls.size == 2 }
        compose.onNodeWithText("ab:vi:zh").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.text_clear)).performClick()
        compose.onNodeWithText("ab:vi:zh").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.text_input_hint)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, calls.size); assertEquals(1, history.records.size) }
        compose.onNode(hasSetTextAction()).performTextInput("new")
        compose.waitUntil(5_000) { history.records.size == 2 }
        compose.runOnIdle { assertEquals(setOf("ab", "new"), history.records.map { it.original }.toSet()) }
    }

    @Test fun lateResultCannotOverwriteNewText() {
        val oldResult = CompletableDeferred<String>()
        var oldStarted = false
        translation.translateText = { text, _, _ ->
            if (text == "old") {
                oldStarted = true
                withContext(NonCancellable) { oldResult.await() }
            } else "new result"
        }
        compose.setContent {
            VDubbingTheme {
                TextTranslationUI(onHistory = {})
            }
        }
        try {
            compose.onNode(hasSetTextAction()).performTextInput("old")
            compose.waitUntil(5_000) { oldStarted }
            compose.onNode(hasSetTextAction()).performTextReplacement("new")
            compose.waitUntil(5_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("new result")).fetchSemanticsNodes().isNotEmpty()
            }
            oldResult.complete("stale result")
            compose.waitForIdle()
            compose.onNodeWithText("new result").assertIsDisplayed()
            compose.onNodeWithText("stale result").assertDoesNotExist()
            compose.runOnIdle { assertTrue(history.records.none { it.original == "old" }) }
        } finally {
            oldResult.complete("stale result")
        }
    }

    @Test fun showsFailureAndRetriesWhenTextChanges() {
        translation.translateText = { text, _, _ ->
            if (text == "fail") throw IllegalStateException("offline")
            "translation recovered"
        }
        compose.setContent {
            VDubbingTheme {
                TextTranslationUI(onHistory = {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("fail")
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(label(R.string.text_translation_error)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("retry")
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("translation recovered")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.text_translation_error)).assertDoesNotExist()
        compose.onNodeWithText("translation recovered").assertIsDisplayed()
    }
}
