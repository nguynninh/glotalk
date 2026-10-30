package vn.onplay.vdubbing.presentation.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.designsystem.help.flag
import vn.onplay.vdubbing.presentation.designsystem.help.flagUrl
import vn.onplay.vdubbing.presentation.designsystem.help.translationLabel
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun LanguageFlag(language: Language, modifier: Modifier = Modifier) {
    val label = language.translationLabel()
    val fallback: @Composable () -> Unit = { Text(language.flag(), fontSize = 24.sp) }
    val url = language.flagUrl()
    Box(
        modifier.clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (url == null || LocalInspectionMode.current) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                loading = { fallback() },
                error = { fallback() },
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LanguageFlagPreview() {
    VDubbingTheme {
        Row { Language.TARGETS.forEach { LanguageFlag(it, Modifier.size(36.dp)) } }
    }
}
