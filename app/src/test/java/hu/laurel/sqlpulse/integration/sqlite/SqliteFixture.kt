package hu.laurel.sqlpulse.integration.sqlite

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.PreparedSql
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * A SQLite file made on the spot. Unlike the server engines' tests these need no environment
 * variable and never skip: the driver is a jar, the "server" is a temp file.
 *
 * The schema is small and deliberately awkward: a rowid alias, an AUTOINCREMENT key, a composite
 * key that references its parent without naming a column, a table with no key at all, a WITHOUT
 * ROWID table, a view and a trigger.
 */
object SqliteFixture {

    val SCHEMA = listOf(
        """
        CREATE TABLE customer (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            email TEXT UNIQUE,
            born DATE,
            note TEXT DEFAULT 'n/a',
            score REAL,
            photo BLOB
        )
        """.trimIndent(),
        """
        CREATE TABLE orders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            customer_id INTEGER NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
            placed TEXT,
            total NUMERIC
        )
        """.trimIndent(),
        // No column named after REFERENCES: the parent's primary key is meant.
        """
        CREATE TABLE line (
            order_id INT,
            n INT,
            sku TEXT,
            PRIMARY KEY (order_id, n),
            FOREIGN KEY (order_id) REFERENCES Orders
        )
        """.trimIndent(),
        "CREATE TABLE tag (label TEXT, weight INT)",
        "CREATE TABLE kv (k TEXT PRIMARY KEY, v) WITHOUT ROWID",
        "CREATE INDEX orders_placed ON orders (placed)",
        "CREATE VIEW big_orders AS SELECT id, total FROM orders WHERE total > 100",
        "CREATE TRIGGER orders_after_insert AFTER INSERT ON orders BEGIN SELECT 1; END",
        "INSERT INTO customer (id, name, email, born, score) VALUES " +
            "(1, 'Ada', 'ada@example.com', '1815-12-10', 9.5), " +
            "(2, 'Béla', NULL, NULL, NULL), " +
            "(3, '50% off', 'x@example.com', '2001-01-01', 1)",
        "INSERT INTO orders (customer_id, placed, total) VALUES (1, '2026-01-01', 50), (1, '2026-02-01', 150), (2, '2026-03-01', 75)",
        "INSERT INTO line (order_id, n, sku) VALUES (1, 1, 'A'), (1, 2, 'B')",
        "INSERT INTO tag (label, weight) VALUES ('red', 1), ('red', 2)",
        "INSERT INTO kv (k, v) VALUES ('a', 1)",
    )

    /** Builds the fixture database at [file] with the plain JDBC driver, no app code involved. */
    fun create(file: File, statements: List<String> = SCHEMA): File {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement -> statements.forEach(statement::execute) }
        }
        return file
    }

    fun config(file: File, readOnly: Boolean = false) = JdbcConfig(
        host = "",
        port = 0,
        database = "",
        user = "",
        password = null,
        readOnly = readOnly,
        engine = DatabaseEngine.SQLITE,
        localFile = file.absolutePath,
    )

    fun session(file: File, readOnly: Boolean = false) = SqlSession(config(file, readOnly))

    /** Runs a prepared statement the way the row editor does: every value bound as text. */
    fun Connection.run(prepared: PreparedSql): Int = prepareStatement(prepared.sql).use { statement ->
        prepared.parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeUpdate()
    }

    fun Connection.scalar(sql: String): String? = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
    }
}
