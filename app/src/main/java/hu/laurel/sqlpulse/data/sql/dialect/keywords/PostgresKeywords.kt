package hu.laurel.sqlpulse.data.sql.dialect.keywords

/**
 * PostgreSQL. No `describe`, and `replace` is a function there; `returning`, `ilike`, `lateral`
 * and friends are what a PostgreSQL query has that MySQL's list lacks.
 */
object PostgresKeywords {
    val ALL: Set<String> = setOf(
        "select", "from", "where", "and", "or", "not", "null", "is", "in", "like", "ilike",
        "similar", "to", "between", "join", "inner", "left", "right", "outer", "full", "cross",
        "natural", "lateral", "on", "using", "group", "by", "having", "order", "asc", "desc",
        "nulls", "first", "last", "limit", "offset", "fetch", "next", "rows", "only", "insert",
        "into", "values", "update", "set", "delete", "returning", "conflict", "do", "nothing",
        "create", "alter", "drop", "table", "index", "view", "schema", "as", "distinct", "union",
        "intersect", "except", "all", "any", "some", "case", "when", "then", "else", "end",
        "with", "recursive", "show", "explain", "analyze", "count", "sum", "avg", "min", "max",
        "true", "false", "primary", "key", "foreign", "references", "default", "exists",
        "cast", "over", "partition", "window", "filter", "array",
    )
}
