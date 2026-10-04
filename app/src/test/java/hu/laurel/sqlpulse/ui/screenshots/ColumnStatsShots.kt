package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.grid.ColumnStats
import hu.laurel.sqlpulse.data.grid.ColumnStatsComputer
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.grid.ColumnStatsSheetContent
import hu.laurel.sqlpulse.ui.theme.Shapes
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

    @Test
    fun numericColumn() = paparazzi.screen {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                shape = Shapes.sheet,
                color = MaterialTheme.colorScheme.background,
            ) {
                ColumnStatsSheetContent(
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
                )
            }
        }
    }

    @Test
    fun distributionWithOutliers() = paparazzi.screen {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                shape = Shapes.sheet,
                color = MaterialTheme.colorScheme.background,
            ) {
                val values = listOf("412", "388", "455", "301", "520", "9800", "480", "395", "430", "575", "640", "-2100", "505")
                val table = ResultTable(
                    columns = listOf(ColumnMeta("total", CellType.NUMBER, "DECIMAL", "orders")),
                    rows = values.map { listOf(CellValue.Number(it)) },
                )
                ColumnStatsSheetContent(
                    columnLabel = "total",
                    stats = ColumnStatsComputer.compute(table, 0)!!,
                    onCopy = {},
                    onHighlightOutliersChange = {},
                    highlightOutliers = true,
                    outliersExpanded = true,
                )
            }
        }
    }

    @Test
    fun distributionCollapsed() = paparazzi.screen {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                shape = Shapes.sheet,
                color = MaterialTheme.colorScheme.background,
            ) {
                val values = listOf("412", "388", "455", "301", "520", "9800", "480", "395")
                val table = ResultTable(
                    columns = listOf(ColumnMeta("total", CellType.NUMBER, "DECIMAL", "orders")),
                    rows = values.map { listOf(CellValue.Number(it)) },
                )
                ColumnStatsSheetContent(
                    columnLabel = "total",
                    stats = ColumnStatsComputer.compute(table, 0)!!,
                    onCopy = {},
                    onHighlightOutliersChange = {},
                )
            }
        }
    }

    @Test
    fun textColumn() = paparazzi.screen {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                shape = Shapes.sheet,
                color = MaterialTheme.colorScheme.background,
            ) {
                ColumnStatsSheetContent(
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
                )
            }
        }
    }

    @Test
    fun allNull() = paparazzi.screen {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                shape = Shapes.sheet,
                color = MaterialTheme.colorScheme.background,
            ) {
                ColumnStatsSheetContent(
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
                )
            }
        }
    }
}
