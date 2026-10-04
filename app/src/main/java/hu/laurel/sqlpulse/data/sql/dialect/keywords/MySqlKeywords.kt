package hu.laurel.sqlpulse.data.sql.dialect.keywords

/** MySQL / MariaDB: exactly the words the highlighter coloured before engines existed. */
object MySqlKeywords {
    val ALL: Set<String> = setOf(
        "select", "from", "where", "and", "or", "not", "null", "is", "in", "like", "between",
        "join", "inner", "left", "right", "outer", "full", "cross", "on", "using",
        "group", "by", "having", "order", "asc", "desc", "limit", "offset",
        "insert", "into", "values", "update", "set", "delete", "replace",
        "create", "alter", "drop", "table", "index", "view", "database", "schema",
        "as", "distinct", "union", "all", "case", "when", "then", "else", "end",
        "with", "show", "describe", "explain", "count", "sum", "avg", "min", "max",
        "true", "false", "primary", "key", "foreign", "references", "default", "exists",
    )
}
