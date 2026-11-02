package vn.onplay.vdubbing.presentation.feature.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun StatusLine(
    label: String,
    ok: Boolean,
    detail: String? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Switch(
            checked = ok,
            onCheckedChange = onCheckedChange,
            enabled = onCheckedChange != null,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Preview(name = "StatusLine light", showBackground = true)
@Preview(name = "StatusLine dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusLinePreview() {
    VDubbingTheme {
        Column {
            StatusLine("Accessibility", true, onCheckedChange = {})
            StatusLine("Notifications", false, onCheckedChange = {})
            StatusLine("Accessibility", true, "Đang khởi động")
        }
    }
}
