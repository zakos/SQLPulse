package hu.laurel.sqlpulse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = SqlPulseColors.DarkAccent,
    onPrimary = Color(0xFF07131F),
    background = SqlPulseColors.DarkBackground,
    onBackground = SqlPulseColors.DarkTextPrimary,
    surface = SqlPulseColors.DarkSurface,
    onSurface = SqlPulseColors.DarkTextPrimary,
    surfaceVariant = SqlPulseColors.DarkSurfaceRaised,
    onSurfaceVariant = SqlPulseColors.DarkTextSecondary,
    error = SqlPulseColors.DarkError,
    outline = SqlPulseColors.DarkTextSecondary,
)

private val LightScheme = lightColorScheme(
    primary = SqlPulseColors.LightAccent,
    onPrimary = Color.White,
    background = SqlPulseColors.LightBackground,
    onBackground = SqlPulseColors.LightTextPrimary,
    surface = SqlPulseColors.LightSurface,
    onSurface = SqlPulseColors.LightTextPrimary,
    surfaceVariant = SqlPulseColors.LightSurfaceRaised,
    onSurfaceVariant = SqlPulseColors.LightTextSecondary,
    error = SqlPulseColors.LightError,
    outline = SqlPulseColors.LightTextSecondary,
)

/** Semantic colours Material3 has no slot for. */
data class SemanticColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val production: Color,
    val hairline: Color,
    val textSecondary: Color,
)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(
        success = SqlPulseColors.DarkSuccess,
        warning = SqlPulseColors.DarkWarning,
        danger = SqlPulseColors.DarkError,
        production = SqlPulseColors.Production,
        hairline = SqlPulseColors.HairlineDark,
        textSecondary = SqlPulseColors.DarkTextSecondary,
    )
}

enum class ThemePreference { System, Light, Dark }

@Composable
fun SqlPulseTheme(
    preference: ThemePreference = ThemePreference.System,
    content: @Composable () -> Unit,
) {
    // Dark is the default: the typical use is in poor light, on the move (§8).
    val dark = when (preference) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    val semantic = if (dark) {
        SemanticColors(
            success = SqlPulseColors.DarkSuccess,
            warning = SqlPulseColors.DarkWarning,
            danger = SqlPulseColors.DarkError,
            production = SqlPulseColors.Production,
            hairline = SqlPulseColors.HairlineDark,
            textSecondary = SqlPulseColors.DarkTextSecondary,
        )
    } else {
        SemanticColors(
            success = SqlPulseColors.LightSuccess,
            warning = SqlPulseColors.LightWarning,
            danger = SqlPulseColors.LightError,
            production = SqlPulseColors.Production,
            hairline = SqlPulseColors.HairlineLight,
            textSecondary = SqlPulseColors.LightTextSecondary,
        )
    }
    CompositionLocalProvider(LocalSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = SqlPulseTypography,
            content = content,
        )
    }
}
