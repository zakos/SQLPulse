package hu.laurel.sqlpulse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R

/**
 * Typography from §8: Inter for the interface, JetBrains Mono for every data and code surface.
 *
 * Both are bundled rather than downloaded. The app runs on phones that may have no Play services,
 * and a font that arrives a moment after the first frame would shift the grid under the thumb.
 * Static weights, not the variable fonts: they cover the four weights the design uses and keep
 * the rendering identical on every API level from 28 up.
 */
val Sans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val Mono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

val SqlPulseTypography = Typography(
    headlineSmall = TextStyle(fontFamily = Sans, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    // The top app bar's title.
    titleLarge = TextStyle(fontFamily = Sans, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Normal),
    // Badges and section captions: small, set in caps by the caller where the design asks.
    labelSmall = TextStyle(fontFamily = Sans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
)

/**
 * Monospace styles: every data and code surface uses them (§8). Ligatures are off: JetBrains Mono
 * would draw `>=` as `≥` and `!=` as `≠`, and in SQL the characters typed are the ones to read.
 */
private const val NO_LIGATURES = "liga 0, calt 0"

object MonoStyles {
    val cell = TextStyle(fontFamily = Mono, fontSize = 13.sp, fontFeatureSettings = NO_LIGATURES)
    val cellNumber = TextStyle(fontFamily = Mono, fontSize = 13.sp, textAlign = TextAlign.End, fontFeatureSettings = NO_LIGATURES)
    val editor = TextStyle(fontFamily = Mono, fontSize = 15.sp, fontFeatureSettings = NO_LIGATURES)
    val fingerprint = TextStyle(fontFamily = Mono, fontSize = 13.sp, fontFeatureSettings = NO_LIGATURES)
}
