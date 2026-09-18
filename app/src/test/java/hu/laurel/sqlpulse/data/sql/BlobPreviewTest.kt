package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobPreviewTest {

    @Test
    fun `text stored in a binary column is shown as text`() {
        val bytes = "hello, világ".toByteArray(Charsets.UTF_8)
        assertEquals("hello, világ", BlobPreview.asText(bytes))
        assertTrue(BlobPreview.of(bytes, truncated = false).isText)
    }

    @Test
    fun `a NUL byte means this is not text`() {
        assertNull(BlobPreview.asText(byteArrayOf(0x68, 0x00, 0x69)))
    }

    @Test
    fun `a JPEG header is not text`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertNull(BlobPreview.asText(jpeg))
        assertFalse(BlobPreview.of(jpeg, truncated = false).isText)
    }

    @Test
    fun `tabs and newlines are text, other control characters are not`() {
        assertEquals("a\tb\nc", BlobPreview.asText("a\tb\nc".toByteArray()))
        assertNull(BlobPreview.asText(byteArrayOf(0x07)))
    }

    @Test
    fun `the hex dump has an offset, the bytes, and the printable characters`() {
        val bytes = "AB".toByteArray() + byteArrayOf(0x00, 0x1F)
        assertEquals(
            "00000000  41 42 00 1f                                      AB..",
            BlobPreview.hexDump(bytes),
        )
    }

    @Test
    fun `the dump wraps every sixteen bytes`() {
        val lines = BlobPreview.hexDump(ByteArray(33) { it.toByte() }).lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1], lines[1].startsWith("00000010"))
        assertTrue(lines[2], lines[2].startsWith("00000020"))
    }

    @Test
    fun `empty bytes are empty text, not a dump`() {
        val preview = BlobPreview.of(ByteArray(0), truncated = false)
        assertTrue(preview.isText)
        assertEquals("", preview.content)
    }

    @Test
    fun `being cut short is carried through, because the rest is still on the server`() {
        assertTrue(BlobPreview.of(byteArrayOf(1, 2, 3), truncated = true).truncated)
    }
}
