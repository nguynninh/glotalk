package vn.onplay.vdubbing.presentation.feature.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.ThemeMode
import vn.onplay.vdubbing.presentation.designsystem.help.resolve
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.settings.SettingsUiState
import vn.onplay.vdubbing.presentation.designsystem.components.card.Section
import vn.onplay.vdubbing.presentation.mapper.displayLabel
import vn.onplay.vdubbing.presentation.mapper.displayNameRes
import vn.onplay.vdubbing.presentation.mapper.labelRes

/**
 * Stateless: every value arrives in [state] and every interaction leaves through
 * a callback. The only local state is text the user is mid-way through typing.
 *
 * All copy comes from resources, so a language change re-renders this whole
 * screen without any state change.
 */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
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
    onSaveCloud: (String, Boolean) -> Unit,
    onVerifyKey: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("VDubbing", style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.settings_intro), style = MaterialTheme.typography.bodyMedium)

        StatusCard(state, onOpenAccessibility, onRequestNotifications)

        Section(stringResource(R.string.settings_section_background)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartService, enabled = !state.serviceRunning) {
                    Text(stringResource(R.string.settings_start_service))
                }
                OutlinedButton(onClick = onStopService, enabled = state.serviceRunning) {
                    Text(stringResource(R.string.notif_action_stop))
                }
            }
            Button(
                onClick = onTranslateNow,
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
                OutlinedButton(onClick = onShowBubble, enabled = state.accessibilityLive) {
                    Text(stringResource(R.string.settings_show_bubble))
                }
                OutlinedButton(onClick = onHideOverlay, enabled = state.serviceRunning) {
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

        Section(stringResource(R.string.settings_section_source_language)) {
            // AUTO detects per capture; naming the language skips that call and
            // is steadier on short lines, which is why it is offered at all.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Language.SOURCES.forEach { language ->
                    FilterChip(
                        selected = state.sourceLanguage == language,
                        onClick = { onSourceLanguageChange(language) },
                        label = { Text(language.displayLabel()) },
                    )
                }
            }
        }

        Section(stringResource(R.string.settings_section_target_language)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Language.TARGETS.forEach { language ->
                    FilterChip(
                        selected = state.targetLanguage == language,
                        onClick = { onTargetLanguageChange(language) },
                        // Target languages read as endonyms, hence the mapper.
                        label = { Text(language.displayLabel()) },
                    )
                }
            }
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

        CloudSection(state, onSaveCloud, onVerifyKey)

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
            onAutoTranslateChange = {}, onSaveCloud = { _, _ -> },
            onVerifyKey = {}, onDismissMessage = {},
        )
    }
}
