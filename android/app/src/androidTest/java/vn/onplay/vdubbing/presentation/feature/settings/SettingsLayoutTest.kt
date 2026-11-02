package vn.onplay.vdubbing.presentation.feature.settings

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import vn.onplay.vdubbing.ComposeTestActivity
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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

    @Test fun permissionSwitchesReplaceButtons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var accessibilityRequests = 0
        var notificationRequests = 0
        compose.setContent {
            VDubbingTheme {
                vn.onplay.vdubbing.presentation.feature.settings.components.StatusCard(
                    state = SettingsUiState(),
                    onOpenAccessibility = { accessibilityRequests++ },
                    onRequestNotifications = { notificationRequests++ },
                    onServiceChange = {}, onCloudChange = {}, onBubbleEnabledChange = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.status_accessibility)).performClick()
        compose.onNodeWithText(context.getString(R.string.status_enable_accessibility)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.status_notifications)).performClick()
        compose.onNodeWithText(context.getString(R.string.status_enable_notifications)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, accessibilityRequests)
            assertEquals(1, notificationRequests)
        }
    }

    @Test fun bubbleOptionDefaultsOnAndCanBeDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = androidx.compose.runtime.mutableStateOf(SettingsUiState())
        compose.setContent {
            VDubbingTheme {
                vn.onplay.vdubbing.presentation.feature.settings.components.StatusCard(
                    state = state.value,
                    onOpenAccessibility = {}, onRequestNotifications = {},
                    onServiceChange = {}, onCloudChange = {},
                    onBubbleEnabledChange = { state.value = state.value.copy(bubbleEnabled = it) },
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.status_interactive_bubble))
            .assertIsOn().performClick().assertIsOff()
    }

    @Test fun statusSwitchReflectsStateAndRequestsChange() {
        val checked = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            VDubbingTheme {
                vn.onplay.vdubbing.presentation.feature.settings.components.StatusLine(
                    "Service", checked.value, onCheckedChange = { checked.value = it },
                )
            }
        }
        compose.onNodeWithContentDescription("Service").assertIsOff().performClick().assertIsOn()
    }

    @Test fun systemSettingsStayVisibleAndSourceMenuKeepsAutoDetection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected: Language? = null
        compose.setContent {
            VDubbingTheme {
                SettingsContent(
                    state = SettingsUiState(sourceLanguage = Language.ENGLISH),
                    onStartService = {}, onStopService = {},
                    onOpenAccessibility = {}, onRequestNotifications = {},
                    onBubbleEnabledChange = {},
                    onSourceLanguageChange = { selected = it }, onTargetLanguageChange = {},
                    onDisplayModeChange = {}, onThemeModeChange = {}, onAppLanguageChange = {},
                    onSaveGeminiLive = { _, _ -> }, onSaveOpenAiRealtime = { _, _ -> },
                    onSaveCloud = { _, _ -> }, onVerifyKey = {}, onDismissMessage = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_section_app_language)).assertExists()
        compose.onNodeWithText("English").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.language_auto)).performClick()
        compose.runOnIdle { assertEquals(Language.AUTO, selected) }
        compose.onNodeWithText(context.getString(R.string.settings_section_app_language)).performScrollTo().assertExists()
    }
}
