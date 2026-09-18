package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonFormatterTest {

    @Test
    fun `an object is laid out one field per line`() {
        assertEquals(
            """
            {
              "id": 7,
              "name": "Anna"
            }
            """.trimIndent(),
            JsonFormatter.pretty("""{"id":7,"name":"Anna"}"""),
        )
    }

    @Test
    fun `nesting is indented`() {
        assertEquals(
            """
            {
              "order": {
                "items": [
                  1,
                  2
                ]
              }
            }
            """.trimIndent(),
            JsonFormatter.pretty("""{"order":{"items":[1,2]}}"""),
        )
    }

    @Test
    fun `empty objects and arrays stay on one line`() {
        assertEquals("{\n  \"a\": {},\n  \"b\": []\n}", JsonFormatter.pretty("""{"a":{},"b":[]}"""))
        assertEquals("[]", JsonFormatter.pretty("[]"))
        assertEquals("{}", JsonFormatter.pretty("{}"))
    }

    @Test
    fun `a string is copied through exactly, escapes and all`() {
        val formatted = JsonFormatter.pretty("""{"note":"a \"quoted\" word, and a \\ backslash"}""")
        assertEquals(
            "{\n  \"note\": \"a \\\"quoted\\\" word, and a \\\\ backslash\"\n}",
            formatted,
        )
    }

    @Test
    fun `a brace inside a string does not open a level`() {
        assertEquals("{\n  \"a\": \"{not an object}\"\n}", JsonFormatter.pretty("""{"a":"{not an object}"}"""))
    }

    @Test
    fun `text that is not JSON is left alone`() {
        assertNull(JsonFormatter.pretty("hello"))
        assertNull(JsonFormatter.pretty("12"))
        assertNull(JsonFormatter.pretty(""))
        // A truncated document must not be presented as if it were whole.
        assertNull(JsonFormatter.pretty("""{"id":7,"""))
        assertNull(JsonFormatter.pretty("""{"id":}"""))
        // Trailing rubbish after a valid document is not a valid document.
        assertNull(JsonFormatter.pretty("""{"id":7} and then some"""))
    }

    @Test
    fun `formatting twice gives the same text`() {
        val once = JsonFormatter.pretty("""{"a":[1,{"b":null}],"c":true}""")!!
        assertEquals(once, JsonFormatter.pretty(once))
    }
}
