package hu.laurel.sqlpulse.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 4pt base grid (§8). Use these instead of ad-hoc dp values. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Minimum touch target. */
    val touchTarget = 48.dp
}

object Shapes {
    val card = RoundedCornerShape(16.dp)
    val button = RoundedCornerShape(12.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val chip = RoundedCornerShape(percent = 50)
}

object Motion {
    /** Short, functional animations (§8). */
    const val DURATION_SHORT_MS = 150
    const val DURATION_MEDIUM_MS = 200
}
