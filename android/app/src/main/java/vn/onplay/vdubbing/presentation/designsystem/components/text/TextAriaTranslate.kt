package vn.onplay.vdubbing.presentation.designsystem.components.text

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.onplay.vdubbing.presentation.designsystem.components.AppIcon
import vn.onplay.vdubbing.presentation.designsystem.components.AppIconSource
import vn.onplay.vdubbing.R

@Composable
fun TextAriaTranslate(
    text: String,
    subtitle: String? = null,
    @SuppressLint("ModifierParameter")
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val clipboardManager = LocalClipboardManager.current

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.dp),
            shape = RoundedCornerShape(12.dp),
            color = colors.primaryContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 12.dp,
                        top = 10.dp,
                        bottom = 10.dp,
                        end = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = text,
                        color = colors.onPrimaryContainer,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 26.sp,
                    )

                    if (subtitle != null) Text(
                        text = subtitle,
                        color = colors.onPrimaryContainer.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.Top),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val actions = listOf(
                        Triple(R.drawable.ic_text_speak, R.string.text_speak, {
                            onSpeak?.invoke()
                            Unit
                        }),
                        Triple(R.drawable.ic_text_copy, R.string.text_copy, {
                            clipboardManager.setText(AnnotatedString(text))
                            onCopy?.invoke()
                            Unit
                        }),
                    )
                    actions.forEach { (icon, description, onClick) ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClick = onClick),
                        ) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .blur(4.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                                    .background(
                                        colors.primary.copy(alpha = 0.12f),
                                        CircleShape,
                                    ),
                            )
                            AppIcon(
                                source = AppIconSource.DrawableSource(icon),
                                contentDescription = stringResource(description),
                                tint = colors.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// Light Preview
@Preview(
    name = "TextAriaTranslate - Light",
    showBackground = true,
    backgroundColor = 0xFFFFFFFF,
)
@Composable
private fun TextAriaTranslateLightPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.padding(16.dp)) {
            TextAriaTranslate(
                text = "你好",
                subtitle = "Nǐ hǎo",
            )
        }
    }
}

// Dark Preview
@Preview(
    name = "TextAriaTranslate - Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
    backgroundColor = 0xFF0F172A,
)
@Composable
private fun TextAriaTranslateDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(
            modifier = Modifier.padding(16.dp),
            color = Color(0xFF0F172A),
        ) {
            TextAriaTranslate(
                text = "你好",
                subtitle = "Nǐ hǎo",
            )
        }
    }
}
