package dev.wendelin.hausfunk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The four brand colours (docs/brand). Every scheme colour below is one of them or a tone of one. */
object Brand {
    val Navy = Color(0xFF203563)
    val Steel = Color(0xFF557691)
    val Sand = Color(0xFFEED1AF)
    val Amber = Color(0xFFFFC46F)
}

// Light: navy actions on warm sand-tinted surfaces.
private val LightColors = lightColorScheme(
    primary = Brand.Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2F5),
    onPrimaryContainer = Color(0xFF0E1D3F),
    inversePrimary = Brand.Amber,
    secondary = Brand.Steel,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E1EC),
    onSecondaryContainer = Color(0xFF102A3E),
    tertiary = Color(0xFF7A5200),
    onTertiary = Color.White,
    tertiaryContainer = Brand.Amber,
    onTertiaryContainer = Color(0xFF2B1A00),
    background = Color(0xFFFFF9F2),
    onBackground = Color(0xFF1B1F2A),
    surface = Color(0xFFFFF9F2),
    onSurface = Color(0xFF1B1F2A),
    surfaceVariant = Brand.Sand,
    onSurfaceVariant = Color(0xFF4A4F5C),
    surfaceTint = Brand.Navy,
    inverseSurface = Brand.Navy,
    inverseOnSurface = Color(0xFFF3EBE0),
    outline = Color(0xFF6E7380),
    outlineVariant = Color(0xFFD9C6AF),
    surfaceBright = Color(0xFFFFF9F2),
    surfaceDim = Color(0xFFE6D9C8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF2E8),
    surfaceContainer = Color(0xFFF7E8D7),
    surfaceContainerHigh = Color(0xFFF3DEC4),
    surfaceContainerHighest = Brand.Sand,
)

// Dark: amber actions on deep navy surfaces.
private val DarkColors = darkColorScheme(
    primary = Brand.Amber,
    onPrimary = Brand.Navy,
    primaryContainer = Color(0xFF5A3F0A),
    onPrimaryContainer = Color(0xFFFFDDB0),
    inversePrimary = Brand.Navy,
    secondary = Brand.Sand,
    onSecondary = Brand.Navy,
    secondaryContainer = Brand.Steel,
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFA9C3D9),
    onTertiary = Color(0xFF142A3D),
    tertiaryContainer = Color(0xFF3D5A73),
    onTertiaryContainer = Color(0xFFD6E3EE),
    background = Color(0xFF121A2E),
    onBackground = Color(0xFFF3EBE0),
    surface = Color(0xFF121A2E),
    onSurface = Color(0xFFF3EBE0),
    surfaceVariant = Color(0xFF2C3D63),
    onSurfaceVariant = Color(0xFFD3C8BA),
    surfaceTint = Brand.Amber,
    inverseSurface = Brand.Sand,
    inverseOnSurface = Brand.Navy,
    outline = Color(0xFF8D93A0),
    outlineVariant = Color(0xFF3A4766),
    surfaceBright = Color(0xFF2C3D63),
    surfaceDim = Color(0xFF121A2E),
    surfaceContainerLowest = Color(0xFF0D1424),
    surfaceContainerLow = Color(0xFF172138),
    surfaceContainer = Color(0xFF1B2741),
    surfaceContainerHigh = Brand.Navy,
    surfaceContainerHighest = Color(0xFF2C3D63),
)

/** Material 3 with fixed brand colours, so the app looks the same on every phone. */
@Composable
fun HausfunkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
