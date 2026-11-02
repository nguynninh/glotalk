package vn.onplay.vdubbing.presentation.feature.settings

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.settings.components.SettingsContent

@Composable
fun SettingsUI(
    modifier: Modifier = Modifier,
    onRequestNotifications: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    bottomInset: Dp = 0.dp,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        modifier = modifier,
        bottomInset = bottomInset,
        onStartService = viewModel::onStartService,
        onStopService = viewModel::onStopService,
        onOpenAccessibility = viewModel::onOpenAccessibilitySettings,
        onRequestNotifications = onRequestNotifications,
        onBubbleEnabledChange = viewModel::onBubbleEnabledChange,
        onSourceLanguageChange = viewModel::onSourceLanguageChange,
        onTargetLanguageChange = viewModel::onTargetLanguageChange,
        onDisplayModeChange = viewModel::onDisplayModeChange,
        onThemeModeChange = viewModel::onThemeModeChange,
        onAppLanguageChange = viewModel::onAppLanguageChange,
        onSaveGeminiLive = viewModel::onSaveGeminiLive,
        onSaveOpenAiRealtime = viewModel::onSaveOpenAiRealtime,
        onSaveCloud = viewModel::onSaveCloud,
        onVerifyKey = viewModel::onVerifyKey,
        onDismissMessage = viewModel::consumeMessage,
    )
}

@Preview(name = "SettingsUI light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "SettingsUI dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 390, heightDp = 844)
@Composable
internal fun SettingsUIPreview(bottomInset: Dp = 0.dp) {
    VDubbingTheme {
        SettingsContent(
            state = SettingsUiState(),
            bottomInset = bottomInset,
            onStartService = {}, onStopService = {},
            onOpenAccessibility = {}, onRequestNotifications = {},
            onBubbleEnabledChange = {},
            onSourceLanguageChange = {}, onTargetLanguageChange = {},
            onDisplayModeChange = {}, onThemeModeChange = {}, onAppLanguageChange = {},
            onSaveGeminiLive = { _, _ -> }, onSaveOpenAiRealtime = { _, _ -> }, onSaveCloud = { _, _ -> },
            onVerifyKey = {}, onDismissMessage = {},
        )
    }
}
