package vn.onplay.vdubbing.presentation.feature.texttranslation.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun TranslationActions(
    text: String,
    language: Language,
    onSpeak: (String, Language) -> Unit,
    onCopy: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(
            onClick = { onSpeak(text, language) },
            modifier = Modifier.background(Color(0xFFF2F2F2), CircleShape),
        ) {
            Icon(painterResource(R.drawable.ic_text_speak), stringResource(R.string.text_speak), tint = Color.Black)
        }
        IconButton(
            onClick = { onCopy(text) },
            modifier = Modifier.background(Color(0xFFF2F2F2), CircleShape),
        ) {
            Icon(painterResource(R.drawable.ic_text_copy), stringResource(R.string.text_copy), tint = Color.Black)
        }
    }
}

@Preview(name = "TranslationActions light", showBackground = true)
@Preview(name = "TranslationActions dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TranslationActionsPreview() {
    VDubbingTheme {
        TranslationActions("你好", Language.CHINESE, { _, _ -> }, {})
    }
}
