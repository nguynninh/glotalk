package vn.onplay.vdubbing.presentation.feature.flashcard.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun ReviewButton(icon: Int, label: Int, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = color)) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp))
    }
}

@Preview(name = "ReviewButton light", showBackground = true)
@Preview(name = "ReviewButton dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewButtonPreview() {
    VDubbingTheme {
        Row {
            ReviewButton(R.drawable.ic_card_play, R.string.cards_play, MaterialTheme.colorScheme.primary) {}
            ReviewButton(R.drawable.ic_card_undo, R.string.cards_previous, MaterialTheme.colorScheme.onSurface, enabled = false) {}
        }
    }
}
