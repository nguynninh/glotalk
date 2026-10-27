package vn.onplay.vdubbing.presentation.feature.texttranslation.components

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun InputButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    prominent: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = if (compact) {
            modifier.height(40.dp).background(Color(0xFFF2F2F2), CircleShape)
        } else {
            modifier.size(if (prominent) 64.dp else 48.dp)
                .background(if (prominent) Color.White else Color(0xFF304675), CircleShape)
                .border(1.dp, if (prominent) Color.White else Color(0xFF4C6191), CircleShape)
        },
    ) {
        Icon(
            painterResource(icon), stringResource(label), Modifier.size(if (compact) 18.dp else 24.dp),
            tint = if (prominent || compact) Color(0xFF171717) else Color.White,
        )
    }
}

@Preview(name = "InputButton light", showBackground = true)
@Preview(name = "InputButton dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InputButtonPreview() {
    VDubbingTheme {
        Row {
            InputButton(R.drawable.ic_text_mic, R.string.text_voice_input, false, {})
            InputButton(R.drawable.ic_text_edit, R.string.text_keyboard, true, {})
            InputButton(R.drawable.ic_text_camera, R.string.text_camera, false, {}, compact = true)
        }
    }
}
