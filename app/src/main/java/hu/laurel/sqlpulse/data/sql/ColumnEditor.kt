package hu.laurel.sqlpulse.data.sql

/**
 * What a column's values look like, and therefore what editing one should offer.
 *
 * A free text box for a column that accepts three words is how a typo gets into a database, and a
 * date typed by hand on a phone is how it gets the wrong format. The type MySQL reports is enough
 * to do better for the cases that matter.
 */
sealed interface CellEditor {
    /** One of a fixed list, from `enum('new','paid','sent')`. */
    data class Choice(val options: List<String>) : CellEditor

    /** Any number of a fixed list, from `set(...)`, stored comma-separated. */
    data class Choices(val options: List<String>) : CellEditor

    /** `yyyy-MM-dd`. */
    data object Date : CellEditor

    /** `yyyy-MM-dd HH:mm:ss`. */
    data object DateTime : CellEditor

    data object Time : CellEditor

    /** `tinyint(1)`, which is what MySQL calls a boolean. */
    data object Bool : CellEditor

    /** A number: the keyboard opens on digits. */
    data object Number : CellEditor

    /** Everything else, including any text long enough to need the full box. */
    data object Text : CellEditor
}

object ColumnEditors {

    /**
     * Reads the column type as `information_schema` reports it: `enum('a','b')`, `int(11)
     * unsigned`, `decimal(10,2)`, `datetime`.
     */
    fun of(typeName: String): CellEditor {
        val type = typeName.trim().lowercase()
        return when {
            type.startsWith("enum(") -> CellEditor.Choice(optionsIn(typeName))
            type.startsWith("set(") -> CellEditor.Choices(optionsIn(typeName))
            // tinyint(1) is the only integer MySQL means as a boolean; tinyint(4) is a number.
            type.startsWith("tinyint(1)") && !type.contains("unsigned") -> CellEditor.Bool
            type == "bit(1)" || type == "boolean" || type == "bool" -> CellEditor.Bool
            type.startsWith("date") && !type.startsWith("datetime") -> CellEditor.Date
            type.startsWith("datetime") || type.startsWith("timestamp") -> CellEditor.DateTime
            type.startsWith("time") -> CellEditor.Time
            type.startsWith("year") -> CellEditor.Number
            NUMERIC.any { type.startsWith(it) } -> CellEditor.Number
            else -> CellEditor.Text
        }
    }

    /**
     * The values inside `enum(...)` or `set(...)`.
     *
     * They are SQL string literals, so a quote inside one is doubled — `'it''s'` is one value.
     * Splitting on commas alone would cut a value containing one in half.
     */
    private fun optionsIn(typeName: String): List<String> {
        val inside = typeName.substringAfter('(', "").substringBeforeLast(')', "")
        val options = mutableListOf<String>()
        val value = StringBuilder()
        var index = 0
        var quoted = false
        while (index < inside.length) {
            val c = inside[index]
            when {
                quoted && c == '\'' && index + 1 < inside.length && inside[index + 1] == '\'' -> {
                    value.append('\'')
                    index++
                }

                c == '\'' -> quoted = !quoted
                !quoted && c == ',' -> {
                    options += value.toString()
                    value.setLength(0)
                }

                else -> value.append(c)
            }
            index++
        }
        if (value.isNotEmpty()) options += value.toString()
        return options.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val NUMERIC = listOf(
        "int", "smallint", "mediumint", "bigint", "tinyint",
        "decimal", "numeric", "float", "double", "real",
    )
}
