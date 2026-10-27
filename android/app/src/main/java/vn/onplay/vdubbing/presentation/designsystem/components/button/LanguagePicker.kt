package vn.onplay.vdubbing.presentation.designsystem.components.button

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.designsystem.help.flag
import vn.onplay.vdubbing.presentation.designsystem.help.translationLabel

@Composable
internal fun LanguagePicker(
    language: Language,
    onClick: () -> Unit,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val label = language.translationLabel()
    Box(modifier) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(if (compact) 40.dp else 24.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.textButtonColors(containerColor = Color.White, contentColor = Color.Black),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = if (compact) 4.dp else 0.dp),
        ) {
            if (compact) {
                Text(
                    language.flag(),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = label },
                    fontSize = 20.sp,
                )
            } else {
                Text(
                    when (language) {
                        Language.CHINESE -> stringResource(R.string.text_language_chinese)
                        Language.VIETNAMESE -> stringResource(R.string.text_language_vietnamese)
                        else -> label
                    },
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Preview(name = "LanguagePicker light", showBackground = true)
@Preview(name = "LanguagePicker dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LanguagePickerPreview() {
    VDubbingTheme {
        Column {
            LanguagePicker(Language.CHINESE, {}, Modifier.width(160.dp))
            LanguagePicker(Language.VIETNAMESE, {}, Modifier.width(48.dp), compact = true)
        }
    }
}
