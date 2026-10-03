package hu.laurel.sqlpulse.data.grid

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutliersTest {
    private fun samples(vararg v: Double) = v.mapIndexed { i, x -> Sample(i, x) }

    private fun report(vararg v: Double) =
        Outliers.analyze(samples(*v)) as OutlierOutcome.Report

    @Test
    fun quartilesUseLinearInterpolation() {
        // R: quantile(1:8, type = 7) -> 25% 2.75, 50% 4.5, 75% 6.25
        val r = report(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0)
        assertEquals(2.75, r.q1, 1e-9)
        assertEquals(4.5, r.median, 1e-9)
        assertEquals(6.25, r.q3, 1e-9)
        assertEquals(3.5, r.iqr, 1e-9)
    }

    @Test
    fun oddCountQuartilesLandOnValues() {
        val r = report(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(2.0, r.q1, 1e-9)
        assertEquals(3.0, r.median, 1e-9)
        assertEquals(4.0, r.q3, 1e-9)
    }

    @Test
    fun orderOfInputDoesNotMatter() {
        val a = report(5.0, 1.0, 4.0, 2.0, 3.0)
        val b = report(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(a.q1, b.q1, 0.0)
        assertEquals(a.q3, b.q3, 0.0)
    }

    @Test
    fun sampleStandardDeviation() {
        // 2,4,4,4,5,5,7,9: population sd 2, sample sd sqrt(32/7)
        val r = report(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        assertEquals(5.0, r.mean, 1e-9)
        assertEquals(Math.sqrt(32.0 / 7.0), r.stdDev, 1e-9)
    }

    @Test
    fun fencesAreOneAndAHalfIqrOut() {
        val r = report(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(2.0 - 1.5 * 2.0, r.lowFence, 1e-9)
        assertEquals(4.0 + 1.5 * 2.0, r.highFence, 1e-9)
    }

    @Test
    fun highValueIsFlaggedByBothRules() {
        val r = report(10.0, 12.0, 11.0, 13.0, 12.0, 11.0, 10.0, 12.0, 200.0)
        assertEquals(1, r.outliers.size)
        val o = r.outliers.single()
        assertEquals(8, o.row)
        assertEquals(200.0, o.value, 0.0)
        assertEquals(OutlierSide.HIGH, o.side)
        assertTrue(o.byIqr)
        assertTrue(o.byRobustZ)
        assertNotNull(o.robustZ)
        assertEquals(1, r.iqrOutlierCount)
    }

    @Test
    fun lowValueIsFlaggedAndRowsAreSorted() {
        val r = report(500.0, 10.0, 11.0, 12.0, 11.0, 10.0, -300.0, 12.0)
        assertEquals(listOf(0, 6), r.outliers.map { it.row })
        assertEquals(OutlierSide.HIGH, r.outliers[0].side)
        assertEquals(OutlierSide.LOW, r.outliers[1].side)
        assertEquals(setOf(0, 6), r.outlierRows)
    }

    @Test
    fun cleanDataHasNoOutliers() {
        val r = report(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
        assertTrue(r.outliers.isEmpty())
    }

    @Test
    fun valueOnTheFenceIsNotOut() {
        // Q1 = 2, Q3 = 4, so the high fence is 7 and 7 itself stays inside.
        val r = report(1.0, 2.0, 3.0, 4.0, 7.0)
        assertEquals(7.0, r.highFence, 1e-9)
        assertFalse(r.outliers.any { it.byIqr })
    }

    @Test
    fun rulesAreReportedIndependently() {
        val r = report(0.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 20.0, 30.0, 30.0, 30.0)
        assertTrue(r.outliers.isNotEmpty())
        r.outliers.forEach { assertTrue(it.byIqr || it.byRobustZ) }
    }

    @Test
    fun fewerThanFourValuesGiveNoVerdict() {
        assertEquals(OutlierOutcome.NotEnoughData(3), Outliers.analyze(samples(1.0, 2.0, 1000.0)))
        assertEquals(OutlierOutcome.NotEnoughData(1), Outliers.analyze(samples(5.0)))
        assertTrue(Outliers.analyze(samples(1.0, 2.0, 3.0, 4.0)) is OutlierOutcome.Report)
    }

    @Test
    fun noValuesIsNotApplicable() {
        assertEquals(OutlierOutcome.NotApplicable, Outliers.analyze(emptyList()))
    }

    @Test
    fun allEqualHasNoOutliersAndNoDivisionByZero() {
        val r = report(7.0, 7.0, 7.0, 7.0, 7.0)
        assertEquals(0.0, r.iqr, 0.0)
        assertEquals(0.0, r.mad, 0.0)
        assertEquals(0.0, r.stdDev, 0.0)
        assertTrue(r.outliers.isEmpty())
    }

    @Test
    fun zeroMadFlagsTheOddOneOut() {
        val r = report(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0)
        assertEquals(0.0, r.mad, 0.0)
        val o = r.outliers.single()
        assertEquals(6, o.row)
        assertTrue(o.byRobustZ)
        assertTrue(o.byIqr)
        assertNull(o.robustZ)
    }

    @Test
    fun negativeValues() {
        val r = report(-5.0, -4.0, -6.0, -5.0, -4.0, -5.0, 40.0)
        assertEquals(listOf(6), r.outliers.map { it.row })
    }

    @Test
    fun rowIndexesSurviveGapsLeftByNulls() {
        val table = table(
            CellValue.Number("10"), CellValue.Null, CellValue.Number("11"), CellValue.Number("12"),
            CellValue.Null, CellValue.Number("11"), CellValue.Number("10"), CellValue.Number("900"),
        )
        val r = Outliers.analyzeColumn(table, 0) as OutlierOutcome.Report
        assertEquals(6, r.count)
        assertEquals(listOf(7), r.outliers.map { it.row })
    }

    @Test
    fun nullsDoNotCountTowardsTheMinimum() {
        val table = table(CellValue.Number("1"), CellValue.Null, CellValue.Null, CellValue.Number("2"), CellValue.Number("3"))
        assertEquals(OutlierOutcome.NotEnoughData(3), Outliers.analyzeColumn(table, 0))
    }

    @Test
    fun nonNumericColumnIsNotApplicable() {
        val text = table(CellValue.Text("a"), CellValue.Text("b"), CellValue.Text("c"), CellValue.Text("d"))
        assertEquals(OutlierOutcome.NotApplicable, Outliers.analyzeColumn(text, 0))
        val mixed = table(CellValue.Number("1"), CellValue.Number("2"), CellValue.Number("3"), CellValue.Text("x"))
        assertEquals(OutlierOutcome.NotApplicable, Outliers.analyzeColumn(mixed, 0))
        val dates = table(CellValue.Date("2026-01-01"), CellValue.Date("2026-01-02"))
        assertEquals(OutlierOutcome.NotApplicable, Outliers.analyzeColumn(dates, 0))
        val bools = table(CellValue.Bool(true), CellValue.Bool(false), CellValue.Bool(true), CellValue.Bool(true))
        assertEquals(OutlierOutcome.NotApplicable, Outliers.analyzeColumn(bools, 0))
    }

    @Test
    fun allNullAndBadColumnIndex() {
        val nulls = table(CellValue.Null, CellValue.Null)
        assertEquals(OutlierOutcome.NotApplicable, Outliers.analyzeColumn(nulls, 0))
        assertEquals(OutlierOutcome.NotApplicable, Outliers.analyzeColumn(nulls, 5))
    }

    @Test
    fun decimalStringsAreParsed() {
        val table = table(
            CellValue.Number("1.50"), CellValue.Number("1.55"), CellValue.Number("1.45"),
            CellValue.Number("1.52"), CellValue.Number("99999999999999999999.5"),
        )
        val r = Outliers.analyzeColumn(table, 0) as OutlierOutcome.Report
        assertEquals(listOf(4), r.outliers.map { it.row })
    }

    @Test
    fun columnStatsCarryTheDistribution() {
        val table = table(
            CellValue.Number("10"), CellValue.Number("11"), CellValue.Number("12"),
            CellValue.Number("11"), CellValue.Number("10"), CellValue.Number("500"),
        )
        val stats = ColumnStatsComputer.compute(table, 0)!!
        val d = stats.distribution as OutlierOutcome.Report
        assertEquals(listOf(5), d.outliers.map { it.row })
    }

    private fun table(vararg cells: CellValue) = ResultTable(
        columns = listOf(ColumnMeta("v", CellType.NUMBER, "INT", "t")),
        rows = cells.map { listOf(it) },
    )
}
