package vn.onplay.vdubbing.presentation.feature.home.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun HomeBlurLayout(
    showNavigation: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Dp) -> Unit,
    navigation: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val navigationHeight = if (showNavigation) 54.dp else 0.dp
    Box(modifier) {
        Box(Modifier.fillMaxSize()) { content(navigationHeight) }
        if (showNavigation) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(colors.surfaceContainer),
            ) { navigation() }
        }
    }
}

@Preview(name = "Blur navigation light", widthDp = 390, heightDp = 160)
@Preview(name = "Blur navigation dark", widthDp = 390, heightDp = 160, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeBlurLayoutPreview() {
    VDubbingTheme {
        HomeBlurLayout(
            showNavigation = true,
            modifier = Modifier.fillMaxSize(),
            content = { Text("Nội dung") },
            navigation = {
                Box(Modifier.height(54.dp), contentAlignment = Alignment.Center) {
                    Text("Dịch màn hình     Dịch văn bản")
                }
            },
        )
    }
}
