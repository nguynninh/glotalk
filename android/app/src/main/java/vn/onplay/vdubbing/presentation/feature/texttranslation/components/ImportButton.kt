package vn.onplay.vdubbing.presentation.feature.texttranslation.components

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun ImportButton(@StringRes label: Int, @DrawableRes icon: Int, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = Color(0xFFE4EDF9), contentColor = Color(0xFF203A68),
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(stringResource(label), fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Icon(painterResource(icon), null, Modifier.size(16.dp))
    }
}

@Preview(name = "ImportButton light", showBackground = true)
@Preview(name = "ImportButton dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ImportButtonPreview() {
    VDubbingTheme {
        ImportButton(R.string.text_import_file, R.drawable.ic_text_upload, {})
    }
}
