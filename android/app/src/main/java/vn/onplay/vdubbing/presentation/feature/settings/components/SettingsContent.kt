package vn.onplay.vdubbing.presentation.feature.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    onTranslateNow: () -> Unit,
    onHideOverlay: () -> Unit,
    onShowBubble: () -> Unit,
    onSourceLanguageChange: (Language) -> Unit,
    onTargetLanguageChange: (Language) -> Unit,
    onDisplayModeChange: (DisplayMode) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onSaveGeminiLive: (String, Boolean) -> Unit,
    onSaveCloud: (String, Boolean) -> Unit,
    onVerifyKey: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.home_screen_tab), style = MaterialTheme.typography.headlineLarge)
        Text(
            stringResource(R.string.settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(state, onOpenAccessibility, onRequestNotifications)

        Section(stringResource(R.string.settings_section_background)) {
            Button(
                onClick = if (state.serviceRunning) onStopService else onStartService,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(stringResource(if (state.serviceRunning) R.string.notif_action_stop else R.string.settings_start_service))
            }
            Button(
                onClick = onTranslateNow,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                // Keyed on the live service, not just the setting: enabled in
                // settings does not mean a capture can start this instant.
                enabled = state.accessibilityLive && !state.translating,
            ) {
                Text(
                    stringResource(
                        if (state.translating) {
                            R.string.settings_translating
                        } else {
                            R.string.settings_translate_now
                        },
                    ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShowBubble, enabled = state.accessibilityLive, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_show_bubble))
                }
                OutlinedButton(onClick = onHideOverlay, enabled = state.serviceRunning, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_clear_translation))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_auto_translate),
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.autoTranslate, onCheckedChange = onAutoTranslateChange)
            }
        }

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

        TextButton(
            onClick = { advanced = !advanced },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.panel_advanced), modifier = Modifier.weight(1f))
            Text(if (advanced) "−" else "+", style = MaterialTheme.typography.titleLarge)
        }
        if (advanced) {
            Section(stringResource(R.string.settings_section_app_language)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppLanguage.CHOICES.forEach { language ->
                        FilterChip(
                            selected = state.appLanguage == language,
                            onClick = { onAppLanguageChange(language) },
                            label = { Text(language.displayLabel()) },
                        )
                    }
                }
            }

            Section(stringResource(R.string.settings_section_interface)) {
                // Pinned themes matter because the app's own screen may be read while
                // the phone is in the opposite system theme. The row scrolls rather
                // than wraps so the options stay on one line on small phones.
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.OPTIONS.forEach { mode ->
                        FilterChip(
                            selected = state.themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(stringResource(mode.labelRes())) },
                        )
                    }
                }
            }

            GeminiLiveAudioSection(state, onSaveGeminiLive)

            CloudSection(state, onSaveCloud, onVerifyKey)

            state.accessibilitySnapshot?.let { snapshot ->
                Section(stringResource(R.string.settings_section_diagnostics)) {
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
            onTranslateNow = {}, onHideOverlay = {}, onShowBubble = {},
            onSourceLanguageChange = {}, onTargetLanguageChange = {},
            onDisplayModeChange = {}, onThemeModeChange = {}, onAppLanguageChange = {},
            onAutoTranslateChange = {}, onSaveGeminiLive = { _, _ -> }, onSaveCloud = { _, _ -> },
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
