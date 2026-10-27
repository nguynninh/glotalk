package vn.onplay.vdubbing.presentation.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import vn.onplay.vdubbing.domain.model.ThemeMode

/**
 * Brand palette. Deliberately *not* Material You: the point of an explicit
 * Sáng/Tối switch is that the two look different, and a wallpaper-derived
 * scheme can render both nearly the same and can wash out the status colours
 * (đỏ "chưa bật" / xanh "đã bật") the settings screen relies on.
 */
private val BrandIndigo = Color(0xFF4F46E5)
private val BrandIndigoLight = Color(0xFFB8B4FF)
private val BrandTeal = Color(0xFF0E7C86)
private val BrandTealLight = Color(0xFF7FD4DC)
private val BrandAmber = Color(0xFFB45309)
private val BrandAmberLight = Color(0xFFFFC16B)

private val LightColors = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E1FF),
    onPrimaryContainer = Color(0xFF14067A),
    secondary = BrandTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC7EFF2),
    onSecondaryContainer = Color(0xFF00201F),
    tertiary = BrandAmber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE7C2),
    onTertiaryContainer = Color(0xFF3B2100),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceContainer = Color(0xFFF1EDF7),
    surfaceContainerHigh = Color(0xFFEBE8F2),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C5D0),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BrandIndigoLight,
    onPrimary = Color(0xFF24108F),
    primaryContainer = Color(0xFF3A31B8),
    onPrimaryContainer = Color(0xFFE3E0FF),
    secondary = BrandTealLight,
    onSecondary = Color(0xFF00363B),
    secondaryContainer = Color(0xFF00535A),
    onSecondaryContainer = Color(0xFFC7EFF2),
    tertiary = BrandAmberLight,
    onTertiary = Color(0xFF5C3800),
    tertiaryContainer = Color(0xFF7A4E00),
    onTertiaryContainer = Color(0xFFFFE7C2),
    background = Color(0xFF121218),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF121218),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    surfaceContainer = Color(0xFF1E1E26),
    surfaceContainerHigh = Color(0xFF282830),
    outline = Color(0xFF928F9A),
    outlineVariant = Color(0xFF47464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** Resolves the user's choice against the device setting. */
@Composable
fun ThemeMode.resolveDark(): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun VDubbingTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = themeMode.resolveDark()
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            // The app draws edge to edge, so the bars are transparent and sit on
            // the Scaffold background. Only the icon tint has to follow the
            // scheme — light icons on a light background are unreadable.
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
