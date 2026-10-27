package vn.onplay.vdubbing.presentation.feature.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.settings.SettingsUiState

@Composable
internal fun StatusCard(
    state: SettingsUiState,
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

@Preview(name = "StatusCard light", showBackground = true)
@Preview(name = "StatusCard dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusCardPreview() {
    VDubbingTheme {
        StatusCard(SettingsUiState(), {}, {})
    }
}
