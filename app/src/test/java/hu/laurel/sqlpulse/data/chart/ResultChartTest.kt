package hu.laurel.sqlpulse.data.chart

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultChartTest {

    private fun column(label: String, type: CellType) = ColumnMeta(label, type, label, "t")

    private val byName = ResultTable(
        columns = listOf(column("city", CellType.TEXT), column("orders", CellType.NUMBER)),
        rows = listOf(
            listOf(CellValue.Text("Szeged"), CellValue.Number("12")),
            listOf(CellValue.Text("Pécs"), CellValue.Number("7")),
            listOf(CellValue.Text("Győr"), CellValue.Number("31")),
        ),
    )

    private val byDay = ResultTable(
        columns = listOf(
            column("day", CellType.DATE),
            column("orders", CellType.NUMBER),
            column("returns", CellType.NUMBER),
        ),
        rows = listOf(
            listOf(CellValue.Date("2026-01-01"), CellValue.Number("5"), CellValue.Number("1")),
            listOf(CellValue.Date("2026-01-02"), CellValue.Number("9"), CellValue.Number("0")),
        ),
    )

    private fun drawn(outcome: ChartOutcome): ChartData =
        (outcome as ChartOutcome.Drawable).data

    @Test
    fun `names get bars and dates get a line`() {
        assertEquals(ChartKind.BARS, ResultCharts.suggest(byName)?.kind)
        assertEquals(ChartKind.LINE, ResultCharts.suggest(byDay)?.kind)
    }

    @Test
    fun `every numeric column is drawn except the one labelling the points`() {
        val spec = ResultCharts.suggest(byDay)
        assertEquals(listOf(1, 2), spec?.valueColumns)
        val data = drawn(ResultCharts.build(byDay, spec))
        assertEquals(listOf("orders", "returns"), data.series.map { it.label })
        assertEquals("day", data.labelColumn)
    }

    @Test
    fun `the points keep the result's order and carry their label`() {
        val data = drawn(ResultCharts.build(byName, null))
        assertEquals(listOf("Szeged", "Pécs", "Győr"), data.series.single().points.map { it.label })
        assertEquals(listOf(12.0, 7.0, 31.0), data.series.single().points.map { it.value })
    }

    @Test
    fun `a result with no numbers cannot be drawn`() {
        val text = ResultTable(
            columns = listOf(column("a", CellType.TEXT)),
            rows = listOf(listOf(CellValue.Text("x"))),
        )
        assertNull(ResultCharts.suggest(text))
        assertEquals(
            ChartRefusal.NO_NUMBERS,
            (ResultCharts.build(text, null) as ChartOutcome.Refused).reason,
        )
    }

    @Test
    fun `an empty result says so rather than drawing an empty axis`() {
        val empty = byName.copy(rows = emptyList())
        assertEquals(
            ChartRefusal.NO_ROWS,
            (ResultCharts.build(empty, null) as ChartOutcome.Refused).reason,
        )
    }

    @Test
    fun `NULL is a gap, not a zero`() {
        val holes = byName.copy(
            rows = byName.rows + listOf(listOf(CellValue.Text("Eger"), CellValue.Null)),
        )
        val data = drawn(ResultCharts.build(holes, null))
        assertEquals(3, data.series.single().points.size)
        assertTrue(data.series.single().points.none { it.label == "Eger" })
    }

    @Test
    fun `past the category limit the chart says how many it kept`() {
        val many = byName.copy(
            rows = (1..ResultCharts.MAX_CATEGORIES + 5).map {
                listOf(CellValue.Text("c$it"), CellValue.Number("$it"))
            },
        )
        val data = drawn(ResultCharts.build(many, null))
        assertEquals(ResultCharts.MAX_CATEGORIES, data.truncatedTo)
        assertEquals(ResultCharts.MAX_CATEGORIES, data.series.single().points.size)
    }

    @Test
    fun `everything drawn leaves the count alone`() {
        assertNull(drawn(ResultCharts.build(byName, null)).truncatedTo)
    }

    @Test
    fun `a row without a label is numbered rather than left blank`() {
        val blank = byName.copy(
            rows = listOf(listOf(CellValue.Null, CellValue.Number("4"))),
        )
        assertEquals("#1", drawn(ResultCharts.build(blank, null)).series.single().points.single().label)
    }

    @Test
    fun `the axis always contains zero, above and below it`() {
        val negative = byName.copy(
            rows = listOf(
                listOf(CellValue.Text("a"), CellValue.Number("-3")),
                listOf(CellValue.Text("b"), CellValue.Number("4")),
            ),
        )
        val data = drawn(ResultCharts.build(negative, null))
        assertEquals(-3.0, data.minimum, 0.001)
        assertEquals(4.0, data.maximum, 0.001)

        val positive = drawn(ResultCharts.build(byName, null))
        assertEquals(0.0, positive.minimum, 0.001)
    }

    @Test
    fun `the caller can pick the columns itself`() {
        val spec = ChartSpec(labelColumn = 1, valueColumns = listOf(2), kind = ChartKind.BARS)
        val data = drawn(ResultCharts.build(byDay, spec))
        assertEquals("returns", data.series.single().label)
        assertEquals(listOf("5", "9"), data.series.single().points.map { it.label })
    }

    @Test
    fun `a spec naming no drawable column is refused, not drawn empty`() {
        val spec = ChartSpec(labelColumn = 0, valueColumns = listOf(0), kind = ChartKind.BARS)
        assertEquals(
            ChartRefusal.NO_NUMBERS,
            (ResultCharts.build(byName, spec) as ChartOutcome.Refused).reason,
        )
    }

    @Test
    fun `the candidates are the columns each choice can take`() {
        assertEquals(listOf(0), ResultCharts.labelCandidates(byName))
        assertEquals(listOf(1), ResultCharts.valueCandidates(byName))
        assertEquals(listOf(0), ResultCharts.labelCandidates(byDay))
        assertEquals(listOf(1, 2), ResultCharts.valueCandidates(byDay))
    }
}
