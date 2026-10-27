package vn.onplay.vdubbing.presentation.feature.home

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.home.components.HomeContent
import vn.onplay.vdubbing.presentation.feature.settings.SettingsUIPreview

@Composable
fun HomeUI(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    screenTranslation: @Composable () -> Unit,
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val showHistory by viewModel.showHistory.collectAsStateWithLifecycle()
    HomeContent(selectedTab, showHistory, viewModel::selectTab,
        viewModel::openHistory, viewModel::closeHistory, modifier, screenTranslation)
}

@Preview(name = "HomeUI light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "HomeUI dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 390, heightDp = 844)
@Composable
private fun HomeUIPreview() {
    VDubbingTheme {
        HomeContent(
            selectedTab = 0, showHistory = false,
            onSelectTab = {}, onOpenHistory = {}, onCloseHistory = {},
        ) {
            SettingsUIPreview()
        }
    }
}
