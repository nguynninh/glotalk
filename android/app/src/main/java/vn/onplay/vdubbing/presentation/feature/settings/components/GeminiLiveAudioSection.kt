package vn.onplay.vdubbing.presentation.feature.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.components.card.Section
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.settings.SettingsUiState

@Composable
internal fun GeminiLiveAudioSection(state: SettingsUiState, onSave: (String, Boolean) -> Unit) {
    var key by remember(state.geminiLiveKey) { mutableStateOf(state.geminiLiveKey) }
    var enabled by remember(state.geminiLiveAudioEnabled) { mutableStateOf(state.geminiLiveAudioEnabled) }
    var revealed by remember { mutableStateOf(false) }
    Section(stringResource(R.string.gemini_live_section)) {
        Text(stringResource(R.string.gemini_live_description), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.gemini_live_key_label)) },
            singleLine = true,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gemini_live_enable), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { revealed = !revealed }) {
                Text(stringResource(if (revealed) R.string.cloud_hide_key else R.string.cloud_show_key))
            }
            Button(onClick = { onSave(key, enabled) }) { Text(stringResource(R.string.action_save)) }
        }
        Text(stringResource(R.string.gemini_live_restart_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(name = "Gemini Live audio light", showBackground = true)
@Preview(name = "Gemini Live audio dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GeminiLiveAudioPreview() {
    VDubbingTheme { GeminiLiveAudioSection(SettingsUiState(), { _, _ -> }) }
}
