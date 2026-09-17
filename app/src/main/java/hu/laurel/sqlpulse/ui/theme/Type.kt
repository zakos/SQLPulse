package hu.laurel.sqlpulse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Typography from §8. Inter and JetBrains Mono are not bundled yet, so the families fall back to
 * the platform sans-serif and monospace; sizes and weights already match the spec.
 */
private val Sans = FontFamily.SansSerif
val Mono: FontFamily = FontFamily.Monospace

val SqlPulseTypography = Typography(
    headlineSmall = TextStyle(fontFamily = Sans, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Normal),
    labelMedium = TextStyle(fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Normal),
)

/** Monospace styles: every data and code surface uses them (§8). */
object MonoStyles {
    val cell = TextStyle(fontFamily = Mono, fontSize = 13.sp)
    val cellNumber = TextStyle(fontFamily = Mono, fontSize = 13.sp, textAlign = TextAlign.End)
    val editor = TextStyle(fontFamily = Mono, fontSize = 15.sp)
    val fingerprint = TextStyle(fontFamily = Mono, fontSize = 13.sp)
}
