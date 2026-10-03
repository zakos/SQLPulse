package hu.laurel.sqlpulse.data.sql.dialect.keywords

/** T-SQL (SQL Server, Azure SQL): `top`, `exec`, `output`, `cross apply`; no `limit`, no `show`. */
object SqlServerKeywords {
    val ALL: Set<String> = setOf(
        "select", "top", "from", "where", "and", "or", "not", "null", "is", "in", "like",
        "between", "join", "inner", "left", "right", "outer", "full", "cross", "apply", "on",
        "group", "by", "having", "order", "asc", "desc", "offset", "fetch", "next", "rows",
        "only", "insert", "into", "values", "update", "set", "delete", "output", "merge",
        "create", "alter", "drop", "table", "index", "view", "database", "schema", "as",
        "distinct", "union", "intersect", "except", "all", "case", "when", "then", "else", "end",
        "with", "exec", "execute", "use", "go", "declare", "begin", "if", "while", "count",
        "sum", "avg", "min", "max", "true", "false", "primary", "key", "foreign", "references",
        "default", "exists", "cast", "convert", "over", "partition", "pivot", "unpivot",
    )
}
