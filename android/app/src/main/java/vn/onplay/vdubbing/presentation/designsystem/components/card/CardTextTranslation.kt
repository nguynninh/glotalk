
package vn.onplay.vdubbing.presentation.designsystem.components.card

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.components.AppIcon
import vn.onplay.vdubbing.presentation.designsystem.components.AppIconSource
import vn.onplay.vdubbing.presentation.designsystem.components.header.HeaderTranslate
import vn.onplay.vdubbing.presentation.designsystem.components.text.TextAriaTranslate
import vn.onplay.vdubbing.presentation.designsystem.uimodel.TranslateUiState

@Composable
fun CardTranslate(
    source: TranslateUiState,
    target: TranslateUiState,
    isLoading: Boolean = false,

    onCopy: ((isTarget: Boolean) -> Unit)? = null,
    onSpeak: ((isTarget: Boolean) -> Unit)? = null,
    onSave: ((isTarget: Boolean) -> Unit)? = null,

    @SuppressLint("ModifierParameter")
    modifier: Modifier = Modifier,
    sourceContent: (@Composable () -> Unit)? = null,
    showTranslation: Boolean = true,
    onSwapLanguages: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isEmpty = !showTranslation && source.text.isBlank() && target.text.isBlank()
    var swapRotation by remember { mutableFloatStateOf(0f) }
    val animatedSwapRotation by animateFloatAsState(
        targetValue = swapRotation,
        animationSpec = tween(durationMillis = 400),
        label = "swapRotation",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEmpty) Color.Transparent else colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(if (isEmpty) 0.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (showTranslation) HeaderTranslate(
                language = source.language,
                color = colorScheme.onSurfaceVariant,
                showSave = true,
                onSave = onSave?.let { save -> { save(false) } },
            )

            if (sourceContent != null) {
                sourceContent()
            } else Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = source.text,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurface,
                    lineHeight = 26.sp
                )

                if (!source.description.isNullOrBlank()) {
                    Text(
                        text = source.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (showTranslation) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    AppIcon(
                        source = AppIconSource.DrawableSource(R.drawable.ic_overlay_swap),
                        modifier = Modifier.size(20.dp).rotate(animatedSwapRotation),
                        contentDescription = stringResource(R.string.panel_swap_languages),
                        tint = colorScheme.primary,
                        onClick = onSwapLanguages?.let { swap ->
                            {
                                swapRotation += 360f
                                swap()
                            }
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                }

                HeaderTranslate(
                    language = target.language,
                    showSave = true,
                    isLoading = isLoading,
                    onSave = onSave?.let { save -> { save(true) } },
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colorScheme.primary,
                        trackColor = colorScheme.surfaceVariant
                    )
                } else if (target.text.isNotEmpty()) {
                    TextAriaTranslate(
                        text = target.text,
                        subtitle = target.description,
                        onCopy = onCopy?.let { copy -> { copy(true) } },
                        onSpeak = onSpeak?.let { speak -> { speak(true) } },
                    )
                }
            }
        }
    }
}

@Preview(name = "English → Chinese · Light", showBackground = true, widthDp = 380)
@Composable
private fun CardTranslateLightPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface {
            CardTranslatePreviewContent()
        }
    }
}

@Preview(
    name = "English → Chinese · Dark",
    showBackground = true,
    widthDp = 380,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CardTranslateDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface {
            CardTranslatePreviewContent()
        }
    }
}

@Preview(name = "Loading", showBackground = true, widthDp = 380)
@Composable
private fun CardTranslateLoadingPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface {
            CardTranslatePreviewContent(isLoading = true)
        }
    }
}

@Composable
private fun CardTranslatePreviewContent(isLoading: Boolean = false) {
    CardTranslate(
        source = TranslateUiState(
            language = "English",
            text = "Hello! How are you doing today?",
            description = "A friendly greeting in everyday conversation.",
        ),
        target = TranslateUiState(
            language = "Chinese",
            text = if (isLoading) "" else "你好！你今天过得怎么样？",
            description = if (isLoading) null else "Nǐ hǎo! Nǐ jīntiān guò de zěnmeyàng?",
        ),
        modifier = Modifier.padding(16.dp),
        isLoading = isLoading,
        onCopy = {},
        onSpeak = {},
        onSave = {},
        onSwapLanguages = {},
    )
}
