package vn.onplay.vdubbing.presentation.feature.home.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
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
    screenTranslation: @Composable (Dp) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selectedIconColor = if (colors.surface.luminance() < 0.5f) {
        Color(0xFFA9A2FF)
    } else {
        Color(0xFF6155F5)
    }
    var voiceRequest by rememberSaveable { mutableStateOf(0) }
    var showFlashcards by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showHistory || showFlashcards || selectedTab != 0) {
        when {
            showHistory -> onCloseHistory()
            showFlashcards -> showFlashcards = false
            else -> onSelectTab(0)
        }
    }
    val tabState = rememberSaveableStateHolder()
    var inputFocused by remember(selectedTab, showHistory) { mutableStateOf(false) }
    if (showFlashcards) {
        TranslationHistoryUI(onBack = { showFlashcards = false }, modifier = modifier, startReviewing = true)
        return
    }
    if (showHistory) {
        TranslationHistoryUI(onBack = onCloseHistory, modifier = modifier)
        return
    }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardVisible by remember(ime, density) {
        derivedStateOf { ime.getBottom(density) > 0 }
    }
    HomeBlurLayout(
        showNavigation = selectedTab != 1 || !(keyboardVisible || inputFocused),
        modifier = modifier.fillMaxSize().imePadding(),
        content = { bottomInset ->
            tabState.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    0 -> HomeDashboard(
                        bottomInset = bottomInset,
                        onSettings = { onSelectTab(2) },
                        onText = { onSelectTab(1) },
                        onHistory = onOpenHistory,
                        onVoice = { voiceRequest++; onSelectTab(1) },
                        onFlashcards = { showFlashcards = true },
                    )
                    2 -> screenTranslation(bottomInset)
                    1 -> TextTranslationUI(
                        onHistory = onOpenHistory, bottomInset = bottomInset,
                        voiceRequest = voiceRequest,
                        isKeyboardVisible = keyboardVisible,
                        onInputFocusChange = { inputFocused = it },
                    )
                }
            }
        },
        navigation = {
            NavigationBar(
                modifier = Modifier.height(54.dp),
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                listOf(R.string.home_dashboard_tab, R.string.home_translate_tab, R.string.home_settings_tab).forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { onSelectTab(index) },
                        icon = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painterResource(listOf(R.drawable.ic_home, R.drawable.ic_overlay_translate, R.drawable.ic_overlay_settings)[index]),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    stringResource(title),
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    color = if (selectedTab == index) selectedIconColor else colors.onSurfaceVariant,
                                )
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = selectedIconColor,
                            selectedTextColor = colors.onSurface,
                            unselectedIconColor = colors.onSurfaceVariant,
                            unselectedTextColor = colors.onSurfaceVariant,
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    )
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
            SettingsUIPreview(bottomInset = it)
        }
    }
}
