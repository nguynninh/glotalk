package vn.onplay.vdubbing.presentation.designsystem.components.card

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.presentation.designsystem.components.LanguageFlag
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
fun CardNavigationTextTranslation(
    source: Language,
    target: Language,
    onSourceClick: () -> Unit,
    onTargetClick: () -> Unit,
    onSwapLanguages: () -> Unit,
    onVoiceInput: () -> Unit,
    onCameraInput: () -> Unit,
    onKeyboardInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp)
            .shadow(6.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x18000000), spotColor = Color(0x18000000))
            .background(Color.White, RoundedCornerShape(20.dp)).padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.height(40.dp).background(Color(0xFFF2F2F2), CircleShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavigationLanguageButton(source, onSourceClick)
            IconButton(onClick = onSwapLanguages, modifier = Modifier.width(28.dp).height(40.dp)) {
                Icon(
                    painterResource(R.drawable.ic_overlay_swap),
                    stringResource(R.string.panel_swap_languages),
                    Modifier.size(20.dp),
                    tint = Color(0xFF171717),
                )
            }
            NavigationLanguageButton(target, onTargetClick)
        }
        VerticalDivider(Modifier.height(26.dp), color = Color(0xFFEAEAEA))
        NavigationInputButton(R.drawable.ic_mic, R.string.text_voice_input, onVoiceInput, Modifier.weight(1f))
        NavigationInputButton(R.drawable.ic_text_camera, R.string.text_camera, onCameraInput, Modifier.weight(1f))
        NavigationInputButton(R.drawable.ic_text_edit, R.string.text_keyboard, onKeyboardInput, Modifier.weight(1f))
    }
}

@Composable
private fun NavigationLanguageButton(language: Language, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        LanguageFlag(language, Modifier.size(24.dp))
    }
}

@Composable
private fun NavigationInputButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier.height(40.dp).background(Color(0xFFF2F2F2), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(painterResource(icon), stringResource(label), Modifier.size(18.dp), tint = Color(0xFF171717))
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CardNavigationTextTranslationPreview() {
    VDubbingTheme {
        CardNavigationTextTranslation(
            source = Language.CHINESE,
            target = Language.VIETNAMESE,
            onSourceClick = {},
            onTargetClick = {},
            onSwapLanguages = {},
            onVoiceInput = {},
            onCameraInput = {},
            onKeyboardInput = {},
        )
    }
}
