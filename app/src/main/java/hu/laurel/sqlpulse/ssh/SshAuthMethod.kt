package hu.laurel.sqlpulse.ssh

/**
 * How the SSH host is convinced who we are.
 *
 * A key is the better answer and stays the default: it cannot be guessed, and it never leaves the
 * keystore in a form the app could hand out. Password authentication exists because plenty of
 * hosts are set up that way and the alternative — not being able to connect at all — helps nobody.
 */
enum class SshAuthMethod {
    KEY,
    PASSWORD,
    ;

    companion object {
        fun fromName(name: String?): SshAuthMethod = entries.firstOrNull { it.name == name } ?: KEY
    }
}
