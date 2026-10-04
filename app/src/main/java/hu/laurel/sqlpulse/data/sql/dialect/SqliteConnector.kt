package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import java.io.File
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import org.sqlite.SQLiteConfig

/**
 * Opens the app's private copy of a SQLite file (docs/tobb-motor-terv.md §3.2).
 *
 * There is no server to be unreachable and no password to be wrong, so the failures worth a clear
 * sentence are the file's: gone from this device (a restored backup, a cleared app storage) and
 * not a database at all. Everything else SQLite says is passed on verbatim.
 */
class SqliteConnector(private val config: JdbcConfig) : EngineConnector {

    override fun open(): Connection {
        val path = config.localFile ?: throw SQLException("this connection has no database file", null, CANTOPEN)
        val file = File(path)
        // Without this check sqlite would create an empty database at the path in a writable
        // connection, and the user would browse a schema with no tables and wonder where it went.
        if (!file.isFile) {
            throw SQLException(
                "The database file is not on this device. Open the connection in the editor and choose the file again.",
                null,
                CANTOPEN,
            )
        }
        val settings = SQLiteConfig().apply {
            // The open mode, not only the JDBC flag: a read-only connection cannot write however
            // a statement gets past the app's guards.
            setReadOnly(config.readOnly)
            // SQLite ignores foreign keys unless asked, per connection. Ignoring them would let a
            // row edit orphan rows that a server engine would have refused to orphan.
            enforceForeignKeys(true)
            // Three pooled connections share one file: wait for the lock instead of failing.
            setBusyTimeout(BUSY_TIMEOUT_MS)
        }
        // The class is named rather than found through DriverManager, which is not reliably
        // populated from a jar's service declaration on Android.
        val connection = driver.connect("jdbc:sqlite:${file.absolutePath}", settings.toProperties())
            ?: throw SQLException("the driver did not accept the database file")
        return connection.apply { autoCommit = true }
    }

    private companion object {
        const val BUSY_TIMEOUT_MS = 5_000
        const val CANTOPEN = 14
        val driver: Driver by lazy { org.sqlite.JDBC() }
    }
}
