package hu.laurel.sqlpulse.data.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetsAndKeyBarTest {

    // --- Snippets ----------------------------------------------------------------------------

    @Test
    fun `expand strips the marks and selects the first placeholder`() {
        val result = SnippetEngine.expand("SELECT *\nFROM {{table}}\nWHERE {{condition}}")
        assertEquals("SELECT *\nFROM table\nWHERE condition", result.text)
        assertEquals("table", result.text.substring(result.selectionStart, result.selectionEnd))
    }

    @Test
    fun `a snippet without placeholders leaves the caret after it`() {
        val result = SnippetEngine.expand("SHOW TABLES")
        assertEquals(11, result.selectionStart)
        assertEquals(11, result.selectionEnd)
    }

    @Test
    fun `an unclosed or empty mark is plain text`() {
        assertEquals("a {{ b", SnippetEngine.expand("a {{ b").text)
        assertEquals("x {{}} y", SnippetEngine.expand("x {{}} y").text)
    }

    @Test
    fun `a statement snippet goes to an empty editor as it is`() {
        val result = SnippetEngine.insert("", 0, 0, "SELECT * FROM {{table}}")
        assertEquals("SELECT * FROM table", result.text)
        assertEquals("table", result.text.substring(result.selectionStart, result.selectionEnd))
    }

    @Test
    fun `a statement snippet after other text starts a statement of its own`() {
        val result = SnippetEngine.insert("SELECT 1;", 9, 9, "DELETE FROM {{table}}\nWHERE {{condition}}")
        assertEquals("SELECT 1;\n\nDELETE FROM table\nWHERE condition", result.text)
        assertEquals("table", result.text.substring(result.selectionStart, result.selectionEnd))
        // With a newline already there, only one more is added.
        assertEquals("a;\n\nSELECT 1", SnippetEngine.insert("a;\n", 3, 3, "SELECT 1").text)
        assertEquals("a;\n\nSELECT 1", SnippetEngine.insert("a;\n\n", 4, 4, "SELECT 1").text)
    }

    @Test
    fun `a fragment goes in line with a space after what precedes it`() {
        assertEquals("SELECT * FROM t WHERE x = 1", SnippetEngine.insert("SELECT * FROM t", 15, 15, "WHERE x = 1").text)
        assertEquals("SELECT * FROM t WHERE x = 1", SnippetEngine.insert("SELECT * FROM t ", 16, 16, "WHERE x = 1").text)
        assertEquals("f(a, b)", SnippetEngine.insert("f(", 2, 2, "a, b)").text)
    }

    @Test
    fun `inserting replaces the selection and keeps what follows`() {
        val result = SnippetEngine.insert("SELECT x FROM t", 7, 8, "{{cols}}")
        assertEquals("SELECT cols FROM t", result.text)
        assertEquals("cols", result.text.substring(result.selectionStart, result.selectionEnd))
        // A reversed selection is the same selection.
        assertEquals(result.text, SnippetEngine.insert("SELECT x FROM t", 8, 7, "{{cols}}").text)
    }

    @Test
    fun `selection offsets are clamped`() {
        val result = SnippetEngine.insert("ab", 50, 90, "{{x}}")
        assertEquals("ab x", result.text)
    }

    @Test
    fun `built in snippets are templates with no structure changes`() {
        val banned = Regex("""\b(ALTER|CREATE|DROP|TRUNCATE|GRANT|REVOKE|RENAME)\b""", RegexOption.IGNORE_CASE)
        assertTrue(BuiltInSnippets.ALL.size >= 8)
        BuiltInSnippets.ALL.forEach { snippet ->
            assertFalse(snippet.name, banned.containsMatchIn(snippet.body))
            assertTrue(snippet.builtIn)
            assertTrue(snippet.body.contains("{{"))
        }
        assertEquals(BuiltInSnippets.ALL.size, BuiltInSnippets.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun `built in updates and deletes always carry a where`() {
        BuiltInSnippets.ALL
            .filter { it.body.startsWith("UPDATE") || it.body.startsWith("DELETE") }
            .also { assertTrue(it.size >= 2) }
            .forEach { assertTrue(it.name, Regex("""\bWHERE\b""").containsMatchIn(it.body)) }
    }

    @Test
    fun `every built in snippet has a name and expands to text`() {
        BuiltInSnippets.ALL.forEach {
            assertTrue(it.name.isNotBlank())
            val expanded = SnippetEngine.expand(it.body)
            assertFalse(expanded.text.contains("{{"))
            assertTrue(expanded.selectionEnd > expanded.selectionStart)
        }
    }

    @Test
    fun `search matches name or body, ignoring case`() {
        val all = BuiltInSnippets.ALL
        assertEquals(all, SnippetEngine.search(all, "  "))
        assertTrue(SnippetEngine.search(all, "join").any { it.id == "builtin:select_join" })
        assertTrue(SnippetEngine.search(all, "having").any { it.id == "builtin:select_group" })
        assertTrue(SnippetEngine.search(all, "zzz-nothing").isEmpty())
    }

    @Test
    fun `statement starters`() {
        assertTrue(SnippetEngine.startsStatement("  select 1"))
        assertTrue(SnippetEngine.startsStatement("EXPLAIN\nSELECT"))
        assertFalse(SnippetEngine.startsStatement("WHERE x = 1"))
        assertFalse(SnippetEngine.startsStatement("DESC"))
        assertFalse(SnippetEngine.startsStatement("selection_of"))
    }

    // --- User snippets -----------------------------------------------------------------------

    @Test
    fun `upsert adds then replaces by id`() {
        val added = UserSnippets.upsert(emptyList(), "a", "  Orders  ", "SELECT 1")!!
        assertEquals(listOf(Snippet("a", "Orders", "SELECT 1")), added)
        val replaced = UserSnippets.upsert(added, "a", "Orders v2", "SELECT 2")!!
        assertEquals(listOf(Snippet("a", "Orders v2", "SELECT 2")), replaced)
        val second = UserSnippets.upsert(replaced, "b", "Other", "SELECT 3")!!
        assertEquals(listOf("a", "b"), second.map { it.id })
    }

    @Test
    fun `upsert refuses a blank name or body and a full list`() {
        assertNull(UserSnippets.upsert(emptyList(), "a", " ", "SELECT 1"))
        assertNull(UserSnippets.upsert(emptyList(), "a", "n", " \n"))
        val full = (1..UserSnippets.MAX_SNIPPETS).map { Snippet("$it", "n$it", "b") }
        assertNull(UserSnippets.upsert(full, "new", "n", "b"))
        // Replacing in a full list is still fine.
        assertNotNull(UserSnippets.upsert(full, "5", "renamed", "b"))
    }

    @Test
    fun `upsert trims over long names and bodies`() {
        val result = UserSnippets.upsert(emptyList(), "a", "n".repeat(500), "b".repeat(UserSnippets.MAX_BODY + 10))!!
        assertEquals(UserSnippets.MAX_NAME, result.single().name.length)
        assertEquals(UserSnippets.MAX_BODY, result.single().body.length)
    }

    @Test
    fun `delete removes only that snippet`() {
        val list = listOf(Snippet("a", "A", "1"), Snippet("b", "B", "2"))
        assertEquals(listOf(list[1]), UserSnippets.delete(list, "a"))
        assertEquals(list, UserSnippets.delete(list, "zzz"))
    }

    @Test
    fun `snippets survive the round trip, newlines quotes and unicode included`() {
        val list = listOf(
            Snippet("1", "Számlák \"nyitott\"", "SELECT *\nFROM számla\nWHERE állapot = 'nyitott'\t-- \\ done"),
            Snippet("2", "Marks", "SELECT {{a}} FROM {{b}}"),
        )
        assertEquals(list, UserSnippets.decode(UserSnippets.encode(list)))
    }

    @Test
    fun `decode copes with nothing, junk and half valid entries`() {
        assertEquals(emptyList<Snippet>(), UserSnippets.decode(null))
        assertEquals(emptyList<Snippet>(), UserSnippets.decode(""))
        assertEquals(emptyList<Snippet>(), UserSnippets.decode("{not json"))
        assertEquals(emptyList<Snippet>(), UserSnippets.decode("""{"a":1}"""))
        val text = """[{"id":"1","name":"ok","body":"SELECT 1"},{"id":"2","name":"","body":"x"},{"name":"n","body":"b"},5,
            {"id":"1","name":"dup","body":"y"}]"""
        assertEquals(listOf(Snippet("1", "ok", "SELECT 1")), UserSnippets.decode(text))
    }

    // --- Key bar -----------------------------------------------------------------------------

    @Test
    fun `the default bar keeps the original keys and adds the new ones`() {
        val labels = KeyBar.visible(KeyBarConfig.DEFAULT).mapNotNull { it.label }
        listOf("SELECT", "FROM", "WHERE", "*", "=", "<", ">", ",", "'", "%", "(", ")").forEach { assertTrue(it, it in labels) }
        listOf(";", "<>", ">=", "<=", "!=", "`", "_", "[", "]", ".", ":", "AND", "OR", "NOT", "NULL", "IS", "IN",
            "LIKE", "JOIN", "ON", "GROUP BY", "ORDER BY", "LIMIT").forEach { assertTrue(it, it in labels) }
        val actions = KeyBar.visible(KeyBarConfig.DEFAULT).map { it.action }
        assertTrue(KeyAction.Undo in actions && KeyAction.Redo in actions && KeyAction.Snippets in actions)
        assertEquals(KeyBar.CATALOGUE.size, KeyBar.CATALOGUE.map { it.id }.toSet().size)
    }

    @Test
    fun `move shifts a key and stops at the ends`() {
        val first = KeyBar.DEFAULT_ORDER[0]
        val second = KeyBar.DEFAULT_ORDER[1]
        val moved = KeyBar.move(KeyBarConfig.DEFAULT, first, 1)
        assertEquals(listOf(second, first), moved.order.take(2))
        assertEquals(KeyBarConfig.DEFAULT, KeyBar.move(KeyBarConfig.DEFAULT, first, -1))
        val last = KeyBar.DEFAULT_ORDER.last()
        assertEquals(last, KeyBar.move(KeyBarConfig.DEFAULT, last, -1).order.let { it[it.size - 2] })
        assertEquals(KeyBarConfig.DEFAULT, KeyBar.move(KeyBarConfig.DEFAULT, last, 5))
        assertEquals(KeyBarConfig.DEFAULT, KeyBar.move(KeyBarConfig.DEFAULT, "nonsense", 1))
    }

    @Test
    fun `hiding removes a key from the bar but keeps it in the settings list`() {
        val hidden = KeyBar.setShown(KeyBarConfig.DEFAULT, "OR", false)
        assertFalse(KeyBar.visible(hidden).any { it.id == "OR" })
        assertEquals(false, KeyBar.entries(hidden).single { it.first.id == "OR" }.second)
        val shown = KeyBar.setShown(hidden, "OR", true)
        assertTrue(KeyBar.visible(shown).any { it.id == "OR" })
    }

    @Test
    fun `a key added later appears, visible, at the end`() {
        val old = KeyBarConfig(order = listOf("SELECT", "FROM"), hidden = setOf("FROM"))
        val entries = KeyBar.entries(old)
        assertEquals(KeyBar.CATALOGUE.size, entries.size)
        assertEquals(listOf("SELECT", "FROM"), entries.take(2).map { it.first.id })
        assertTrue(entries.drop(2).all { it.second })
        assertFalse(entries[1].second)
    }

    @Test
    fun `unknown and repeated ids in a stored order are ignored`() {
        val config = KeyBarConfig(order = listOf("ghost", "FROM", "FROM", "SELECT"))
        assertEquals(listOf("FROM", "SELECT"), KeyBar.entries(config).take(2).map { it.first.id })
    }

    @Test
    fun `the config survives the round trip`() {
        val config = KeyBar.setShown(KeyBar.move(KeyBarConfig.DEFAULT, "LIMIT", -5), "NOT", false)
        assertEquals(config, KeyBar.decode(KeyBar.encode(config)))
    }

    @Test
    fun `an unreadable config gives the default bar`() {
        assertEquals(KeyBarConfig.DEFAULT, KeyBar.decode(null))
        assertEquals(KeyBarConfig.DEFAULT, KeyBar.decode("junk"))
        assertEquals(KeyBarConfig.DEFAULT, KeyBar.decode("[]"))
        assertEquals(KeyBarConfig.DEFAULT, KeyBar.decode("""{"order":["ghost"],"hidden":[]}"""))
    }

    // --- Key spacing -------------------------------------------------------------------------

    private fun key(key: String, before: String, after: String = "") = KeyInsertion.textFor(key, before, after)

    @Test
    fun `a keyword gets a space before when a word precedes, and one after`() {
        assertEquals("SELECT ", key("SELECT", ""))
        assertEquals(" FROM ", key("FROM", "SELECT *"))
        assertEquals("FROM ", key("FROM", "SELECT * "))
        assertEquals("FROM ", key("FROM", "SELECT *\n"))
        assertEquals("AND ", key("AND", "x = 1 "))
    }

    @Test
    fun `no extra space after when one is already there or a bracket closes`() {
        assertEquals("AND", key("AND", "a ", " b"))
        assertEquals("NOT", key("NOT", "a ", ")"))
        assertEquals("NULL", key("NULL", "a IS ", ";"))
        assertEquals("IN ", key("IN", "(", "x"))
    }

    @Test
    fun `no space right after an opening bracket`() {
        assertEquals("NOT ", key("NOT", "WHERE ("))
        assertEquals("NOT ", key("NOT", "f("))
    }

    @Test
    fun `comparison operators are spaced like keywords`() {
        assertEquals(" = ", key("=", "id"))
        assertEquals("<> ", key("<>", "id "))
        assertEquals(" >= ", key(">=", "n"))
    }

    @Test
    fun `punctuation goes in bare`() {
        listOf(",", ";", ".", "(", ")", "'", "`", "_", "[", "]", "%").forEach {
            assertEquals(it, key(it, "word"))
            assertEquals(it, key(it, "word ", " x"))
        }
    }

    @Test
    fun `a star after a word is spaced, but inside count it is not`() {
        assertEquals(" *", key("*", "SELECT"))
        assertEquals("*", key("*", "SELECT "))
        assertEquals("*", key("*", "COUNT("))
        assertEquals(" :", key(":", "x"))
        assertEquals(":", key(":", "x = "))
    }
}
