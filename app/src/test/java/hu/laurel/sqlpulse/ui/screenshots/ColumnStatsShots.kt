package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.grid.ColumnStats
import hu.laurel.sqlpulse.ui.grid.ColumnStatsSheet
import hu.laurel.sqlpulse.ui.theme.SqlPulseTheme
import hu.laurel.sqlpulse.ui.theme.ThemePreference
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/**
 * Column statistics sheet: shows count, non-null, distinct, sum, average, min, max.
 * Each stat is shown in a copyable monospace cell.
 */
class ColumnStatsShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun numericColumn() = paparazzi.screen {
        Box(Modifier.fillMaxSize().padding(bottom = 200.dp)) {
            ColumnStatsSheet(
                columnLabel = "price",
                stats = ColumnStats(
                    rowCount = 150,
                    nullCount = 3,
                    distinctCount = 142,
                    sum = BigDecimal("4521.75"),
                    average = "30.279162",
                    min = "0.99",
                    max = "999.99",
                ),
                onCopy = {},
                onDismiss = {},
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun textColumn() = paparazzi.screen {
        Box(Modifier.fillMaxSize().padding(bottom = 200.dp)) {
            ColumnStatsSheet(
                columnLabel = "customer_name",
                stats = ColumnStats(
                    rowCount = 50,
                    nullCount = 1,
                    distinctCount = 48,
                    sum = null,
                    average = null,
                    min = "Alice Johnson",
                    max = "Zara Wilson",
                ),
                onCopy = {},
                onDismiss = {},
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun allNull() = paparazzi.screen {
        Box(Modifier.fillMaxSize().padding(bottom = 200.dp)) {
            ColumnStatsSheet(
                columnLabel = "optional_field",
                stats = ColumnStats(
                    rowCount = 25,
                    nullCount = 25,
                    distinctCount = 0,
                    sum = null,
                    average = null,
                    min = null,
                    max = null,
                ),
                onCopy = {},
                onDismiss = {},
            )
        }
    }
}
