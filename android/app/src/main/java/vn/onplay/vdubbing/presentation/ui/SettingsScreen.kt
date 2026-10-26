package vn.onplay.vdubbing.presentation.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.ThemeMode

/**
 * Stateless: every value arrives in [state] and every interaction leaves through
 * a callback. The only local state is text the user is mid-way through typing.
 *
 * All copy comes from resources, so a language change re-renders this whole
 * screen without any state change.
 */
@Composable
fun SettingsScreen(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
    onTranslateNow: () -> Unit,
    onHideOverlay: () -> Unit,
    onShowBubble: () -> Unit,
    onTargetLanguageChange: (Language) -> Unit,
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

@Composable
private fun StatusCard(
    state: AppUiState,
    onOpenAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.settings_section_status),
                style = MaterialTheme.typography.titleMedium,
            )
            StatusLine(
                label = stringResource(R.string.status_accessibility),
                ok = state.accessibilityReady,
                detail = when {
                    state.accessibilityNeedsRebind -> stringResource(R.string.status_needs_rebind)
                    state.accessibilityReady && !state.accessibilityLive ->
                        stringResource(R.string.status_starting)
                    else -> null
                },
            )

            if (state.accessibilityNeedsRebind) {
                // Android keeps the toggle on after an app update but stops
                // rebinding, so telling the user to toggle it is the only fix.
                Card {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.status_rebind_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onOpenAccessibility) {
                            Text(stringResource(R.string.status_open_accessibility_settings))
                        }
                    }
                }
            }
            StatusLine(stringResource(R.string.status_background_service), state.serviceRunning)
            StatusLine(stringResource(R.string.status_notifications), state.notificationsGranted)
            StatusLine(stringResource(R.string.status_cloud), state.cloudActive)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Always available: users need it to turn the service back off too.
                Button(onClick = onOpenAccessibility) {
                    Text(
                        stringResource(
                            if (state.accessibilityReady) {
                                R.string.status_accessibility_settings
                            } else {
                                R.string.status_enable_accessibility
                            },
                        ),
                    )
                }
                if (!state.notificationsGranted) {
                    OutlinedButton(onClick = onRequestNotifications) {
                        Text(stringResource(R.string.status_enable_notifications))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            text = when {
                detail != null -> detail
                ok -> stringResource(R.string.state_on)
                else -> stringResource(R.string.state_off)
            },
            color = when {
                detail != null -> MaterialTheme.colorScheme.tertiary
                ok -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun CloudSection(
    state: AppUiState,
    onSaveCloud: (String, Boolean) -> Unit,
    onVerifyKey: (String) -> Unit,
) {
    var key by remember(state.savedKey) { mutableStateOf(state.savedKey) }
    var enabled by remember(state.cloudEnabled) { mutableStateOf(state.cloudEnabled) }
    var revealed by remember { mutableStateOf(false) }

    Section(stringResource(R.string.cloud_section)) {
        Text(
            stringResource(R.string.cloud_description),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.cloud_api_key_label)) },
            singleLine = true,
            visualTransformation = if (revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cloud_enable), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { revealed = !revealed }) {
                Text(
                    stringResource(
                        if (revealed) R.string.cloud_hide_key else R.string.cloud_show_key,
                    ),
                )
            }
            OutlinedButton(onClick = { onVerifyKey(key) }, enabled = key.isNotBlank()) {
                Text(stringResource(R.string.cloud_verify_key))
            }
            Button(onClick = { onSaveCloud(key, enabled) }) {
                Text(stringResource(R.string.action_save))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (enabled && key.isNotBlank()) {
                stringResource(
                    R.string.cloud_status_both,
                    stringResource(R.string.engine_on_device),
                    stringResource(R.string.engine_cloud),
                )
            } else {
                stringResource(
                    R.string.cloud_status_local,
                    stringResource(R.string.engine_on_device),
                )
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
