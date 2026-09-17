package hu.laurel.sqlpulse.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SealedTest {

    @Test
    fun `encode then decode round trips`() {
        val original = Sealed(iv = ByteArray(12) { it.toByte() }, ciphertext = "secret".toByteArray())

        val decoded = Sealed.decode(original.encode())

        assertArrayEquals(original.iv, decoded.iv)
        assertArrayEquals(original.ciphertext, decoded.ciphertext)
    }

    @Test
    fun `encoded blob starts with the IV length`() {
        val encoded = Sealed(ByteArray(12), ByteArray(3)).encode()

        assertEquals(12, encoded[0].toInt())
        assertEquals(1 + 12 + 3, encoded.size)
    }

    @Test
    fun `truncated blob is rejected rather than silently misread`() {
        val encoded = Sealed(ByteArray(12), ByteArray(4)).encode()

        assertThrows(IllegalArgumentException::class.java) {
            Sealed.decode(encoded.copyOfRange(0, 8))
        }
    }

    @Test
    fun `empty blob is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Sealed.decode(ByteArray(0)) }
    }

    @Test
    fun `wipe clears the array in place`() {
        val bytes = byteArrayOf(1, 2, 3)

        bytes.wipe()

        assertArrayEquals(ByteArray(3), bytes)
    }
}
