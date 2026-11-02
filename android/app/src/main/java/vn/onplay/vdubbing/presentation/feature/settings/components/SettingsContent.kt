package vn.onplay.vdubbing.presentation.feature.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.BuildConfig
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.ThemeMode
import vn.onplay.vdubbing.presentation.designsystem.components.card.Section
import vn.onplay.vdubbing.presentation.designsystem.help.resolve
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.settings.SettingsUiState
import vn.onplay.vdubbing.presentation.mapper.displayLabel
import vn.onplay.vdubbing.presentation.mapper.displayNameRes
import vn.onplay.vdubbing.presentation.mapper.labelRes

@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
    onBubbleEnabledChange: (Boolean) -> Unit,
    onSourceLanguageChange: (Language) -> Unit,
    onTargetLanguageChange: (Language) -> Unit,
    onDisplayModeChange: (DisplayMode) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onSaveGeminiLive: (String, Boolean) -> Unit,
    onSaveOpenAiRealtime: (String, Boolean) -> Unit,
    onOnPlayAudioChange: (Boolean) -> Unit,
    onSaveCloud: (String, Boolean) -> Unit,
    onVerifyKey: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.home_settings_tab), style = MaterialTheme.typography.headlineLarge)
        Text(
            stringResource(R.string.settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(
            state, onOpenAccessibility, onRequestNotifications,
            onServiceChange = { enabled ->
                if (enabled) {
                    onStartService()
                } else {
                    onStopService()
                }
            },
            onBubbleEnabledChange = onBubbleEnabledChange,
            onCloudChange = { enabled ->
                if (!enabled || state.savedKey.isNotBlank()) onSaveCloud(state.savedKey, enabled)
            },
        )

        Section(stringResource(R.string.text_choose_language)) {
            LanguageSetting(
                stringResource(R.string.settings_section_source_language),
                state.sourceLanguage, Language.SOURCES, onSourceLanguageChange,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LanguageSetting(
                stringResource(R.string.settings_section_target_language),
                state.targetLanguage, Language.TARGETS, onTargetLanguageChange,
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.panel_mode_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DisplayMode.CHOICES.forEach { mode ->
                    FilterChip(
                        selected = state.displayMode == mode,
                        onClick = { onDisplayModeChange(mode) },
                        label = { Text(stringResource(mode.labelRes())) },
                    )
                }
            }
            Text(
                stringResource(
                    if (state.displayMode == DisplayMode.PARALLEL) {
                        R.string.panel_mode_parallel_desc
                    } else {
                        R.string.panel_mode_replace_desc
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.lastTranslation?.let { translation ->
            Section(stringResource(R.string.settings_section_last_result)) {
                Text(
                    stringResource(
                        R.string.settings_last_result_meta,
                        pluralStringResource(
                            R.plurals.unit_lines,
                            translation.blocks.size,
                            translation.blocks.size,
                        ),
                        stringResource(translation.engine.displayNameRes()),
                        translation.source.displayLabel(),
                        translation.target.displayLabel(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(translation.plainText, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.panel_advanced),
                    style = MaterialTheme.typography.titleMedium,
                )
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_section_app_language),
                    selected = state.appLanguage,
                    options = AppLanguage.CHOICES,
                    label = { it.displayLabel() },
                    onSelect = onAppLanguageChange,
                )
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_section_interface),
                    selected = state.themeMode,
                    options = ThemeMode.OPTIONS,
                    label = { stringResource(it.labelRes()) },
                    onSelect = onThemeModeChange,
                )

                GeminiLiveAudioSection(state, onSaveGeminiLive)
                OnPlayAudioSection(state.onPlayAudioEnabled, onOnPlayAudioChange)
                OpenAiRealtimeAudioSection(state, onSaveOpenAiRealtime)

                CloudSection(state, onSaveCloud, onVerifyKey)

                if (BuildConfig.FLAVOR == "development") {
                    state.accessibilitySnapshot?.let { snapshot ->
                        SettingsGroup(stringResource(R.string.settings_section_diagnostics)) {
                            Text(
                                stringResource(R.string.settings_diagnostics_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            DiagnosticLine(
                                stringResource(R.string.diag_master_switch),
                                stringResource(
                                    if (snapshot.masterSwitchOn) R.string.diag_on else R.string.diag_off,
                                ),
                            )
                            DiagnosticLine(
                                stringResource(R.string.diag_service_live),
                                stringResource(
                                    if (snapshot.liveServiceAttached) R.string.diag_yes else R.string.diag_no,
                                ),
                            )
                            snapshot.serviceFailure?.let {
                                DiagnosticLine(stringResource(R.string.diag_connection_error), it)
                            }
                            DiagnosticLine(
                                stringResource(R.string.diag_expected_component),
                                snapshot.expectedComponent,
                            )
                            DiagnosticLine(
                                stringResource(R.string.diag_system_stored),
                                snapshot.rawSystemValue.ifBlank { stringResource(R.string.diag_empty) },
                            )
                        }
                    }

                    Text(
                        stringResource(R.string.settings_bubble_hint),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        stringResource(R.string.settings_developer_hint),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            confirmButton = {
                Button(onClick = onDismissMessage) { Text(stringResource(R.string.action_close)) }
            },
            title = {
                Text(
                    stringResource(
                        if (message.isError) {
                            R.string.dialog_error_title
                        } else {
                            R.string.dialog_done_title
                        },
                    ),
                )
            },
            text = { Text(message.content.resolve()) },
        )
    }
}

@Preview(name = "SettingsUI light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "SettingsUI dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 390, heightDp = 844)
@Composable
private fun SettingsContentPreview() {
    VDubbingTheme {
        SettingsContent(
            state = SettingsUiState(),
            onStartService = {}, onStopService = {},
            onOpenAccessibility = {}, onRequestNotifications = {},
            onBubbleEnabledChange = {},
            onSourceLanguageChange = {}, onTargetLanguageChange = {},
            onDisplayModeChange = {}, onThemeModeChange = {}, onAppLanguageChange = {},
            onSaveGeminiLive = { _, _ -> }, onSaveOpenAiRealtime = { _, _ -> }, onOnPlayAudioChange = {}, onSaveCloud = { _, _ -> },
            onVerifyKey = {}, onDismissMessage = {},
        )
    }
}

@Composable
private fun LanguageSetting(
    title: String,
    selected: Language,
    options: List<Language>,
    onSelect: (Language) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(selected.displayLabel())
                Text(" ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language.displayLabel()) },
                        onClick = { expanded = false; onSelect(language) },
                    )
                }
            }
        }
    }
}

@Preview(name = "Language setting light", showBackground = true)
@Preview(name = "Language setting dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LanguageSettingPreview() {
    VDubbingTheme {
        LanguageSetting("Ngôn ngữ nguồn", Language.AUTO, Language.SOURCES, {})
    }
}
