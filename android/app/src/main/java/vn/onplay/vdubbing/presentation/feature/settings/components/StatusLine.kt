package vn.onplay.vdubbing.presentation.feature.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun StatusLine(label: String, ok: Boolean, detail: String? = null) {
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

@Preview(name = "StatusLine light", showBackground = true)
@Preview(name = "StatusLine dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusLinePreview() {
    VDubbingTheme {
        Column {
            StatusLine("Accessibility", true)
            StatusLine("Notifications", false)
            StatusLine("Accessibility", true, "Đang khởi động")
        }
    }
}
