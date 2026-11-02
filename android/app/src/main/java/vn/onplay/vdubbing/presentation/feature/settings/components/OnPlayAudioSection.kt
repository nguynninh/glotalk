package vn.onplay.vdubbing.presentation.feature.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun OnPlayAudioSection(enabled: Boolean, onChange: (Boolean) -> Unit) {
    SettingsGroup(stringResource(R.string.onplay_audio_title)) {
        Text(stringResource(R.string.onplay_audio_description), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.onplay_audio_enable), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = onChange)
        }
        Text(stringResource(R.string.gemini_live_restart_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(name = "ONPlay light", showBackground = true)
@Preview(name = "ONPlay dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnPlayAudioPreview() {
    VDubbingTheme { OnPlayAudioSection(false, {}) }
}
