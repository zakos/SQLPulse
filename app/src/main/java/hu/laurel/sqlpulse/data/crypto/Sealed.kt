package hu.laurel.sqlpulse.data.crypto

/**
 * AES-GCM output: the IV travels with the ciphertext, because a GCM decrypt cipher must be
 * initialised with the IV *before* the user authenticates it (§5, §6).
 */
data class Sealed(val iv: ByteArray, val ciphertext: ByteArray) {

    fun encode(): ByteArray {
        require(iv.size in 1..255) { "unexpected IV length ${iv.size}" }
        val out = ByteArray(1 + iv.size + ciphertext.size)
        out[0] = iv.size.toByte()
        iv.copyInto(out, 1)
        ciphertext.copyInto(out, 1 + iv.size)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is Sealed && iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()

    companion object {
        fun decode(bytes: ByteArray): Sealed {
            require(bytes.isNotEmpty()) { "empty sealed blob" }
            val ivLength = bytes[0].toInt() and 0xFF
            require(bytes.size > 1 + ivLength) { "truncated sealed blob" }
            return Sealed(
                iv = bytes.copyOfRange(1, 1 + ivLength),
                ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size),
            )
        }
    }
}

/** Overwrite secret material as soon as it is no longer needed (§5). */
fun ByteArray.wipe() = fill(0)

fun CharArray.wipe() = fill(Char(0))
