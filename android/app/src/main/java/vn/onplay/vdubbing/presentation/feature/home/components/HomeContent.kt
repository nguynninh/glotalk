package vn.onplay.vdubbing.presentation.feature.home.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.history.TranslationHistoryUI
import vn.onplay.vdubbing.presentation.feature.settings.SettingsUIPreview
import vn.onplay.vdubbing.presentation.feature.texttranslation.TextTranslationUI

@Composable
internal fun HomeContent(
    selectedTab: Int,
    showHistory: Boolean,
    onSelectTab: (Int) -> Unit,
    onOpenHistory: () -> Unit,
    onCloseHistory: () -> Unit,
    modifier: Modifier = Modifier,
    screenTranslation: @Composable () -> Unit,
) {
    val tabState = rememberSaveableStateHolder()
    if (showHistory) {
        TranslationHistoryUI(onBack = onCloseHistory, modifier = modifier)
        return
    }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            tabState.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    0 -> screenTranslation()
                    1 -> TextTranslationUI(onHistory = onOpenHistory)
                }
            }
        }
        if (selectedTab != 1 || !keyboardVisible) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                listOf(R.string.home_screen_tab, R.string.home_text_tab).forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { onSelectTab(index) },
                        text = { Text(stringResource(title)) },
                    )
                }
            }
        }
    }
}

@Preview(name = "HomeUI light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "HomeUI dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 390, heightDp = 844)
@Composable
private fun HomeContentPreview() {
    VDubbingTheme {
        HomeContent(
            selectedTab = 0, showHistory = false,
            onSelectTab = {}, onOpenHistory = {}, onCloseHistory = {},
        ) {
            SettingsUIPreview()
        }
    }
}
