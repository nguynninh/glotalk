package vn.onplay.vdubbing.presentation.feature.settings

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        modifier = modifier,
        onStartService = viewModel::onStartService,
        onStopService = viewModel::onStopService,
        onOpenAccessibility = viewModel::onOpenAccessibilitySettings,
        onRequestNotifications = onRequestNotifications,
        onTranslateNow = viewModel::onTranslateNow,
        onHideOverlay = viewModel::onHideOverlay,
        onShowBubble = viewModel::onShowBubble,
        onSourceLanguageChange = viewModel::onSourceLanguageChange,
        onTargetLanguageChange = viewModel::onTargetLanguageChange,
        onDisplayModeChange = viewModel::onDisplayModeChange,
        onThemeModeChange = viewModel::onThemeModeChange,
        onAppLanguageChange = viewModel::onAppLanguageChange,
        onAutoTranslateChange = viewModel::onAutoTranslateChange,
        onSaveGeminiLive = viewModel::onSaveGeminiLive,
        onSaveCloud = viewModel::onSaveCloud,
        onVerifyKey = viewModel::onVerifyKey,
        onDismissMessage = viewModel::consumeMessage,
    )
}

@Preview(name = "SettingsUI light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "SettingsUI dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 390, heightDp = 844)
@Composable
internal fun SettingsUIPreview() {
    VDubbingTheme {
        SettingsContent(
            state = SettingsUiState(),
            onStartService = {}, onStopService = {},
            onOpenAccessibility = {}, onRequestNotifications = {},
            onTranslateNow = {}, onHideOverlay = {}, onShowBubble = {},
            onSourceLanguageChange = {}, onTargetLanguageChange = {},
            onDisplayModeChange = {}, onThemeModeChange = {}, onAppLanguageChange = {},
            onAutoTranslateChange = {}, onSaveGeminiLive = { _, _ -> }, onSaveCloud = { _, _ -> },
            onVerifyKey = {}, onDismissMessage = {},
        )
    }
}
