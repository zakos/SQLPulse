package hu.laurel.sqlpulse.data.chart

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultChartOutlierTest {
    private fun chart(vararg values: String?): ChartData {
        val table = ResultTable(
            columns = listOf(ColumnMeta("n", CellType.TEXT, "VARCHAR", null), ColumnMeta("v", CellType.NUMBER, "INT", null)),
            rows = values.mapIndexed { i, v ->
                listOf(CellValue.Text("r$i"), if (v == null) CellValue.Null else CellValue.Number(v))
            },
        )
        val outcome = ResultCharts.build(table, ChartSpec(0, listOf(1), ChartKind.BARS))
        return (outcome as ChartOutcome.Drawable).data
    }

    @Test
    fun outliersAreIndexedByDrawnPoint() {
        val data = chart("10", null, "11", "12", "11", "10", "900")
        // The NULL leaves no point, so the outlier is the sixth drawn point, not the seventh row.
        assertEquals(setOf(5), data.series.single().outliers)
        assertEquals(1, data.outlierCount)
        assertNotNull(data.series.single().band)
    }

    @Test
    fun tooFewPointsHaveNoBandAndNoOutliers() {
        val data = chart("1", "2", "500")
        assertNull(data.series.single().band)
        assertTrue(data.series.single().outliers.isEmpty())
    }

    @Test
    fun flatSeriesHasNoOutliers() {
        assertEquals(0, chart("5", "5", "5", "5", "5").outlierCount)
    }
}
