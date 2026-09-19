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
    /** Raised surface: bottom sheets, and the grid header that stays put over the rows (§8). */
    val surfaceRaised: Color,
)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(
        success = SqlPulseColors.DarkSuccess,
        warning = SqlPulseColors.DarkWarning,
        danger = SqlPulseColors.DarkError,
        production = SqlPulseColors.Production,
        hairline = SqlPulseColors.HairlineDark,
        textSecondary = SqlPulseColors.DarkTextSecondary,
        surfaceRaised = SqlPulseColors.DarkSurfaceRaised,
    )
}

enum class ThemePreference { System, Light, Dark }

/**
 * The grid's text size, as a percentage of the default.
 *
 * A composition local rather than a parameter threaded through every screen: the grid appears in
 * four places, and the setting has to reach all of them or it is the kind of slider that moves
 * and changes nothing.
 */
val LocalGridFontScale = staticCompositionLocalOf { 100 }

@Composable
fun SqlPulseTheme(
    preference: ThemePreference = ThemePreference.System,
    /** The result grid's text size, as a percentage of the default. */
    gridFontScale: Int = 100,
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
            surfaceRaised = SqlPulseColors.DarkSurfaceRaised,
        )
    } else {
        SemanticColors(
            success = SqlPulseColors.LightSuccess,
            warning = SqlPulseColors.LightWarning,
            danger = SqlPulseColors.LightError,
            production = SqlPulseColors.Production,
            hairline = SqlPulseColors.HairlineLight,
            textSecondary = SqlPulseColors.LightTextSecondary,
            surfaceRaised = SqlPulseColors.LightSurfaceRaised,
        )
    }
    CompositionLocalProvider(
        LocalSemanticColors provides semantic,
        LocalGridFontScale provides gridFontScale,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = SqlPulseTypography,
            content = content,
        )
    }
}
