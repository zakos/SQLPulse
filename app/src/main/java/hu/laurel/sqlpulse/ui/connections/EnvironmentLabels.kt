package hu.laurel.sqlpulse.ui.connections

import androidx.annotation.StringRes
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment

/** The short form, for the picker and the badge on a card. */
@StringRes
fun ConnectionEnvironment.shortLabel(): Int = when (this) {
    ConnectionEnvironment.DEVELOPMENT -> R.string.environment_development_short
    ConnectionEnvironment.TEST -> R.string.environment_test_short
    ConnectionEnvironment.PRODUCTION -> R.string.environment_production_short
    ConnectionEnvironment.UNSET -> R.string.environment_unset_short
}

/** The full form, for the group headings in the connection list. */
@StringRes
fun ConnectionEnvironment.label(): Int = when (this) {
    ConnectionEnvironment.DEVELOPMENT -> R.string.environment_development
    ConnectionEnvironment.TEST -> R.string.environment_test
    ConnectionEnvironment.PRODUCTION -> R.string.environment_production
    ConnectionEnvironment.UNSET -> R.string.environment_unset
}
