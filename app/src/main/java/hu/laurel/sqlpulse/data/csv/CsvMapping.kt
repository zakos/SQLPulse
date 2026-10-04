package hu.laurel.sqlpulse.data.csv

import hu.laurel.sqlpulse.data.schema.SchemaColumn

enum class MappingIssueKind(val blocking: Boolean) {
    NOTHING_MAPPED(true),
    DUPLICATE_TARGET(true),
    REQUIRED_UNMAPPED(true),
    TYPE_MISMATCH(false),
    NULL_IN_REQUIRED(false),
}

/**
 * One problem with a mapping.
 *
 * [count] and [example] describe the offending values of a type or NULL warning; [fileColumn] may
 * list several comma-separated names for a duplicate target.
 */
data class MappingIssue(
    val kind: MappingIssueKind,
    val fileColumn: String? = null,
    val tableColumn: String? = null,
    val count: Int = 0,
    val example: String? = null,
    val typeName: String? = null,
)

/** The coarse family of a column's type, enough to tell "abc" from a number. */
enum class TypeKind {
    INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, TIME, OTHER;

    /** True when [value] could be stored in a column of this kind; text-like kinds take anything. */
    fun accepts(value: String): Boolean {
        val v = value.trim()
        return when (this) {
            INTEGER -> INT.matches(v)
            DECIMAL -> NUM.matches(v)
            BOOLEAN -> BOOL.matches(v)
            // A date column fed a timestamp is truncated by the server, not refused, and the other
            // way round the time is simply midnight.
            DATE, DATETIME -> DATE_RE.matches(v) || DATETIME_RE.matches(v)
            TIME -> TIME_RE.matches(v)
            OTHER -> true
        }
    }

    companion object {
        private val INT = Regex("[+-]?\\d+")
        private val NUM = Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")
        private val BOOL = Regex("0|1|true|false|t|f|yes|no|y|n|on|off", RegexOption.IGNORE_CASE)
        private val DATE_RE = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")
        private val DATETIME_RE =
            Regex("\\d{4}-\\d{1,2}-\\d{1,2}[ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?\\s*(Z|[+-]\\d{2}(:?\\d{2})?)?")
        private val TIME_RE = Regex("-?\\d{1,3}:\\d{2}(:\\d{2}(\\.\\d+)?)?")

        /** From a type name as any of the engines spells it: `int(11)`, `bigint unsigned`, `numeric(10,2)`. */
        fun of(typeName: String): TypeKind {
            val word = typeName.trim().lowercase().takeWhile { it.isLetterOrDigit() || it == '_' }
            return when (word) {
                "int", "integer", "tinyint", "smallint", "mediumint", "bigint", "serial", "bigserial",
                "smallserial", "int2", "int4", "int8", "year" -> INTEGER

                "decimal", "numeric", "dec", "float", "double", "real", "money", "smallmoney",
                "float4", "float8" -> DECIMAL

                "bool", "boolean", "bit" -> BOOLEAN
                "date" -> DATE
                "datetime", "datetime2", "smalldatetime", "timestamp", "timestamptz", "datetimeoffset" -> DATETIME
                "time", "timetz" -> TIME
                else -> OTHER
            }
        }
    }
}

/**
 * Manual column mapping for a CSV import: which file column goes into which table column, and
 * what is wrong with the choice.
 *
 * The by-name match ([CsvImport.match]) is only the starting point; the person can send any file
 * column to any table column or leave it out. Nothing here talks to the server.
 */
object CsvMapping {

    /** The match [mapping] amounts to: what the card lists and what decides whether it may run. */
    fun matchOf(header: List<String>, mapping: List<String?>, columns: List<SchemaColumn>): ColumnMatch {
        val matched = LinkedHashMap<String, String>()
        val unmatched = mutableListOf<String>()
        header.forEachIndexed { index, fileColumn ->
            val target = mapping.getOrNull(index)
            if (target == null) unmatched += fileColumn else matched[fileColumn] = target
        }
        val filled = mapping.filterNotNull().toSet()
        val missing = columns.map { it.name }.filterNot { it in filled }
        return ColumnMatch(
            matched = matched,
            unmatched = unmatched,
            missing = missing,
            blocking = columns.filter { it.name in missing && CsvImport.demandsValue(it) }.map { it.name },
        )
    }

    /** The plan with file column [fileIndex] sent to [tableColumn] (null: left out). */
    fun remap(plan: ImportPlan, fileIndex: Int, tableColumn: String?): ImportPlan {
        if (fileIndex !in plan.mapping.indices) return plan
        val mapping = plan.mapping.toMutableList().also { it[fileIndex] = tableColumn }
        return plan.copy(match = matchOf(plan.table.header, mapping, plan.columns), mapping = mapping)
    }

    /**
     * Everything wrong or doubtful about [plan]'s mapping, blocking problems first.
     *
     * Blocking: nothing mapped, two file columns into one table column, a required column nobody
     * fills. Warnings: values that will not parse as the target's type and NULLs headed for a NOT
     * NULL column. The import is one transaction, so those would roll it back on the server;
     * saying so here saves the round trip.
     */
    fun check(plan: ImportPlan): List<MappingIssue> {
        val header = plan.table.header
        val issues = mutableListOf<MappingIssue>()
        val mapped = plan.mapping.indices.filter { plan.mapping[it] != null }
        if (mapped.isEmpty()) issues += MappingIssue(MappingIssueKind.NOTHING_MAPPED)

        mapped.groupBy { plan.mapping[it] }.filterValues { it.size > 1 }.forEach { (target, indexes) ->
            issues += MappingIssue(
                MappingIssueKind.DUPLICATE_TARGET,
                fileColumn = indexes.joinToString(", ") { header.getOrElse(it) { "?" } },
                tableColumn = target,
            )
        }
        if (mapped.isNotEmpty()) {
            plan.match.blocking.forEach { issues += MappingIssue(MappingIssueKind.REQUIRED_UNMAPPED, tableColumn = it) }
        }

        val byName = plan.columns.associateBy { it.name }
        mapped.forEach { index ->
            val column = byName[plan.mapping[index]] ?: return@forEach
            val kind = TypeKind.of(column.typeName)
            var mismatches = 0
            var example: String? = null
            var nulls = 0
            plan.table.rows.forEach { row ->
                val value = row.getOrNull(index)
                if (value == null) {
                    nulls++
                } else if (!kind.accepts(value)) {
                    if (mismatches++ == 0) example = value
                }
            }
            if (mismatches > 0) {
                issues += MappingIssue(
                    MappingIssueKind.TYPE_MISMATCH, header.getOrNull(index), column.name, mismatches, example,
                    typeName = column.typeName,
                )
            }
            if (nulls > 0 && CsvImport.demandsValue(column)) {
                issues += MappingIssue(MappingIssueKind.NULL_IN_REQUIRED, header.getOrNull(index), column.name, nulls)
            }
        }
        return issues.sortedBy { !it.kind.blocking }
    }

    /** The first [limit] rows as they would be written: only mapped columns, in file order. */
    fun preview(plan: ImportPlan, limit: Int = 3): List<List<String?>> {
        val indexes = plan.mapping.indices.filter { plan.mapping[it] != null }
        return plan.table.rows
            .asSequence()
            // The importer skips a row whose mapped fields are all NULL, so the preview does too.
            .filter { row -> indexes.any { row.getOrNull(it) != null } }
            .take(limit)
            .map { row -> indexes.map { row.getOrNull(it) } }
            .toList()
    }

    /** The target column names of [preview]'s columns, in the same order. */
    fun previewHeader(plan: ImportPlan): List<String> = plan.mapping.filterNotNull()
}
