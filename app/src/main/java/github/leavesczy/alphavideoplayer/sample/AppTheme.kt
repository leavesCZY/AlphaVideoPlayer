package github.leavesczy.alphavideoplayer.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Blue = Color(color = 0xFF1565C0)

private val BlueDark = Color(color = 0xFF0D47A1)

private val Ink = Color(color = 0xFF1A237E)

private val InkMuted = Color(color = 0xFF607D8B)

internal val PageBg = Color(color = 0xFFFFFFFF)

private val SurfaceSoft = Color(color = 0xFFF5F9FC)

private val SampleTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp
    )
)

private val SampleColorScheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(color = 0xFFE3F2FD),
    onPrimaryContainer = BlueDark,
    secondary = BlueDark,
    onSecondary = Color.White,
    background = PageBg,
    onBackground = Ink,
    surface = PageBg,
    onSurface = Ink,
    surfaceVariant = SurfaceSoft,
    onSurfaceVariant = InkMuted,
    outline = Color(color = 0xFFBBDEFB)
)

@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SampleColorScheme,
        typography = SampleTypography
    ) {
        content()
    }
}
