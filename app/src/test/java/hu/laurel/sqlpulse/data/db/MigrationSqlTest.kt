package hu.laurel.sqlpulse.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The eight migrations, run end to end against a real SQLite engine.
 *
 * Room's own MigrationTestHelper needs an instrumented device and the exported schema JSONs, and
 * this project has neither in its ordinary check run — so the migrations had never been executed
 * at all. They are only SQL, though, so [MigrationStatements] holds them as data and this test
 * applies exactly those strings, in order, to an in-memory sqlite-jdbc database that starts life
 * with the version 1 schema written out in [SchemaV1].
 *
 * What this does and does not prove:
 *  - it runs the same statement strings the app runs, so a syntax error or a missing column
 *    cannot reach a phone unnoticed;
 *  - it does not run SQLCipher, and the sqlite-jdbc engine is newer than the oldest engine the
 *    app can meet. Where that difference matters it is called out at the assertion.
 *
 * The starting schema is written out here by hand on purpose: `app/schemas` was never committed,
 * so the historical shape existed nowhere. Now it exists here, and the first migration is checked
 * against it rather than against a guess.
 */
class MigrationSqlTest {

    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Room turns foreign keys off for the duration of a migration and back on afterwards; the
        // 1→2 rebuild drops a table another table references, which needs exactly that.
        exec("PRAGMA foreign_keys = OFF")
        SchemaV1.STATEMENTS.forEach(::exec)
        exec("PRAGMA user_version = 1")
    }

    @After
    fun close() {
        db.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
        db.createStatement().use { st -> st.executeQuery(sql).use(read) }

    private fun rows(sql: String): List<Map<String, Any?>> = query(sql) { rs ->
        val meta = rs.metaData
        val out = mutableListOf<Map<String, Any?>>()
        while (rs.next()) {
            out += (1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) }
        }
        out
    }

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val default: String?,
        val primaryKey: Boolean,
    )

    private fun columns(table: String): Map<String, Column> =
        rows("PRAGMA table_info(`$table`)").associate { r ->
            val name = r["name"] as String
            name to Column(
                name = name,
                type = (r["type"] as String).uppercase(),
                notNull = (r["notnull"] as Number).toInt() != 0,
                default = r["dflt_value"] as String?,
                primaryKey = (r["pk"] as Number).toInt() != 0,
            )
        }

    private fun tables(): Set<String> =
        rows("SELECT name FROM sqlite_master WHERE type = 'table'")
            .map { it["name"] as String }
            .filterNot { it.startsWith("sqlite_") }
            .toSet()

    /** Applies the migrations that take the database from [from] to [to]. */
    private fun migrate(from: Int, to: Int) {
        MigrationStatements.IN_ORDER
            .filter { (start, _) -> start in from until to }
            .forEach { (_, statements) -> statements.forEach(::exec) }
        exec("PRAGMA user_version = $to")
    }

    private fun migrateToCurrent() = migrate(1, CURRENT_VERSION)

    private fun seedVersion1() {
        db.prepareStatement(SchemaV1.INSERT_SSH_KEY).use { st ->
            st.setLong(1, KEY_ID)
            st.setString(2, "laptop")
            st.setString(3, "ED25519")
            st.setInt(4, 256)
            st.setString(5, FINGERPRINT)
            st.setString(6, "ED25519_SEED")
            st.setBytes(7, SEALED_KEY)
            st.setString(8, "ssh-ed25519 AAAA... laptop")
            st.setLong(9, 1_700_000_000_000L)
            st.executeUpdate()
        }
        db.prepareStatement(SchemaV1.INSERT_CONNECTION).use { st ->
            st.setLong(1, CONNECTION_ID)
            st.setString(2, "prod reporting")
            st.setString(3, "TEAL")
            st.setString(4, "bastion.example.org")
            st.setInt(5, 2222)
            st.setString(6, "deploy")
            st.setLong(7, KEY_ID)
            st.setString(8, "10.0.0.7")
            st.setInt(9, 3307)
            st.setString(10, "reporting")
            st.setString(11, "readonly")
            st.setInt(12, 1)
            st.setLong(13, 1_700_000_500_000L)
            st.executeUpdate()
        }
        db.prepareStatement(SchemaV1.INSERT_DB_CREDENTIAL).use { st ->
            st.setLong(1, CONNECTION_ID)
            st.setBytes(2, SEALED_PASSWORD)
            st.executeUpdate()
        }
    }

    // ------------------------------------------------------- the whole ladder

    /**
     * Every table and column the current entities declare exists after 1 → 9, with matching
     * nullability. The expectation is read out of `Entities.kt` itself (see [EntitySource]), so a
     * field added to an entity without a migration fails here.
     */
    @Test
    fun `schema after every migration matches the entities`() {
        migrateToCurrent()

        val expected = EntitySource.tables
        assertEquals(
            "tables in the database do not match the @Entity list",
            expected.keys,
            tables(),
        )

        for ((table, properties) in expected) {
            val actual = columns(table)
            assertEquals(
                "columns of `$table`",
                properties.map { it.name }.toSet(),
                actual.keys,
            )
            for (property in properties) {
                val column = actual.getValue(property.name)
                assertEquals(
                    "affinity of `$table`.`${property.name}`",
                    property.affinity,
                    column.type,
                )
                // A single-column INTEGER PRIMARY KEY is the rowid alias: SQLite reports it as
                // nullable whatever the DDL says, and Room accepts that, so it is exempted.
                if (!column.primaryKey) {
                    assertEquals(
                        "nullability of `$table`.`${property.name}`",
                        !property.nullable,
                        column.notNull,
                    )
                }
            }
        }
    }

    /** The defaults the migrations promise existing rows, spelled out. */
    @Test
    fun `added columns carry the documented defaults`() {
        migrateToCurrent()
        val connection = columns("connection")

        assertEquals("'KEY'", connection.getValue("sshAuthMethod").default)
        assertEquals("'DISABLED'", connection.getValue("sslMode").default)
        assertEquals("'UNSET'", connection.getValue("environment").default)
        assertEquals("22", connection.getValue("sshJumpPort").default)
        assertEquals("10", connection.getValue("connectTimeoutSeconds").default)
        assertEquals("30", connection.getValue("queryTimeoutSeconds").default)

        // Nullable-and-no-default is the compatibility contract for the optional columns.
        for (name in listOf("caCertificate", "sshJumpHost", "sshJumpUser", "lastUsedAt")) {
            assertNull("`$name` must not have a default", connection.getValue(name).default)
            assertFalse("`$name` must stay nullable", connection.getValue(name).notNull)
        }
    }

    /** The user's saved connection, key and sealed password come out the other side unchanged. */
    @Test
    fun `data written at version 1 survives to version 9`() {
        seedVersion1()
        migrateToCurrent()

        val key = rows("SELECT * FROM ssh_key").single()
        assertEquals(KEY_ID, (key["id"] as Number).toLong())
        assertEquals(FINGERPRINT, key["fingerprint"])
        assertEquals("ED25519_SEED", key["materialFormat"])
        assertArrayEquals(SEALED_KEY, key["sealedPrivateKey"] as ByteArray)

        val connection = rows("SELECT * FROM `connection`").single()
        assertEquals(CONNECTION_ID, (connection["id"] as Number).toLong())
        assertEquals("prod reporting", connection["name"])
        assertEquals("TEAL", connection["color"])
        // Renamed in 2→3; the value has to be the one written as `bastionHost` at version 1.
        assertEquals("bastion.example.org", connection["sshHost"])
        assertEquals(2222, (connection["sshPort"] as Number).toInt())
        assertEquals("deploy", connection["sshUser"])
        assertEquals(KEY_ID, (connection["sshKeyId"] as Number).toLong())
        assertEquals("10.0.0.7", connection["dbHost"])
        assertEquals(3307, (connection["dbPort"] as Number).toInt())
        assertEquals("reporting", connection["database"])
        assertEquals("readonly", connection["dbUser"])
        assertEquals(1, (connection["readOnly"] as Number).toInt())
        assertEquals(1_700_000_500_000L, (connection["lastUsedAt"] as Number).toLong())

        // The columns added along the way take the values the migrations promise.
        assertEquals(1, (connection["useSshTunnel"] as Number).toInt())
        assertEquals("KEY", connection["sshAuthMethod"])
        assertEquals("DISABLED", connection["sslMode"])
        assertNull(connection["caCertificate"])
        assertEquals("UNSET", connection["environment"])
        assertEquals(10, (connection["connectTimeoutSeconds"] as Number).toInt())
        assertEquals(30, (connection["queryTimeoutSeconds"] as Number).toInt())

        val credential = rows("SELECT * FROM db_credential").single()
        assertEquals(CONNECTION_ID, (credential["connectionId"] as Number).toLong())
        assertArrayEquals(SEALED_PASSWORD, credential["sealedPassword"] as ByteArray)

        // Nothing is left pointing at a row that no longer exists after the 1→2 rebuild.
        exec("PRAGMA foreign_keys = ON")
        assertEquals(emptyList<Map<String, Any?>>(), rows("PRAGMA foreign_key_check"))
    }

    // ----------------------------------------------------------- the hazards

    /**
     * 1→2 rebuilds `connection` to relax `sshKeyId`, which means dropping the table the user's
     * connections live in and copying them into a new one. Every row has to arrive, the tunnel
     * flag has to be on for all of them, and the identity counter has to survive so a new
     * connection cannot be given an id a deleted one already used.
     */
    @Test
    fun `the 1 to 2 rebuild copies every row and relaxes sshKeyId`() {
        seedVersion1()
        db.prepareStatement(SchemaV1.INSERT_CONNECTION).use { st ->
            st.setLong(1, CONNECTION_ID + 1)
            st.setString(2, "staging")
            st.setString(3, "AMBER")
            st.setString(4, "jump.example.org")
            st.setInt(5, 22)
            st.setString(6, "deploy")
            st.setLong(7, KEY_ID)
            st.setString(8, "127.0.0.1")
            st.setInt(9, 3306)
            st.setString(10, "staging")
            st.setString(11, "app")
            st.setInt(12, 0)
            st.setNull(13, java.sql.Types.INTEGER)
            st.executeUpdate()
        }
        assertTrue(columns("connection").getValue("sshKeyId").notNull)

        migrate(1, 2)

        val after = rows("SELECT * FROM `connection` ORDER BY id")
        assertEquals(2, after.size)
        assertEquals(listOf("prod reporting", "staging"), after.map { it["name"] })
        assertEquals(listOf(CONNECTION_ID, CONNECTION_ID + 1), after.map { (it["id"] as Number).toLong() })
        assertEquals(listOf(1, 0), after.map { (it["readOnly"] as Number).toInt() })
        assertTrue("every existing connection was tunnelled", after.all { (it["useSshTunnel"] as Number).toInt() == 1 })
        assertNull("a NULL lastUsedAt must stay NULL, not become 0", after[1]["lastUsedAt"])

        // The point of the rebuild: the column now takes NULL.
        assertFalse(columns("connection").getValue("sshKeyId").notNull)
        exec(
            "INSERT INTO `connection` (name, color, useSshTunnel, bastionHost, bastionPort, sshUser," +
                " sshKeyId, dbHost, dbPort, `database`, dbUser, readOnly, lastUsedAt)" +
                " VALUES ('direct', 'GREY', 0, '', 22, '', NULL, 'db.local', 3306, 'x', 'y', 1, NULL)",
        )

        // AUTOINCREMENT survived the rebuild, so the new row sits after the copied ones.
        val fresh = rows("SELECT id FROM `connection` WHERE name = 'direct'").single()
        assertTrue(
            "a new connection must not reuse an id",
            (fresh["id"] as Number).toLong() > CONNECTION_ID + 1,
        )
        assertTrue(
            "the rebuilt table must keep AUTOINCREMENT",
            query("SELECT sql FROM sqlite_master WHERE name = 'connection'") { rs ->
                rs.next(); rs.getString(1)
            }.contains("AUTOINCREMENT"),
        )

        // The index the old table had has to be recreated, or every lookup by key turns into
        // a full scan of the user's connections.
        assertTrue(
            "index_connection_sshKeyId must be recreated on the rebuilt table",
            rows("PRAGMA index_list(`connection`)").any { it["name"] == "index_connection_sshKeyId" },
        )
    }

    /**
     * 2→3 renames the two bastion columns. The rename has to happen at 3 and not earlier — a
     * device sitting at version 2 still has the old names — and it has to keep the data.
     */
    @Test
    fun `the 2 to 3 renames keep the values and happen at 3`() {
        seedVersion1()

        migrate(1, 2)
        assertTrue("version 2 still speaks of bastions", columns("connection").containsKey("bastionHost"))
        assertFalse(columns("connection").containsKey("sshHost"))

        migrate(2, 3)
        val after = columns("connection")
        assertFalse("bastionHost is gone at version 3", after.containsKey("bastionHost"))
        assertFalse("bastionPort is gone at version 3", after.containsKey("bastionPort"))
        assertNotNull(after["sshHost"])
        assertNotNull(after["sshPort"])
        assertEquals("TEXT", after.getValue("sshHost").type)
        assertEquals("INTEGER", after.getValue("sshPort").type)
        assertTrue("the rename must not relax NOT NULL", after.getValue("sshHost").notNull)

        val row = rows("SELECT sshHost, sshPort FROM `connection`").single()
        assertEquals("bastion.example.org", row["sshHost"])
        assertEquals(2222, (row["sshPort"] as Number).toInt())

        // ALTER TABLE ... RENAME COLUMN needs SQLite 3.25+. sqlite-jdbc is far newer, so this test
        // proves the statement is right but not that the engine on the device has it; the app
        // relies on SQLCipher shipping its own recent SQLite rather than on the platform's.
    }

    /**
     * 7→8 adds the two jump-credential columns, where NULL means "the first hop shares the second
     * hop's credential". A default on either column would silently convert every saved two-hop
     * connection into one with its own (missing) credential, so the absence of a default is the
     * assertion that matters.
     */
    @Test
    fun `the 7 to 8 columns are null for existing rows and mean shared`() {
        seedVersion1()
        migrate(1, 7)

        // A two-hop connection saved at version 7: one credential, used for both hops.
        exec(
            "UPDATE `connection` SET sshJumpHost = 'jump.example.org', sshJumpPort = 2022," +
                " sshJumpUser = 'deploy' WHERE id = $CONNECTION_ID",
        )
        assertFalse(columns("connection").containsKey("sshJumpAuthMethod"))

        migrate(7, 8)

        val after = columns("connection")
        for (name in listOf("sshJumpAuthMethod", "sshJumpKeyId")) {
            assertFalse("`$name` must be nullable", after.getValue(name).notNull)
            assertNull("`$name` must have no default: NULL is what means `shared`", after.getValue(name).default)
        }
        assertEquals("TEXT", after.getValue("sshJumpAuthMethod").type)
        assertEquals("INTEGER", after.getValue("sshJumpKeyId").type)

        val row = rows("SELECT * FROM `connection` WHERE id = $CONNECTION_ID").single()
        assertNull("an existing two-hop connection keeps sharing its credential", row["sshJumpAuthMethod"])
        assertNull(row["sshJumpKeyId"])
        // The hop itself is untouched.
        assertEquals("jump.example.org", row["sshJumpHost"])
        assertEquals(2022, (row["sshJumpPort"] as Number).toInt())
        assertEquals("deploy", row["sshJumpUser"])

        // The new password table exists and is empty — nothing is migrated into it.
        assertTrue(tables().contains("ssh_jump_credential"))
        assertEquals(0, rows("SELECT * FROM ssh_jump_credential").size)
        val jump = columns("ssh_jump_credential")
        assertTrue(jump.getValue("connectionId").primaryKey)
        assertEquals("BLOB", jump.getValue("sealedPassword").type)
        assertTrue(jump.getValue("sealedPassword").notNull)
    }

    /**
     * 8→9 adds the offline schema cache (§11): five empty tables and not one existing column
     * touched.
     *
     * Two things are worth failing over. A cached row has to carry the moment it was captured —
     * the screen may not show it without saying when it was taken, so `capturedAt` is NOT NULL
     * and the schema is what enforces that. And the cache has to die with its connection: it is
     * a record of one server, it means nothing without it, and §9's rule that deleting a
     * connection deletes what belongs to it covers the schema as much as the query log. That
     * cascade is checked by actually deleting the connection with foreign keys on.
     */
    @Test
    fun `the 8 to 9 cache tables start empty and die with their connection`() {
        seedVersion1()
        migrate(1, 8)
        for (table in CACHE_TABLES) {
            assertFalse("`$table` must not exist before version 9", tables().contains(table))
        }

        migrate(8, 9)

        for (table in CACHE_TABLES) {
            assertTrue("`$table` is missing at version 9", tables().contains(table))
            assertEquals("`$table` must start empty", 0, rows("SELECT * FROM `$table`").size)
            assertTrue(
                "every cached row must be attributable to a connection",
                columns(table).getValue("connectionId").let { it.notNull && it.primaryKey },
            )
            assertTrue(
                "`index_${table}_connectionId` must exist, or every read scans every cache",
                rows("PRAGMA index_list(`$table`)").any { it["name"] == "index_${table}_connectionId" },
            )
        }

        // The capture time: present, non-null, and the one nullable timestamp is the structure's.
        assertTrue(columns("cached_database").getValue("capturedAt").notNull)
        val cachedTable = columns("cached_table")
        assertTrue(cachedTable.getValue("capturedAt").notNull)
        assertEquals("INTEGER", cachedTable.getValue("capturedAt").type)
        assertFalse(
            "structureCapturedAt is NULL until the table itself has been opened",
            cachedTable.getValue("structureCapturedAt").notNull,
        )
        assertNull(cachedTable.getValue("structureCapturedAt").default)
        // A table that has only been listed keeps its own estimates nullable: the server does not
        // always have them, and a 0 would read as "this table is empty".
        for (name in listOf("approximateRows", "dataBytes", "indexBytes", "comment", "engine")) {
            assertFalse("`cached_table`.`$name` must stay nullable", cachedTable.getValue(name).notNull)
        }

        // A table, a column, an index and a foreign key cached for the seeded connection.
        exec(
            "INSERT INTO `cached_database` (connectionId, name, capturedAt)" +
                " VALUES ($CONNECTION_ID, 'reporting', 1700000900000)",
        )
        exec(
            "INSERT INTO `cached_table` (connectionId, `database`, name, kind, approximateRows," +
                " comment, engine, collation, dataBytes, indexBytes, capturedAt, structureCapturedAt)" +
                " VALUES ($CONNECTION_ID, 'reporting', 'orders', 'TABLE', 42, NULL, 'InnoDB'," +
                " 'utf8mb4_general_ci', 8192, 4096, 1700000900000, NULL)",
        )
        exec(
            "INSERT INTO `cached_column` (connectionId, `database`, tableName, name, typeName," +
                " nullable, defaultValue, isPrimaryKey, extra, comment, position)" +
                " VALUES ($CONNECTION_ID, 'reporting', 'orders', 'id', 'bigint', 0, NULL, 1," +
                " 'auto_increment', NULL, 0)",
        )
        exec(
            "INSERT INTO `cached_index` (connectionId, `database`, tableName, name, isUnique," +
                " columns, position) VALUES ($CONNECTION_ID, 'reporting', 'orders', 'PRIMARY', 1, 'id', 0)",
        )
        exec(
            "INSERT INTO `cached_foreign_key` (connectionId, `database`, tableName, constraintName," +
                " `column`, referencedDatabase, referencedTable, referencedColumn)" +
                " VALUES ($CONNECTION_ID, 'reporting', 'orders', 'fk_customer', 'customer_id'," +
                " 'reporting', 'customers', 'id')",
        )
        assertEquals(
            "orders",
            rows("SELECT name FROM `cached_table`").single()["name"],
        )

        exec("PRAGMA foreign_keys = ON")
        assertEquals(emptyList<Map<String, Any?>>(), rows("PRAGMA foreign_key_check"))
        exec("DELETE FROM `connection` WHERE id = $CONNECTION_ID")
        for (table in CACHE_TABLES) {
            assertEquals(
                "`$table` must be emptied when its connection is deleted",
                0,
                rows("SELECT * FROM `$table`").size,
            )
        }
    }

    /** 4→5 adds the SSH password table without disturbing the MySQL one. */
    @Test
    fun `the 4 to 5 credential table is added alongside the existing one`() {
        seedVersion1()
        migrate(1, 4)
        assertFalse(tables().contains("ssh_credential"))

        migrate(4, 5)
        assertTrue(tables().contains("ssh_credential"))
        assertArrayEquals(
            SEALED_PASSWORD,
            rows("SELECT sealedPassword FROM db_credential").single()["sealedPassword"] as ByteArray,
        )
        assertEquals("KEY", rows("SELECT sshAuthMethod FROM `connection`").single()["sshAuthMethod"])
    }

    /**
     * Every migration is idempotent in the only sense that matters here: running the ladder twice
     * on two separate databases from the same starting point gives the same schema. This catches
     * a migration that depends on leftover state from a previous run.
     */
    @Test
    fun `the ladder is deterministic`() {
        seedVersion1()
        migrateToCurrent()
        val first = tables().associateWith { columns(it) }

        close()
        open()
        migrateToCurrent()
        assertEquals(first, tables().associateWith { columns(it) })
    }

    /** [MigrationStatements.IN_ORDER] has to cover exactly 1 → [CURRENT_VERSION]. */
    @Test
    fun `every version step is covered`() {
        assertEquals(
            (1 until CURRENT_VERSION).toList(),
            MigrationStatements.IN_ORDER.map { it.first },
        )
        assertTrue(MigrationStatements.IN_ORDER.all { it.second.isNotEmpty() })
    }

    private companion object {
        /**
         * The version in `@Database` on [SqlPulseDatabase]. That file cannot be read from here —
         * it imports Room — so bumping the schema means bumping this too, and `every version step
         * is covered` then fails until the new migration is listed.
         */
        const val CURRENT_VERSION = 9

        /** The five tables the offline schema cache lives in, added at version 9. */
        val CACHE_TABLES = listOf(
            "cached_database", "cached_table", "cached_column", "cached_index", "cached_foreign_key",
        )

        const val KEY_ID = 7L
        const val CONNECTION_ID = 3L
        const val FINGERPRINT = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
        val SEALED_KEY = byteArrayOf(1, 2, 3, 0, -128, 127, 42)
        val SEALED_PASSWORD = byteArrayOf(-1, 0, 17, 99)
    }
}
