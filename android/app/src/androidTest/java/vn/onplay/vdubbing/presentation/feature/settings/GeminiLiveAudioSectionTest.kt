package vn.onplay.vdubbing.presentation.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.settings.components.GeminiLiveAudioSection

class GeminiLiveAudioSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keyAndUploadChoiceAreSavedOnlyAfterUserClicksSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var saved: Pair<String, Boolean>? = null
        compose.setContent {
            VDubbingTheme { GeminiLiveAudioSection(SettingsUiState()) { key, enabled -> saved = key to enabled } }
        }
        compose.onNodeWithText(context.getString(R.string.gemini_live_key_label)).performTextInput("AIza-test-personal")
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
        compose.runOnIdle { assertEquals("AIza-test-personal" to true, saved) }
    }
}
