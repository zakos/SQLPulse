package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import hu.laurel.sqlpulse.ui.theme.SqlPulseTheme
import hu.laurel.sqlpulse.ui.theme.ThemePreference

/**
 * A phone of the design's size: 390 × 844 dp at 2×, the frame every artboard in the design
 * canvas is drawn on, so a screenshot and its artboard can be laid side by side.
 */
val DesignPhone = DeviceConfig(
    screenWidth = 780,
    screenHeight = 1688,
    xdpi = 320,
    ydpi = 320,
    density = Density.XHIGH,
    softButtons = false,
    locale = "hu",
)

fun designPaparazzi() = Paparazzi(deviceConfig = DesignPhone, showSystemUi = false)

fun Paparazzi.screen(dark: Boolean = true, content: @Composable () -> Unit) = snapshot {
    SqlPulseTheme(if (dark) ThemePreference.Dark else ThemePreference.Light) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
