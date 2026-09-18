package hu.laurel.sqlpulse.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Whether this window is wide enough to put two things side by side. */
@Composable
fun isWideWindow(): Boolean = ScreenWidth.isWide(LocalConfiguration.current.screenWidthDp)
