package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.schema.SchemaDiff
import hu.laurel.sqlpulse.data.schema.quoteIdentifier
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import java.sql.Connection
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Views, triggers, CHECK constraints and foreign key rules on two MySQL / MariaDB databases —
 * the same fixture contract the other engines' feature tests use ([SchemaObjectsSupport]).
 * MariaDB 10.11 and MySQL 8.0 both have CHECK constraints and `information_schema.TRIGGERS`.
 */
class SchemaObjectsIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession

    private val stamp = System.nanoTime()
    private val a = "sqlpulse_it_obj_a_$stamp"
    private val b = "sqlpulse_it_obj_b_$stamp"

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
        session.use { connection ->
            for ((database, variant) in listOf(a to "a", b to "b")) {
                val first = variant == "a"
                val db = quoteIdentifier(database)
                connection.execute("CREATE DATABASE $db DEFAULT CHARACTER SET utf8mb4")
                connection.execute("CREATE TABLE $db.parent (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB")
                connection.execute(
                    "CREATE TABLE $db.child (id INT NOT NULL PRIMARY KEY, parent_id INT NULL, qty INT NULL, price INT NULL, " +
                        "note VARCHAR(20) NULL, KEY ix_parent (parent_id), " +
                        "CONSTRAINT child_parent_fk FOREIGN KEY (parent_id) REFERENCES $db.parent (id) " +
                        "ON DELETE ${if (first) "CASCADE" else "SET NULL"}, " +
                        "CONSTRAINT qty_ok CHECK (qty ${if (first) ">" else ">="} 0), " +
                        "CONSTRAINT price_ok CHECK (${if (first) "price >= 0" else "(price>=0)"})) ENGINE=InnoDB",
                )
                connection.execute("CREATE VIEW $db.v_child AS SELECT id, qty FROM $db.child WHERE qty > ${if (first) 0 else 1}")
                connection.execute(
                    if (first) "CREATE VIEW $db.v_same AS SELECT id FROM $db.child WHERE price > 5"
                    else "CREATE VIEW $db.v_same AS\n  SELECT   id\n FROM $db.`child`\n  WHERE price>5",
                )
                connection.execute(
                    "CREATE TRIGGER $db.trg_child BEFORE INSERT ON $db.child FOR EACH ROW " +
                        "SET NEW.note = '${if (first) "a" else "b"}'",
                )
                connection.execute(
                    if (first) "CREATE TRIGGER $db.trg_same BEFORE UPDATE ON $db.child FOR EACH ROW BEGIN SET NEW.price = 1; END"
                    else "CREATE TRIGGER $db.trg_same BEFORE UPDATE ON $db.child FOR EACH ROW BEGIN\n  SET NEW.price=1; -- same\nEND",
                )
            }
        }
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching {
                session.use {
                    it.execute("DROP DATABASE IF EXISTS ${quoteIdentifier(a)}")
                    it.execute("DROP DATABASE IF EXISTS ${quoteIdentifier(b)}")
                }
            }
            session.close()
        }
    }

    @Test
    fun `views, triggers, checks and foreign key rules are compared`() {
        val manager = IntegrationSessions.manager(session, config.database)
        val result = SchemaDiff.compare(
            SchemaObjectsSupport.side(manager, a, DatabaseEngine.MYSQL),
            SchemaObjectsSupport.side(manager, b, DatabaseEngine.MYSQL),
        )
        SchemaObjectsSupport.assertFixtureDifferences(result)
    }

    @Test
    fun `a database compared with itself has nothing to report`() {
        val manager = IntegrationSessions.manager(session, config.database)
        val side = SchemaObjectsSupport.side(manager, a, DatabaseEngine.MYSQL)
        val result = SchemaDiff.compare(side, side)
        assertTrue(result.identical)
        assertTrue(side.triggers!!.any { it.name == "trg_child" && it.body.isNotBlank() })
        assertTrue(side.viewDefinitions.keys.containsAll(listOf("v_child", "v_same")))
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
