package hu.laurel.sqlpulse.data.sql.dialect

/**
 * Which database product a saved connection talks to. Stored by [name] in `connection.engine`, so
 * the names are part of the local schema and of the backup format: never rename one.
 *
 * MariaDB is not an engine of its own here: it speaks MySQL's protocol and dialect, and the
 * MySQL code already tells the two apart where they differ (CHECK constraints, legacy driver).
 */
enum class DatabaseEngine(
    /** The port a new connection starts with; null for an engine with no server. */
    val defaultPort: Int?,
) {
    MYSQL(3306),
    POSTGRESQL(5432),

    /** Microsoft SQL Server and Azure SQL Database (the same TDS protocol and T-SQL). */
    SQLSERVER(1433),

    /** A SQLite file on the phone: no host, no port, no tunnel, no credentials. */
    SQLITE(null),
    ;

    /** Whether there is a server to reach — and so a host, a port, TLS and an SSH tunnel. */
    val hasServer: Boolean get() = defaultPort != null

    companion object {
        /** The engine stored under [name]; anything unknown — or a row from before engines — is MySQL. */
        fun fromName(name: String?): DatabaseEngine = entries.firstOrNull { it.name == name } ?: MYSQL

        /** The order the connection editor offers them in. */
        val ORDER: List<DatabaseEngine> = listOf(MYSQL, POSTGRESQL, SQLSERVER, SQLITE)
    }
}
