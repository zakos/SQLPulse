package hu.laurel.sqlpulse.data.sql.dialect.keywords

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine

/**
 * The words the editor colours as keywords, per engine: plain data, so adding a word is a one-line
 * change that cannot touch a dialect's behaviour. Kept apart from the dialects because it is
 * cosmetic — a missing word is a missed colour, never a wrong statement.
 */
object SqlKeywords {
    fun forEngine(engine: DatabaseEngine): Set<String> = when (engine) {
        DatabaseEngine.MYSQL -> MySqlKeywords.ALL
        DatabaseEngine.POSTGRESQL -> PostgresKeywords.ALL
        DatabaseEngine.SQLSERVER -> SqlServerKeywords.ALL
        DatabaseEngine.SQLITE -> SqliteKeywords.ALL
    }
}
