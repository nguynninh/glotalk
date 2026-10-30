package vn.onplay.vdubbing.presentation.designsystem.components.header

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.components.AppIcon
import vn.onplay.vdubbing.presentation.designsystem.components.AppIconSource

@Composable
fun HeaderTranslate(
    language: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    showSave: Boolean = true,
    isLoading: Boolean = false,
    onSave: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = color,
                        shape = CircleShape
                    )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = language,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }

        if (showSave) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = onSave != null && !isLoading,
                        role = Role.Button,
                        onClick = { onSave?.invoke() },
                    ),
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .blur(4.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            CircleShape,
                        ),
                )
                AppIcon(
                    source = AppIconSource.DrawableSource(R.drawable.ic_history_bookmark),
                    contentDescription = stringResource(R.string.action_save),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Preview(name = "HeaderTranslate light", showBackground = true)
@Composable
private fun HeaderTranslateLightPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        HeaderTranslate(language = "Chinese", onSave = {})
    }
}

@Preview(
    name = "HeaderTranslate dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HeaderTranslateDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        HeaderTranslate(language = "Chinese", onSave = {})
    }
}
