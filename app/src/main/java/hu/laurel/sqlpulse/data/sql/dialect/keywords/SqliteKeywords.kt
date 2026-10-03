package hu.laurel.sqlpulse.data.sql.dialect.keywords

/** SQLite: `pragma`, `glob`, `indexed`, `or replace`/`or ignore` conflict clauses; no `show`. */
object SqliteKeywords {
    val ALL: Set<String> = setOf(
        "select", "from", "where", "and", "or", "not", "null", "is", "in", "like", "glob",
        "between", "join", "inner", "left", "right", "outer", "full", "cross", "natural", "on",
        "using", "group", "by", "having", "order", "asc", "desc", "limit", "offset", "insert",
        "into", "values", "update", "set", "delete", "replace", "ignore", "returning",
        "create", "alter", "drop", "table", "index", "view", "as", "distinct", "union",
        "intersect", "except", "all", "case", "when", "then", "else", "end", "with",
        "recursive", "explain", "query", "plan", "pragma", "vacuum", "attach", "detach",
        "count", "sum", "avg", "min", "max", "true", "false", "primary", "key", "foreign",
        "references", "default", "exists", "cast", "over", "partition", "window", "indexed",
    )
}
