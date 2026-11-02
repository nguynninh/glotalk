package vn.onplay.vdubbing.presentation.feature.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Preview(name = "Settings group light", showBackground = true)
@Preview(name = "Settings group dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsGroupPreview() {
    VDubbingTheme {
        SettingsGroup("Giao diện") { Text("Nội dung cài đặt") }
    }
}
