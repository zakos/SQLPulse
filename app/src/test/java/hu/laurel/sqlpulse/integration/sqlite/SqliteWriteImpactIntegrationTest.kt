package hu.laurel.sqlpulse.integration.sqlite

import hu.laurel.sqlpulse.data.sql.dialect.SqliteDialect
import java.io.File
import java.sql.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The write count and the DML preview on SQLite, for the statement shape a phone user actually
 * typed: double-quoted strings. SQLite reads a `"..."` that names no column as a string literal,
 * and the derived SELECT keeps the very same text, so it resolves them exactly as the write does.
 */
class SqliteWriteImpactIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var file: File

    @Before
    fun makeFile() {
        file = SqliteFixture.create(
            folder.newFile("article.sqlite"),
            listOf(
                "CREATE TABLE article (ArtNr TEXT, SubArtNr TEXT, Label TEXT, PRIMARY KEY (ArtNr, SubArtNr))",
                "INSERT INTO article VALUES ('1', 'a', 'csirke mell'), ('2', 'a', 'csirke comb'), ('3', 'a', 'marha')",
            ),
        )
    }

    private fun <T> withConnection(block: (Connection) -> T): T = SqliteFixture.session(file).use(block)

    private fun count(connection: Connection, sql: String): Long {
        val query = SqliteDialect.writeCountQuery(sql)
        assertNotNull("no count derived for: $sql", query)
        val bound = SqliteDialect.bindParameters(query!!)
        return connection.prepareStatement(bound.sql).use { it.executeQuery().use { rows -> rows.next(); rows.getLong(1) } }
    }

    @Test
    fun `double quoted strings are counted`() = withConnection { connection ->
        assertEquals(2L, count(connection, """UPDATE article set ArtNr="x" where Label like "%csirk%""""))
        assertEquals(2L, count(connection, """DELETE FROM article WHERE Label LIKE "%csirk%""""))
    }

    @Test
    fun `double quoted strings are previewed with the new value`() = withConnection { connection ->
        val sql = """UPDATE article set ArtNr="x" where Label like "%csirk%""""
        val preview = SqliteDialect.writePreviewQuery(sql, 20)
        assertNotNull(preview)
        val bound = SqliteDialect.bindParameters(preview!!.sql)
        val seen = mutableListOf<String>()
        connection.prepareStatement(bound.sql).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) seen += rows.getString("Label") + "->" + rows.getString("ArtNr (new)")
            }
        }
        assertEquals(listOf("csirke mell->x", "csirke comb->x"), seen)
    }
}
