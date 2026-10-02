package hu.laurel.sqlpulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.DurationUnits
import java.util.Locale

/**
 * The language the interface is drawn in. Read from the configuration rather than
 * `Locale.getDefault()`, which follows the device region and can disagree with the strings on
 * screen (English numbers beside Hungarian words).
 */
@Composable
fun appLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** `h`/`m`/`s` in English, `ó`/`p`/`mp` in Hungarian: the words of a duration such as `2 ó 20 p`. */
@Composable
fun durationUnits(): DurationUnits {
    val hour = stringResource(R.string.unit_hour_short)
    val minute = stringResource(R.string.unit_minute_short)
    val second = stringResource(R.string.unit_second_short)
    return remember(hour, minute, second) { DurationUnits(hour, minute, second) }
}
