package hu.laurel.sqlpulse.ui.screenshots

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityOptionsCompat
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
    // Screens that pick files register for activity results; a screenshot has no activity.
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoResults) {
        SqlPulseTheme(if (dark) ThemePreference.Dark else ThemePreference.Light) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }
}

private object NoResults : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) = Unit
    }
}
