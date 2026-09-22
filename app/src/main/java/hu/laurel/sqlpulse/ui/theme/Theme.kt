package hu.laurel.sqlpulse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Every Material slot is filled from the §8 palette. Left to the defaults, the baseline purple
 * shows up wherever a component reaches for a slot nobody set — a selected chip, a switch track,
 * the tonal button — and the screen stops reading as one system.
 */
private val DarkScheme = darkColorScheme(
    primary = SqlPulseColors.DarkAccent,
    // Dark text on the accent: white on #4D9FFF does not reach 4.5:1.
    onPrimary = SqlPulseColors.DarkBackground,
    primaryContainer = Color(0xFF16283D),
    onPrimaryContainer = Color(0xFFCFE4FF),
    inversePrimary = SqlPulseColors.LightAccent,
    secondary = SqlPulseColors.DarkTextSecondary,
    onSecondary = SqlPulseColors.DarkBackground,
    secondaryContainer = Color(0xFF1B2A3C),
    onSecondaryContainer = SqlPulseColors.DarkTextPrimary,
    tertiary = SqlPulseColors.CellDate,
    onTertiary = SqlPulseColors.DarkBackground,
    tertiaryContainer = Color(0xFF2A2338),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = SqlPulseColors.DarkBackground,
    onBackground = SqlPulseColors.DarkTextPrimary,
    surface = SqlPulseColors.DarkSurface,
    onSurface = SqlPulseColors.DarkTextPrimary,
    surfaceVariant = SqlPulseColors.DarkSurfaceRaised,
    onSurfaceVariant = SqlPulseColors.DarkTextSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = SqlPulseColors.DarkTextPrimary,
    inverseOnSurface = SqlPulseColors.DarkBackground,
    error = SqlPulseColors.DarkError,
    onError = Color(0xFF14080A),
    errorContainer = Color(0xFF3A1F22),
    onErrorContainer = Color(0xFFFFD9D9),
    outline = Color(0xFF3A3F48),
    outlineVariant = Color(0xFF252930),
    scrim = Color.Black,
    surfaceBright = Color(0xFF272C35),
    surfaceDim = SqlPulseColors.DarkBackground,
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF14171C),
    surfaceContainer = SqlPulseColors.DarkSurface,
    surfaceContainerHigh = SqlPulseColors.DarkSurfaceRaised,
    surfaceContainerHighest = Color(0xFF272C35),
)

private val LightScheme = lightColorScheme(
    primary = SqlPulseColors.LightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFA),
    onPrimaryContainer = Color(0xFF06325F),
    inversePrimary = SqlPulseColors.DarkAccent,
    secondary = SqlPulseColors.LightTextSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EEFA),
    onSecondaryContainer = SqlPulseColors.LightTextPrimary,
    tertiary = Color(0xFF7A4FC2),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE4FA),
    onTertiaryContainer = Color(0xFF2E1660),
    background = SqlPulseColors.LightBackground,
    onBackground = SqlPulseColors.LightTextPrimary,
    surface = SqlPulseColors.LightSurface,
    onSurface = SqlPulseColors.LightTextPrimary,
    surfaceVariant = SqlPulseColors.LightSurfaceRaised,
    onSurfaceVariant = SqlPulseColors.LightTextSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = SqlPulseColors.LightTextPrimary,
    inverseOnSurface = SqlPulseColors.LightBackground,
    error = SqlPulseColors.LightError,
    onError = Color.White,
    errorContainer = Color(0xFFFBE3E3),
    onErrorContainer = Color(0xFF5C0F0F),
    outline = Color(0xFFC9CDD3),
    outlineVariant = Color(0xFFE3E5E8),
    scrim = Color.Black,
    surfaceBright = SqlPulseColors.LightSurface,
    surfaceDim = Color(0xFFE6E8EB),
    surfaceContainerLowest = SqlPulseColors.LightSurface,
    surfaceContainerLow = SqlPulseColors.LightBackground,
    surfaceContainer = SqlPulseColors.LightSurface,
    surfaceContainerHigh = SqlPulseColors.LightSurfaceRaised,
    surfaceContainerHighest = Color(0xFFE9EBEE),
)

/**
 * Corner radii from §8 for the components that take theirs from the theme: text fields and menus
 * 12, chips fully round, cards 16, dialogs and bottom sheets 24.
 */
private val SqlPulseShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(percent = 50),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * The top bar sits on the screen's background rather than on a surface of its own: the data under
 * it is what should stand out, not the chrome above it (§8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun sqlPulseTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
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
            shapes = SqlPulseShapes,
            content = content,
        )
    }
}
