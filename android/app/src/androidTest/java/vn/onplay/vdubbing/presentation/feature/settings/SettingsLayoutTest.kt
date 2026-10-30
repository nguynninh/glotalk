package vn.onplay.vdubbing.presentation.feature.settings

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import vn.onplay.vdubbing.ComposeTestActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.settings.components.SettingsContent

@HiltAndroidTest
class SettingsLayoutTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComposeTestActivity>()

    @Test fun advancedSettingsCanBeExpandedAndSourceMenuKeepsAutoDetection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected: Language? = null
        compose.setContent {
            VDubbingTheme {
                SettingsContent(
                    state = SettingsUiState(sourceLanguage = Language.ENGLISH),
                    onStartService = {}, onStopService = {},
                    onOpenAccessibility = {}, onRequestNotifications = {},
                    onTranslateNow = {}, onHideOverlay = {}, onShowBubble = {},
                    onSourceLanguageChange = { selected = it }, onTargetLanguageChange = {},
                    onDisplayModeChange = {}, onThemeModeChange = {}, onAppLanguageChange = {},
                    onAutoTranslateChange = {}, onSaveGeminiLive = { _, _ -> },
                    onSaveCloud = { _, _ -> }, onVerifyKey = {}, onDismissMessage = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_section_app_language)).assertDoesNotExist()
        compose.onNodeWithText("English").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.language_auto)).performClick()
        compose.runOnIdle { assertEquals(Language.AUTO, selected) }
        compose.onNodeWithText(context.getString(R.string.panel_advanced)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.settings_section_app_language)).assertExists()
        compose.onNodeWithText(context.getString(R.string.panel_advanced)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.settings_section_app_language)).assertDoesNotExist()
    }
}
