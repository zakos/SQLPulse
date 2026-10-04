package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.backup.JsonValue
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.explain.ExplainPlanSection
import org.junit.Rule
import org.junit.Test

/**
 * The plan screen for PostgreSQL, SQL Server and SQLite, drawn from the same captured plans the
 * reader tests use (`src/test/resources/explain/`): the real answer of each engine, as one result
 * table, handed to [ExplainPlanSection] exactly as the query screen does.
 */
class ExplainEngineShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/explain/$name")) { "missing $name" }.readText().trim()

    private fun oneCell(label: String, text: String) = ResultTable(
        columns = listOf(ColumnMeta(label, CellType.TEXT, "text", null)),
        rows = listOf(listOf(CellValue.Text(text))),
    )

    @Test
    fun postgres() = paparazzi.screen {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            ExplainPlanSection(oneCell("QUERY PLAN", resource("pg_join_sort.json")))
        }
    }

    @Test
    fun sqlServer() = paparazzi.screen {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            ExplainPlanSection(oneCell("Microsoft SQL Server 2005 XML Showplan", resource("mssql_join_sort.xml")))
        }
    }

    @Test
    fun sqlServerMissingIndex() = paparazzi.screen {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            ExplainPlanSection(oneCell("Microsoft SQL Server 2005 XML Showplan", resource("mssql_scan_missing_index.xml")))
        }
    }

    @Test
    fun sqlite() = paparazzi.screen {
        val rows = (JsonValue.parse(resource("sqlite_join_sort.json")) as JsonValue.Obj).fields.getValue("rows") as JsonValue.Arr
        val table = ResultTable(
            columns = listOf("id", "parent", "notused", "detail").map { ColumnMeta(it, CellType.TEXT, "", null) },
            rows = rows.items.map { row ->
                (row as JsonValue.Arr).items.map { cell ->
                    when (cell) {
                        is JsonValue.Num -> CellValue.Number(cell.text)
                        is JsonValue.Str -> CellValue.Text(cell.value)
                        else -> CellValue.Null
                    }
                }
            },
        )
        Column(Modifier.fillMaxSize().padding(16.dp)) { ExplainPlanSection(table) }
    }
}
