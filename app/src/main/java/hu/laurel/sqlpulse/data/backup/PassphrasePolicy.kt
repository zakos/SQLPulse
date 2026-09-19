package hu.laurel.sqlpulse.data.backup

/**
 * The rules the export screen holds the passphrase to.
 *
 * The passphrase is the only thing standing between a stolen backup file and every password and
 * private key in it, and unlike the Keystore it has no hardware behind it and no attempt counter:
 * a thief with the file can guess offline as fast as their hardware allows. Hence a floor on the
 * length rather than a "must contain a digit" ritual — length is what actually costs a guesser.
 *
 * The passphrase is never stored: it lives in a CharArray for the length of one export or import
 * and is wiped afterwards.
 */
object PassphrasePolicy {

    /** Short enough to type on a phone, long enough that 210 000 PBKDF2 rounds mean something. */
    const val MIN_LENGTH = 12

    enum class Problem {
        TOO_SHORT,
        CONFIRMATION_DIFFERS,
    }

    /** @return the first thing wrong with the pair, or null when it may be used. */
    fun check(passphrase: CharArray, confirmation: CharArray): Problem? = when {
        passphrase.size < MIN_LENGTH -> Problem.TOO_SHORT
        !passphrase.contentEquals(confirmation) -> Problem.CONFIRMATION_DIFFERS
        else -> null
    }

    fun check(passphrase: String, confirmation: String): Problem? =
        check(passphrase.toCharArray(), confirmation.toCharArray())
}
