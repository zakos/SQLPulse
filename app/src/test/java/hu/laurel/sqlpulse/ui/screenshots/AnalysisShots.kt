package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.chart.ChartKind
import hu.laurel.sqlpulse.data.chart.ChartSpec
import hu.laurel.sqlpulse.data.snapshot.CellChange
import hu.laurel.sqlpulse.data.snapshot.MatchStrategy
import hu.laurel.sqlpulse.data.snapshot.ResultDiff
import hu.laurel.sqlpulse.data.snapshot.RowChangeKind
import hu.laurel.sqlpulse.data.snapshot.RowDiff
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.chart.ResultChartPanel
import hu.laurel.sqlpulse.ui.explain.ExplainPlanSection
import hu.laurel.sqlpulse.ui.snapshot.DiffBody
import org.junit.Rule
import org.junit.Test

class AnalysisShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Test
    fun explain() = paparazzi.screen {
        val plan = """{"query_block":{"select_id":1,"cost_info":{"query_cost":"128443.30"},
          "ordering_operation":{"using_filesort":true,"grouping_operation":{"using_temporary_table":true,"using_filesort":false,
          "nested_loop":[
            {"table":{"table_name":"i","access_type":"ALL","rows_examined_per_scan":1180000,"rows_produced_per_join":129800,"filtered":"11.00",
              "cost_info":{"read_cost":"91234.50","eval_cost":"12980.00","prefix_cost":"104214.50","data_read_per_join":"41M"},"used_columns":["id","issued_at"],
              "attached_condition":"(i.issued_at >= '2026-09-01')"}},
            {"table":{"table_name":"c","access_type":"eq_ref","possible_keys":["PRIMARY"],"key":"PRIMARY","key_length":"8","ref":["billing.i.customer_id"],
              "rows_examined_per_scan":1,"rows_produced_per_join":129800,"filtered":"100.00",
              "cost_info":{"read_cost":"32450.00","eval_cost":"12980.00","prefix_cost":"128443.30","data_read_per_join":"20M"},"used_columns":["id","name"]}}
          ]}}}}"""
        val table = ResultTable(
            columns = listOf(ColumnMeta("EXPLAIN", CellType.TEXT, "TEXT", null)),
            rows = listOf(listOf(CellValue.Text(plan))),
        )
        Column(Modifier.fillMaxSize().padding(16.dp)) { ExplainPlanSection(table) }
    }

    @Test
    fun chart() = paparazzi.screen {
        val values = listOf(412, 388, 455, 301, 520, 610, 480, 395, 430, 575, 640, 598, 505, 690)
        val table = ResultTable(
            columns = listOf(ColumnMeta("nap", CellType.TEXT, "VARCHAR", null), ColumnMeta("osszeg", CellType.NUMBER, "DECIMAL", null)),
            rows = values.mapIndexed { i, v -> listOf(CellValue.Text("09-%02d".format(i + 8)), CellValue.Number(v.toString())) },
        )
        ResultChartPanel(
            table = table,
            spec = ChartSpec(labelColumn = 0, valueColumns = listOf(1), kind = ChartKind.BARS),
            onSpecChange = {},
            modifier = Modifier.padding(16.dp),
        )
    }

    @Test
    fun chartWithOutliers() = paparazzi.screen {
        val values = listOf(412, 388, 455, 301, 520, 1480, 480, 395, 430, 575, 640, 598, 505, 90)
        val table = ResultTable(
            columns = listOf(ColumnMeta("nap", CellType.TEXT, "VARCHAR", null), ColumnMeta("osszeg", CellType.NUMBER, "DECIMAL", null)),
            rows = values.mapIndexed { i, v -> listOf(CellValue.Text("09-%02d".format(i + 8)), CellValue.Number(v.toString())) },
        )
        ResultChartPanel(
            table = table,
            spec = ChartSpec(labelColumn = 0, valueColumns = listOf(1), kind = ChartKind.BARS),
            onSpecChange = {},
            modifier = Modifier.padding(16.dp),
        )
    }

    @Test
    fun lineChartWithOutliersAndBand() = paparazzi.screen {
        val values = listOf(412, 388, 455, 301, 520, 1480, 480, 395, 430, 575, 640, 598, 505, 90)
        val table = ResultTable(
            columns = listOf(ColumnMeta("nap", CellType.DATE, "DATE", null), ColumnMeta("osszeg", CellType.NUMBER, "DECIMAL", null)),
            rows = values.mapIndexed { i, v -> listOf(CellValue.Date("09-%02d".format(i + 8)), CellValue.Number(v.toString())) },
        )
        ResultChartPanel(
            table = table,
            spec = ChartSpec(labelColumn = 0, valueColumns = listOf(1), kind = ChartKind.LINE),
            onSpecChange = {},
            modifier = Modifier.padding(16.dp),
            bandInitially = true,
        )
    }

    @Test
    fun snapshot() = paparazzi.screen {
        fun num(v: String) = CellValue.Number(v)
        fun txt(v: String) = CellValue.Text(v)
        val columns = listOf("id", "ugyfel", "osszeg", "statusz")
        val diff = ResultDiff(
            columns = columns,
            strategy = MatchStrategy.PrimaryKey(listOf("id")),
            rows = listOf(
                RowDiff(RowChangeKind.ADDED, listOf(num("20421")), null, listOf(num("20421"), txt("Lakatos Bence"), num("45000"), txt("paid"))),
                RowDiff(RowChangeKind.ADDED, listOf(num("20420")), null, listOf(num("20420"), txt("Bartos Kft."), num("380000"), txt("draft"))),
                RowDiff(
                    RowChangeKind.CHANGED, listOf(num("20416")), listOf(num("20416"), txt("Nagy Péter"), num("12990"), txt("overdue")),
                    listOf(num("20416"), txt("Nagy Péter"), num("12990"), txt("paid")),
                    listOf(CellChange(3, "statusz", txt("overdue"), txt("paid"))),
                ),
                RowDiff(
                    RowChangeKind.CHANGED, listOf(num("20411")), listOf(num("20411"), txt("Fekete Bt."), num("310000"), txt("overdue")),
                    listOf(num("20411"), txt("Fekete Bt."), num("155000"), txt("overdue")),
                    listOf(CellChange(2, "osszeg", num("310000"), num("155000"))),
                ),
                RowDiff(RowChangeKind.REMOVED, listOf(num("20414")), listOf(num("20414"), txt("Tóth Eszter"), num("8450"), txt("draft")), null),
            ),
            unchangedCount = 308,
            beforeRowCount = 312,
            afterRowCount = 313,
            partial = false,
            takenAt = 1_790_000_000_000L,
            comparedAt = 1_790_003_600_000L,
        )
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            DiffBody(diff)
        }
    }
}
