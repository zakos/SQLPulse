package hu.laurel.sqlpulse.data.backup

import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFileTest {

    private val passphrase = "correct horse battery".toCharArray()
    private val random = SecureRandom()

    private fun write(payload: BackupPayload, at: Long = 1_758_000_000_000L): ByteArray =
        BackupFile.write(payload, passphrase.copyOf(), APP_VERSION, at, random)

    @Test
    fun `round trip without secrets`() {
        val payload = BackupSamples.payload(withSecrets = false)
        val file = write(payload)
        assertEquals(payload, BackupFile.read(file, passphrase.copyOf()))
    }

    @Test
    fun `round trip with secrets`() {
        val payload = BackupSamples.payload(withSecrets = true)
        val file = write(payload)
        val read = BackupFile.read(file, passphrase.copyOf())
        assertEquals(payload, read)
        assertTrue(read.containsSecrets)
        assertEquals("hunter2-db", read.connections.first().dbPassword)
    }

    @Test
    fun `an empty backup round trips`() {
        val file = write(BackupPayload())
        val metadata = BackupFile.peek(file)
        assertEquals(0, metadata.connectionCount)
        assertEquals(0, metadata.sshKeyCount)
        assertTrue(!metadata.containsSecrets)
        val read = BackupFile.read(file, passphrase.copyOf())
        assertTrue(read.isEmpty)
    }

    @Test
    fun `the header describes the file before the passphrase is asked for`() {
        val file = write(BackupSamples.payload(withSecrets = true), at = 1_700_000_000_000L)
        val metadata = BackupFile.peek(file)
        assertEquals(BackupFile.FORMAT_VERSION, metadata.formatVersion)
        assertEquals(BackupFile.APP_NAME, metadata.app)
        assertEquals(APP_VERSION, metadata.appVersion)
        assertEquals(1_700_000_000_000L, metadata.createdAtEpochMs)
        assertEquals(2, metadata.connectionCount)
        assertEquals(2, metadata.sshKeyCount)
        assertEquals(1, metadata.certificateCount)
        assertEquals(2, metadata.savedQueryCount)
        assertTrue(metadata.containsSecrets)
    }

    @Test
    fun `no secret is readable in the file itself`() {
        val file = String(write(BackupSamples.payload(withSecrets = true)), Charsets.UTF_8)
        assertTrue(!file.contains("hunter2"))
        assertTrue(!file.contains("bastion.example.com"))
        assertTrue(file.startsWith("${BackupFile.MAGIC} v${BackupFile.FORMAT_VERSION}\n"))
    }

    @Test
    fun `two files made with the same passphrase differ`() {
        val payload = BackupSamples.payload()
        assertNotEquals(
            String(write(payload), Charsets.UTF_8),
            String(write(payload), Charsets.UTF_8),
        )
    }

    @Test
    fun `the wrong passphrase is refused and nothing is returned`() {
        val file = write(BackupSamples.payload(withSecrets = true))
        val thrown = runCatching { BackupFile.read(file, "wrong passphrase!".toCharArray()) }
            .exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupUnlockException)
        // The file is untouched: the right passphrase still opens it.
        assertEquals(BackupSamples.payload(withSecrets = true), BackupFile.read(file, passphrase.copyOf()))
    }

    @Test
    fun `a single flipped byte in the payload fails the tag`() {
        val file = write(BackupSamples.payload(withSecrets = true))
        val lines = String(file, Charsets.UTF_8).split('\n').toMutableList()
        val payloadLine = lines[2]
        val bytes = Base64.getDecoder().decode(payloadLine)
        bytes[5] = (bytes[5].toInt() xor 0x01).toByte()
        lines[2] = Base64.getEncoder().encodeToString(bytes)
        val tampered = lines.joinToString("\n").toByteArray(Charsets.UTF_8)

        val thrown = runCatching { BackupFile.read(tampered, passphrase.copyOf()) }.exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupUnlockException)
    }

    @Test
    fun `an edited header fails the tag because it is authenticated`() {
        val file = write(BackupSamples.payload())
        val lines = String(file, Charsets.UTF_8).split('\n').toMutableList()
        lines[1] = lines[1].replace("\"connectionCount\":2", "\"connectionCount\":9")
        val tampered = lines.joinToString("\n").toByteArray(Charsets.UTF_8)

        // peek believes it, which is exactly why nothing is written on peek's word...
        assertEquals(9, BackupFile.peek(tampered).connectionCount)
        // ...and the real read refuses it.
        val thrown = runCatching { BackupFile.read(tampered, passphrase.copyOf()) }.exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupUnlockException)
    }

    @Test
    fun `a truncated payload is refused`() {
        val file = write(BackupSamples.payload(withSecrets = true))
        val text = String(file, Charsets.UTF_8)
        val truncated = text.substring(0, text.length - 40).toByteArray(Charsets.UTF_8)
        val thrown = runCatching { BackupFile.read(truncated, passphrase.copyOf()) }.exceptionOrNull()
        assertTrue(
            "was $thrown",
            thrown is BackupUnlockException || thrown is BackupFormatException,
        )
    }

    @Test
    fun `a file cut off before the payload says so`() {
        val file = write(BackupSamples.payload())
        val header = String(file, Charsets.UTF_8).split('\n').take(2).joinToString("\n")
        val thrown = runCatching { BackupFile.peek(header.toByteArray(Charsets.UTF_8)) }
            .exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupFormatException)
        assertTrue(thrown!!.message!!.contains("truncated"))
    }

    @Test
    fun `a file cut off before the header says so`() {
        val thrown = runCatching {
            BackupFile.peek("${BackupFile.MAGIC} v1\n".toByteArray(Charsets.UTF_8))
        }.exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupFormatException)
    }

    @Test
    fun `a backup from a future version is refused with a sentence`() {
        val file = write(BackupSamples.payload())
        val future = String(file, Charsets.UTF_8)
            .replaceFirst("${BackupFile.MAGIC} v1", "${BackupFile.MAGIC} v2")
            .toByteArray(Charsets.UTF_8)
        val thrown = runCatching { BackupFile.peek(future) }.exceptionOrNull()
        assertTrue("was $thrown", thrown is UnsupportedBackupVersionException)
        thrown as UnsupportedBackupVersionException
        assertEquals(2, thrown.fileVersion)
        assertEquals(BackupFile.FORMAT_VERSION, thrown.supportedVersion)
        // And reading it refuses in the same way, rather than reaching the cipher.
        assertTrue(
            runCatching { BackupFile.read(future, passphrase.copyOf()) }
                .exceptionOrNull() is UnsupportedBackupVersionException,
        )
    }

    @Test
    fun `a file that is not a backup at all is refused`() {
        val thrown = runCatching {
            BackupFile.peek("hello, world\n".toByteArray(Charsets.UTF_8))
        }.exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupFormatException)
        assertTrue(thrown!!.message!!.contains("not a SQLPulse backup"))
    }

    @Test
    fun `an empty file is refused`() {
        val thrown = runCatching { BackupFile.peek(ByteArray(0)) }.exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupFormatException)
    }

    @Test
    fun `a payload that is not base64 is refused`() {
        val file = write(BackupSamples.payload())
        val lines = String(file, Charsets.UTF_8).split('\n').toMutableList()
        lines[2] = "!!!! not base64 !!!!"
        val thrown = runCatching {
            BackupFile.read(lines.joinToString("\n").toByteArray(Charsets.UTF_8), passphrase.copyOf())
        }.exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupFormatException)
    }

    @Test
    fun `a file whose magic and header disagree about the version is refused`() {
        val file = write(BackupSamples.payload())
        val doctored = String(file, Charsets.UTF_8)
            .replaceFirst("\"formatVersion\":1", "\"formatVersion\":0")
            .toByteArray(Charsets.UTF_8)
        val thrown = runCatching { BackupFile.peek(doctored) }.exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupFormatException)
    }

    @Test
    fun `the suggested file name carries the date and the extension`() {
        val name = BackupFile.suggestedFileName(1_758_000_000_000L)
        assertTrue(name.endsWith(".${BackupFile.FILE_EXTENSION}"))
        assertTrue(name.startsWith("sqlpulse-2025-09-16"))
        assertNull(name.firstOrNull { it == ':' })
    }

    private companion object {
        const val APP_VERSION = "0.1.42+abc1234"
    }
}
