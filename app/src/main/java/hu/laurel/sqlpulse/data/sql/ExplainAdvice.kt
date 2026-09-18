package hu.laurel.sqlpulse.data.sql

/** What is worth pointing out about a query plan. The wording lives in the UI, translated. */
enum class ExplainNote {
    /** `type = ALL`: every row of the table is read. */
    FULL_TABLE_SCAN,

    /** `type = index`: the whole index is walked, which is cheaper but still everything. */
    FULL_INDEX_SCAN,

    /** No index was used at all, whatever the access type says. */
    NO_INDEX,

    /** `Using filesort`: the rows are sorted after being read. */
    FILESORT,

    /** `Using temporary`: an intermediate table is built. */
    TEMPORARY_TABLE,

    /** The estimate is large enough that it will be felt on a phone. */
    MANY_ROWS,
}

/**
 * Reads an `EXPLAIN` result and says what stands out.
 *
 * Deliberately a short list of the things that make a query slow in practice, not an optimiser:
 * a full scan, no index, a sort or a temporary table, and an estimate large enough to matter. It
 * says what MySQL plans to do, never what to change — that decision needs the schema, the data
 * and the intent, none of which the app has.
 */
object ExplainAdvice {

    /** Above this many estimated rows a query is worth a second look before running it. */
    private const val MANY_ROWS = 100_000L

    fun of(table: ResultTable): List<ExplainNote> {
        val columns = table.columns.map { it.label.lowercase() }
        // Only for a real EXPLAIN result: any other table is not ours to interpret.
        if ("type" !in columns || "rows" !in columns) return emptyList()

        val notes = linkedSetOf<ExplainNote>()
        table.rows.forEach { row ->
            fun cell(name: String): String? = columns.indexOf(name)
                .takeIf { it >= 0 }
                ?.let { index -> row.getOrNull(index) }
                ?.let { if (it is CellValue.Null) null else it.text() }

            when (cell("type")?.lowercase()) {
                "all" -> notes += ExplainNote.FULL_TABLE_SCAN
                "index" -> notes += ExplainNote.FULL_INDEX_SCAN
                else -> Unit
            }
            if (cell("key") == null) notes += ExplainNote.NO_INDEX

            val extra = cell("extra").orEmpty().lowercase()
            if (extra.contains("using filesort")) notes += ExplainNote.FILESORT
            if (extra.contains("using temporary")) notes += ExplainNote.TEMPORARY_TABLE

            val rows = cell("rows")?.toLongOrNull()
            if (rows != null && rows >= MANY_ROWS) notes += ExplainNote.MANY_ROWS
        }
        return notes.toList()
    }

    private fun CellValue.text(): String? = when (this) {
        is CellValue.Text -> value
        is CellValue.Number -> value
        is CellValue.Date -> value
        is CellValue.Bool -> if (value) "1" else "0"
        is CellValue.Blob -> null
        CellValue.Null -> null
    }
}
