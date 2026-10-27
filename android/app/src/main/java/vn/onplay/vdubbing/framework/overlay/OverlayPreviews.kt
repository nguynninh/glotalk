package vn.onplay.vdubbing.framework.overlay

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Preview(name = "Overlay panel light", showBackground = true, widthDp = 390)
@Preview(name = "Overlay panel dark", showBackground = true, widthDp = 390, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OverlayPanelContentPreview() {
    VDubbingTheme {
        AndroidView(factory = { context ->
            OverlayPanelContent(context, OverlayUi(context, localize = { it }), { null }, {}, {}, {}).build(PanelState())
        })
    }
}

@Preview(name = "Overlay menu light", showBackground = true, widthDp = 390)
@Preview(name = "Overlay menu dark", showBackground = true, widthDp = 390, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OverlayMenuContentPreview() {
    VDubbingTheme {
        AndroidView(factory = { context ->
            OverlayMenuContent(context, OverlayUi(context, localize = { it })).build(PanelState(), {}, {}, {}, {}, {})
        })
    }
}

@Preview(name = "ProgressRingView light", showBackground = true)
@Preview(name = "ProgressRingView dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProgressRingViewPreview() {
    VDubbingTheme {
        AndroidView(
            factory = { ProgressRingView(it) },
            modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Preview(name = "TranslationOverlayView light", showBackground = true)
@Preview(name = "TranslationOverlayView dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TranslationOverlayViewPreview() {
    VDubbingTheme {
        AndroidView(
            factory = { context ->
                val density = context.resources.displayMetrics.density
                TranslationOverlayView(context, listOf(OverlayText(
                    left = 0, top = 0,
                    boxWidth = (280 * density).toInt(), boxHeight = (80 * density).toInt(),
                    text = "Xin chào", preferredSize = 24 * density, minSize = 12 * density,
                    isParagraph = false, backgroundColor = android.graphics.Color.WHITE, outlineWidth = 0f,
                )))
            },
            modifier = Modifier.size(280.dp, 80.dp),
        )
    }
}
