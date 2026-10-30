
package vn.onplay.vdubbing.presentation.designsystem.components

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import coil3.compose.AsyncImage

/**
 * Unified icon source for the application.
 */
sealed interface AppIconSource {

    data class PainterSource(
        val painter: Painter
    ) : AppIconSource

    data class VectorSource(
        val imageVector: ImageVector
    ) : AppIconSource

    data class DrawableSource(
        @DrawableRes val resId: Int
    ) : AppIconSource

    data class BitmapSource(
        val bitmap: ImageBitmap
    ) : AppIconSource

    data class UriSource(
        val uri: Uri
    ) : AppIconSource

    data class UrlSource(
        val url: String
    ) : AppIconSource
}

@Composable
fun AppIcon(
    source: AppIconSource,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = Color.Unspecified,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    onClick: (() -> Unit)? = null,
) {
    val iconModifier = if (onClick != null) {
        modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        modifier
    }
    when (source) {

        is AppIconSource.PainterSource -> {
            Icon(
                painter = source.painter,
                contentDescription = contentDescription,
                modifier = iconModifier,
                tint = tint
            )
        }

        is AppIconSource.VectorSource -> {
            Icon(
                imageVector = source.imageVector,
                contentDescription = contentDescription,
                modifier = iconModifier,
                tint = tint
            )
        }

        is AppIconSource.DrawableSource -> {
            if (tint == Color.Unspecified) {
                Image(
                    painter = painterResource(source.resId),
                    contentDescription = contentDescription,
                    modifier = iconModifier,
                    contentScale = contentScale,
                    alignment = alignment
                )
            } else {
                Icon(
                    painter = painterResource(source.resId),
                    contentDescription = contentDescription,
                    modifier = iconModifier,
                    tint = tint
                )
            }
        }

        is AppIconSource.BitmapSource -> {
            if (tint == Color.Unspecified) {
                Image(
                    bitmap = source.bitmap,
                    contentDescription = contentDescription,
                    modifier = iconModifier,
                    contentScale = contentScale,
                    alignment = alignment
                )
            } else {
                Image(
                    bitmap = source.bitmap,
                    contentDescription = contentDescription,
                    modifier = iconModifier,
                    contentScale = contentScale,
                    alignment = alignment,
                    colorFilter = ColorFilter.tint(tint)
                )
            }
        }

        is AppIconSource.UriSource -> {
            AsyncImage(
                model = source.uri,
                contentDescription = contentDescription,
                modifier = iconModifier,
                contentScale = contentScale,
                alignment = alignment,
                colorFilter = if (tint == Color.Unspecified) {
                    null
                } else {
                    ColorFilter.tint(tint)
                }
            )
        }

        is AppIconSource.UrlSource -> {
            AsyncImage(
                model = source.url,
                contentDescription = contentDescription,
                modifier = iconModifier,
                contentScale = contentScale,
                alignment = alignment,
                colorFilter = if (tint == Color.Unspecified) {
                    null
                } else {
                    ColorFilter.tint(tint)
                }
            )
        }
    }
}
