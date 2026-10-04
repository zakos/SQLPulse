package hu.laurel.sqlpulse.data.sql.plan

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ExplainJson
import hu.laurel.sqlpulse.data.sql.ExplainPlanResult
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine

/**
 * Turns what an engine answered to its EXPLAIN statement into the tree the plan screen draws.
 *
 * The statement differs per engine (`SqlDialect.explain`) and so does the answer: MySQL and
 * PostgreSQL return one JSON cell, SQL Server one XML cell, SQLite a handful of `detail` rows.
 * Each reader owns one of those shapes and nothing else; the tree, the cost bar and the advice are
 * shared, which is why every reader ends in [ExplainPlanResult].
 *
 * Like [ExplainJson], a reader never throws: whatever it cannot read is
 * [ExplainPlanResult.Unavailable], so the screen can show the plain table instead.
 */
interface PlanReader {
    val engine: DatabaseEngine

    fun read(table: ResultTable): ExplainPlanResult
}

/** MySQL's `EXPLAIN FORMAT=JSON`: one cell holding a JSON object — the reader the app always had. */
object MySqlPlanReader : PlanReader {
    override val engine = DatabaseEngine.MYSQL

    override fun read(table: ResultTable): ExplainPlanResult = ExplainJson.of(PlanReaders.singleText(table, "{"))
}

object PlanReaders {

    fun forEngine(engine: DatabaseEngine): PlanReader = when (engine) {
        DatabaseEngine.MYSQL -> MySqlPlanReader
        DatabaseEngine.POSTGRESQL -> PostgresPlanReader
        DatabaseEngine.SQLSERVER -> SqlServerPlanReader
        DatabaseEngine.SQLITE -> SqlitePlanReader
    }

    /**
     * Reads [table] with whichever reader recognises it.
     *
     * The plan screen sits on the result grid, which does not know which engine produced the
     * rows (a result outlives a switch of connection), so the shape of the answer decides. The
     * four shapes cannot be mistaken for each other: an object, an array, an XML document, and
     * four named columns.
     */
    fun detect(table: ResultTable): PlanReader? = when {
        SqlitePlanReader.recognises(table) -> SqlitePlanReader
        singleText(table, "[") != null -> PostgresPlanReader
        singleText(table, "<") != null -> SqlServerPlanReader
        singleText(table, "{") != null -> MySqlPlanReader
        else -> null
    }

    /**
     * The one cell of a one-cell result when it is text starting with [start], or null.
     *
     * The column name is not checked: drivers label it differently ("EXPLAIN", "QUERY PLAN",
     * "XML Showplan", or nothing), and a cell that opens like a document is nothing else.
     */
    internal fun singleText(table: ResultTable, start: String): String? {
        if (table.rows.size != 1 || table.columns.size != 1) return null
        val cell = table.rows.first().firstOrNull() as? CellValue.Text ?: return null
        return cell.value.takeIf { it.trimStart().startsWith(start) }
    }
}
