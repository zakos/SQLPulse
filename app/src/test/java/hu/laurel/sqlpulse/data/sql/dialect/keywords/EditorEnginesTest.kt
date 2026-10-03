package hu.laurel.sqlpulse.data.sql.dialect.keywords

import hu.laurel.sqlpulse.data.export.ResultSerializer
import hu.laurel.sqlpulse.data.query.BuiltInSnippets
import hu.laurel.sqlpulse.data.query.KeyAction
import hu.laurel.sqlpulse.data.query.KeyBar
import hu.laurel.sqlpulse.data.query.KeyBarConfig
import hu.laurel.sqlpulse.data.sql.CellEditor
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnEditors
import hu.laurel.sqlpulse.data.sql.SqlFormatter
import hu.laurel.sqlpulse.data.sql.SqlHighlighter
import hu.laurel.sqlpulse.data.sql.SqlScript
import hu.laurel.sqlpulse.data.sql.TokenRole
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlGrammar
import hu.laurel.sqlpulse.data.sql.dialect.SqlSyntax
import hu.laurel.sqlpulse.ui.query.SqlClause
import hu.laurel.sqlpulse.ui.query.SqlCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor asks the engine instead of assuming MySQL: keywords, quoting, comments, snippets,
 * key bar, INSERT export.
 *
 * The grammars and quote characters here are built in the test, not taken from the engines'
 * dialect files, so this pins what the editor does with a grammar and cannot break when an
 * engine's own file is filled in.
 */
class EditorEnginesTest {

    private val postgres = SqlGrammar.ANSI.copy(dollarQuotes = true)
    private val bracketed = SqlGrammar.ANSI.copy(quotes = SqlGrammar.ANSI.quotes + ('[' to ']'))

    /** An engine as far as the editor can tell: its name, its quote, its grammar. */
    private class Fake(
        override val engine: DatabaseEngine,
        private val open: String,
        private val close: String,
        override val grammar: SqlGrammar,
    ) : SqlSyntax {
        override fun quoteIdentifier(name: String) = open + name + close
        override fun stringLiteral(value: String) = "'$value'"
        override fun nullSafeEquals(quotedColumn: String) = "$quotedColumn = ?"
        override val likeEscape = ""
        override fun limit(select: String, limit: Int, offset: Int?, ordered: Boolean) = select
        override fun blobLengthAndHead(quotedColumn: String, maxBytes: Int) = quotedColumn
    }

    private val pg = Fake(DatabaseEngine.POSTGRESQL, "\"", "\"", SqlGrammar.ANSI.copy(dollarQuotes = true))
    private val mssql = Fake(DatabaseEngine.SQLSERVER, "[", "]", SqlGrammar.ANSI.copy(quotes = SqlGrammar.ANSI.quotes + ('[' to ']')))
    private val sqlite = Fake(DatabaseEngine.SQLITE, "\"", "\"", SqlGrammar.ANSI.copy(quotes = SqlGrammar.ANSI.quotes + ('`' to '`')))

    // --- keywords ----------------------------------------------------------------------------

    @Test
    fun `MySQL keywords are the set the highlighter always had`() {
        val words = SqlKeywords.forEngine(DatabaseEngine.MYSQL)
        assertEquals(69, words.size)
        assertTrue("describe" in words && "show" in words && "limit" in words && "replace" in words)
    }

    @Test
    fun `each engine has its own words and lacks the others`() {
        val pgWords = SqlKeywords.forEngine(DatabaseEngine.POSTGRESQL)
        val msWords = SqlKeywords.forEngine(DatabaseEngine.SQLSERVER)
        val liteWords = SqlKeywords.forEngine(DatabaseEngine.SQLITE)
        assertTrue("ilike" in pgWords && "returning" in pgWords)
        assertFalse("describe" in pgWords)
        assertTrue("top" in msWords && "exec" in msWords)
        assertFalse("limit" in msWords || "show" in msWords)
        assertTrue("pragma" in liteWords && "glob" in liteWords)
        assertFalse("show" in liteWords || "describe" in liteWords)
        // Every engine colours the core of SQL.
        DatabaseEngine.entries.forEach { engine ->
            val words = SqlKeywords.forEngine(engine)
            listOf("select", "from", "where", "join", "insert", "update", "delete").forEach {
                assertTrue("$engine $it", it in words)
            }
        }
    }

    // --- highlighter -------------------------------------------------------------------------

    private fun roles(sql: String, grammar: SqlGrammar, engine: DatabaseEngine) =
        SqlHighlighter.tokenize(sql, grammar, SqlKeywords.forEngine(engine)).map { it.role }

    @Test
    fun `double quotes are a string in MySQL and a name elsewhere`() {
        assertEquals(listOf(TokenRole.STRING), roles("\"a\"", SqlGrammar.MYSQL, DatabaseEngine.MYSQL))
        assertEquals(listOf(TokenRole.QUOTED_IDENTIFIER), roles("\"a\"", SqlGrammar.ANSI, DatabaseEngine.POSTGRESQL))
    }

    @Test
    fun `brackets are a name in T-SQL, spaces and all`() {
        val tokens = SqlHighlighter.tokenize("SELECT [order total] FROM t", bracketed, SqlServerKeywords.ALL)
        val name = tokens.single { it.role == TokenRole.QUOTED_IDENTIFIER }
        assertEquals("[order total]", "SELECT [order total] FROM t".substring(name.start, name.end))
    }

    @Test
    fun `hash is a comment only where the grammar says so`() {
        assertEquals(listOf(TokenRole.COMMENT), roles("# note", SqlGrammar.MYSQL, DatabaseEngine.MYSQL))
        assertTrue(roles("#temp", bracketed, DatabaseEngine.SQLSERVER).none { it == TokenRole.COMMENT })
    }

    @Test
    fun `dollar quoted body is one string`() {
        val sql = "SELECT \$\$it's; here\$\$ FROM t"
        val tokens = SqlHighlighter.tokenize(sql, postgres, PostgresKeywords.ALL)
        val string = tokens.single { it.role == TokenRole.STRING }
        assertEquals("\$\$it's; here\$\$", sql.substring(string.start, string.end))
        // A positional parameter is not a quote.
        assertTrue(SqlHighlighter.tokenize("SELECT \$1", postgres, PostgresKeywords.ALL).none { it.role == TokenRole.STRING })
    }

    @Test
    fun `keywords follow the engine`() {
        assertTrue(roles("limit", SqlGrammar.MYSQL, DatabaseEngine.MYSQL).contains(TokenRole.KEYWORD))
        assertTrue(roles("limit", bracketed, DatabaseEngine.SQLSERVER).isEmpty())
        assertTrue(roles("top", bracketed, DatabaseEngine.SQLSERVER).contains(TokenRole.KEYWORD))
        assertTrue(roles("pragma", SqlGrammar.ANSI, DatabaseEngine.SQLITE).contains(TokenRole.KEYWORD))
    }

    @Test
    fun `MySQL tokens come out as they always did`() {
        val sql = "SELECT `a`, 'x\\'y', \"z\" FROM t -- c\n# d\n/* e */ WHERE :p = 1"
        assertEquals(
            SqlHighlighter.tokenize(sql),
            SqlHighlighter.tokenize(sql, SqlGrammar.MYSQL, MySqlKeywords.ALL),
        )
        assertEquals(
            listOf(
                TokenRole.KEYWORD, TokenRole.QUOTED_IDENTIFIER, TokenRole.STRING, TokenRole.STRING,
                TokenRole.KEYWORD, TokenRole.COMMENT, TokenRole.COMMENT, TokenRole.COMMENT,
                TokenRole.KEYWORD, TokenRole.PARAMETER, TokenRole.NUMBER,
            ),
            SqlHighlighter.tokenize(sql).map { it.role },
        )
    }

    // --- script splitting and formatting -----------------------------------------------------

    @Test
    fun `a semicolon inside a dollar body or a bracketed name does not split`() {
        val body = "DO \$\$ BEGIN PERFORM 1; END \$\$; SELECT 1"
        assertEquals(2, SqlScript.split(body, postgres).size)
        assertEquals("DO \$\$ BEGIN PERFORM 1; END \$\$", SqlScript.split(body, postgres).first().sql)

        assertEquals(1, SqlScript.split("SELECT [a;b] FROM t", bracketed).size)
        // MySQL's own reading of a double-quoted string is unchanged.
        assertEquals(1, SqlScript.split("SELECT \"a;b\"").size)
        assertEquals(2, SqlScript.split("SELECT 1; SELECT 2").size)
    }

    @Test
    fun `a backslash escapes in MySQL and nowhere else`() {
        val sql = "SELECT 'a\\'; SELECT 2"
        assertEquals(1, SqlScript.split(sql).size)
        assertEquals(2, SqlScript.split(sql, SqlGrammar.ANSI).size)
    }

    @Test
    fun `the formatter keeps a bracketed name whole`() {
        val formatted = SqlFormatter.format("select [first name], b from [my table] where x = 1", bracketed)
        assertTrue(formatted, formatted.contains("[first name]"))
        assertTrue(formatted, formatted.contains("[my table]"))
        assertEquals(SqlFormatter.format("select a from t"), SqlFormatter.format("select a from t", SqlGrammar.MYSQL))
    }

    // --- completion --------------------------------------------------------------------------

    @Test
    fun `statement starters per engine`() {
        val mysql = SqlCompletion.contextAt("", 0, MySqlDialect).keywords
        assertTrue("DESCRIBE" in mysql && "USE" in mysql && "REPLACE INTO" in mysql)
        val pgStart = SqlCompletion.contextAt("", 0, pg).keywords
        assertTrue("SHOW" in pgStart)
        assertFalse("DESCRIBE" in pgStart || "USE" in pgStart || "REPLACE INTO" in pgStart)
        assertTrue("PRAGMA" in SqlCompletion.contextAt("", 0, sqlite).keywords)
        assertFalse("SHOW" in SqlCompletion.contextAt("", 0, sqlite).keywords)
        val ms = SqlCompletion.contextAt("", 0, mssql).keywords
        assertTrue("EXEC" in ms && "USE" in ms)
        assertFalse("EXPLAIN" in ms || "SHOW" in ms)
    }

    @Test
    fun `T-SQL pages with TOP and OFFSET, not LIMIT`() {
        val select = SqlCompletion.keywordsAfter(SqlClause.SELECT, DatabaseEngine.SQLSERVER)
        assertTrue("TOP" in select)
        assertFalse("LIMIT" in SqlCompletion.keywordsAfter(SqlClause.WHERE, DatabaseEngine.SQLSERVER))
        assertTrue("OFFSET" in SqlCompletion.keywordsAfter(SqlClause.ORDER_BY, DatabaseEngine.SQLSERVER))
        // MySQL's and PostgreSQL's lists are untouched.
        assertTrue("LIMIT" in SqlCompletion.keywordsAfter(SqlClause.WHERE, DatabaseEngine.MYSQL))
        assertTrue("LIMIT" in SqlCompletion.keywordsAfter(SqlClause.WHERE, DatabaseEngine.POSTGRESQL))
        assertFalse("TOP" in SqlCompletion.keywordsAfter(SqlClause.SELECT, DatabaseEngine.MYSQL))
    }

    @Test
    fun `nothing is offered inside a bracketed name or a dollar body`() {
        assertTrue(SqlCompletion.contextAt("SELECT * FROM [my ta", 20, mssql).suppressed)
        assertFalse(SqlCompletion.contextAt("SELECT * FROM [my table] ", 25, mssql).suppressed)
        assertTrue(SqlCompletion.contextAt("DO \$\$ SELECT ", 14, pg).suppressed)
        assertFalse(SqlCompletion.contextAt("DO \$\$ x \$\$ SELECT ", 20, pg).suppressed)
        assertTrue(SqlCompletion.contextAt("SELECT \"a", 9, pg).suppressed)
    }

    @Test
    fun `a quoted name resolves as a table in the engine's own quotes`() {
        val context = SqlCompletion.contextAt("SELECT t. FROM [order] t", 9, mssql)
        assertEquals(listOf("order"), context.columnTables)
        val quoted = SqlCompletion.contextAt("SELECT t. FROM \"order\" t", 9, pg)
        assertEquals(listOf("order"), quoted.columnTables)
    }

    // --- snippets ----------------------------------------------------------------------------

    @Test
    fun `MySQL gets the snippets it always had`() {
        assertEquals(BuiltInSnippets.ALL, BuiltInSnippets.forEngine(DatabaseEngine.MYSQL))
        val ids = BuiltInSnippets.ALL.map { it.id }
        assertTrue("builtin:describe" in ids && "builtin:show_index" in ids)
        assertEquals("SELECT *\nFROM {{table}}\nWHERE {{condition}}\nLIMIT 100", BuiltInSnippets.ALL.first().body)
    }

    @Test
    fun `MySQL-only snippets are not offered elsewhere`() {
        listOf(DatabaseEngine.POSTGRESQL, DatabaseEngine.SQLSERVER, DatabaseEngine.SQLITE).forEach { engine ->
            val list = BuiltInSnippets.forEngine(engine)
            assertTrue(list.none { it.body.startsWith("DESCRIBE") || it.body.startsWith("SHOW") })
            assertEquals(list.size, list.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `SQL Server selects use TOP and never LIMIT`() {
        val list = BuiltInSnippets.forEngine(DatabaseEngine.SQLSERVER)
        assertEquals("SELECT TOP (100) *\nFROM {{table}}\nWHERE {{condition}}", list.first { it.id == "builtin:select_where" }.body)
        assertTrue(list.none { it.body.contains("LIMIT") })
        assertTrue(list.none { it.id == "builtin:explain" })
        assertTrue(list.any { it.body.startsWith("EXEC sp_helpindex") })
        assertTrue(BuiltInSnippets.forEngine(DatabaseEngine.POSTGRESQL).none { it.body.contains("TOP (") })
    }

    @Test
    fun `SQLite gets PRAGMA and a query plan`() {
        val list = BuiltInSnippets.forEngine(DatabaseEngine.SQLITE)
        assertEquals("PRAGMA table_info({{table}})", list.first { it.id == "builtin:pragma_table_info" }.body)
        assertTrue(list.first { it.id == "builtin:explain" }.body.startsWith("EXPLAIN QUERY PLAN"))
        assertTrue(BuiltInSnippets.forEngine(DatabaseEngine.POSTGRESQL).none { it.body.contains("PRAGMA") })
    }

    @Test
    fun `no engine's templates change structure and every write has a where`() {
        val banned = Regex("""\b(ALTER|CREATE|DROP|TRUNCATE|GRANT|REVOKE|RENAME)\b""", RegexOption.IGNORE_CASE)
        DatabaseEngine.entries.forEach { engine ->
            BuiltInSnippets.forEngine(engine).forEach {
                assertFalse("$engine ${it.name}", banned.containsMatchIn(it.body))
                if (it.body.startsWith("UPDATE") || it.body.startsWith("DELETE")) {
                    assertTrue(it.body.contains("WHERE"))
                }
            }
        }
    }

    // --- key bar -----------------------------------------------------------------------------

    private fun bar(syntax: SqlSyntax) = KeyBar.forEngine(KeyBar.visible(KeyBarConfig.DEFAULT), syntax)

    private fun text(items: List<hu.laurel.sqlpulse.data.query.KeyBarItem>, id: String) =
        (items.first { it.id == id }.action as KeyAction.Insert).text

    @Test
    fun `MySQL key bar is untouched`() {
        assertEquals(KeyBar.visible(KeyBarConfig.DEFAULT), bar(MySqlDialect))
        assertEquals("`", text(bar(MySqlDialect), "`"))
    }

    @Test
    fun `the backtick key types the engine's quote`() {
        assertEquals("\"", text(bar(pg), KeyBar.QUOTE_KEY))
        assertEquals("\"", text(bar(sqlite), KeyBar.QUOTE_KEY))
        // T-SQL already has [ and ] keys; the backtick key goes.
        assertTrue(bar(mssql).none { it.id == KeyBar.QUOTE_KEY })
        assertTrue(bar(mssql).any { it.id == "[" } && bar(mssql).any { it.id == "]" })
    }

    @Test
    fun `the LIMIT key becomes TOP in T-SQL only`() {
        assertEquals("TOP", text(bar(mssql), KeyBar.LIMIT_KEY))
        assertEquals("LIMIT", text(bar(pg), KeyBar.LIMIT_KEY))
        assertEquals("LIMIT", text(bar(sqlite), KeyBar.LIMIT_KEY))
    }

    @Test
    fun `engine keys keep their stored ids and positions`() {
        val ids = bar(pg).map { it.id }
        assertEquals(KeyBar.visible(KeyBarConfig.DEFAULT).map { it.id }, ids)
        assertNotNull(bar(pg).firstOrNull { it.id == KeyBar.LIMIT_KEY })
    }

    // --- INSERT export -----------------------------------------------------------------------

    private val table = hu.laurel.sqlpulse.data.sql.ResultTable(
        columns = listOf(
            hu.laurel.sqlpulse.data.sql.ColumnMeta("name", hu.laurel.sqlpulse.data.sql.CellType.TEXT, "text", null),
            hu.laurel.sqlpulse.data.sql.ColumnMeta("ok", hu.laurel.sqlpulse.data.sql.CellType.BOOLEAN, "bool", null),
        ),
        rows = listOf(listOf(CellValue.Text("it's a\\b é"), CellValue.Bool(true))),
    )

    @Test
    fun `INSERT export quotes and escapes per engine`() {
        assertEquals(
            "INSERT INTO `t` (`name`, `ok`) VALUES ('it\\'s a\\\\b é', 1);",
            ResultSerializer.toSqlInserts(table, "t"),
        )
        assertEquals(
            "INSERT INTO \"t\" (\"name\", \"ok\") VALUES ('it''s a\\b é', TRUE);",
            ResultSerializer.toSqlInserts(table, "t", pg),
        )
        assertEquals(
            "INSERT INTO [t] ([name], [ok]) VALUES (N'it''s a\\b é', 1);",
            ResultSerializer.toSqlInserts(table, "t", mssql),
        )
        assertEquals(
            "INSERT INTO \"t\" (\"name\", \"ok\") VALUES ('it''s a\\b é', 1);",
            ResultSerializer.toSqlInserts(table, "t", sqlite),
        )
    }

    // --- column editors ----------------------------------------------------------------------

    @Test
    fun `column types of the other engines pick the right editor`() {
        assertEquals(CellEditor.Bool, ColumnEditors.of("bit"))
        assertEquals(CellEditor.Bool, ColumnEditors.of("boolean"))
        assertEquals(CellEditor.Number, ColumnEditors.of("bigserial"))
        assertEquals(CellEditor.Number, ColumnEditors.of("money"))
        assertEquals(CellEditor.DateTime, ColumnEditors.of("timestamp with time zone"))
        assertEquals(CellEditor.DateTime, ColumnEditors.of("datetime2"))
        // MySQL's are as before.
        assertEquals(CellEditor.Bool, ColumnEditors.of("tinyint(1)"))
        assertEquals(CellEditor.Number, ColumnEditors.of("tinyint(4)"))
        assertEquals(CellEditor.Text, ColumnEditors.of("varchar(20)"))
    }
}
