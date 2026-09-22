package hu.laurel.sqlpulse.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour tokens from the specification (§8). Colour carries information only:
 * state, type and danger. Everything else is background.
 */
object SqlPulseColors {
    // Dark theme
    val DarkBackground = Color(0xFF0F1115)
    val DarkSurface = Color(0xFF181B21)
    val DarkSurfaceRaised = Color(0xFF20242C)
    val DarkTextPrimary = Color(0xFFE8EAED)
    val DarkTextSecondary = Color(0xFF9BA1AC)
    val DarkAccent = Color(0xFF4D9FFF)
    val DarkSuccess = Color(0xFF3DD68C)
    val DarkWarning = Color(0xFFF5B14C)
    val DarkError = Color(0xFFFF6B6B)

    // Light theme
    val LightBackground = Color(0xFFFAFAFA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceRaised = Color(0xFFF2F3F5)
    val LightTextPrimary = Color(0xFF1A1C1F)
    val LightTextSecondary = Color(0xFF5F6670)
    val LightAccent = Color(0xFF0A6ED1)
    val LightSuccess = Color(0xFF1A9E5F)
    val LightWarning = Color(0xFFB87400)
    val LightError = Color(0xFFD32F2F)

    /** Identical in both themes on purpose: a production database looks the same everywhere. */
    val Production = Color(0xFFE5484D)

    // Result-grid cell types (§8) — deliberately muted.
    val CellNumber = Color(0xFF7FD1E8)
    // A touch lighter than the §8 value (#6B7280), which falls short of 4.5:1 on the dark surface.
    val CellNull = Color(0xFF858C98)
    val CellDate = Color(0xFFC4A7F0)

    // The same three on the light theme's white, dark enough to reach 4.5:1.
    val LightCellNumber = Color(0xFF0B7A96)
    val LightCellNull = Color(0xFF6B717A)
    val LightCellDate = Color(0xFF7A4FC2)

    /** Hairline border used instead of elevation shadows on dark surfaces. */
    val HairlineDark = Color(0x10FFFFFF)
    val HairlineLight = Color(0x14000000)
}

/** Palette a connection can be tagged with; production red is not user-pickable by accident. */
enum class ConnectionColor(val value: Color) {
    Blue(Color(0xFF4D9FFF)),
    Green(Color(0xFF3DD68C)),
    Amber(Color(0xFFF5B14C)),
    Purple(Color(0xFFC4A7F0)),
    Cyan(Color(0xFF7FD1E8)),
    Grey(Color(0xFF9BA1AC)),
    Production(SqlPulseColors.Production),
    ;

    companion object {
        fun fromName(name: String?): ConnectionColor =
            entries.firstOrNull { it.name == name } ?: Blue
    }
}
