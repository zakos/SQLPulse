package hu.laurel.sqlpulse.data.connection

import hu.laurel.sqlpulse.ssh.SshAuthMethod

/**
 * What the first hop of a two-hop tunnel is entered with.
 *
 * Until now there was nothing to decide: both hops used the connection's single credential. A jump
 * host is often a different machine belonging to a different team, with its own key and its own
 * account, so it can now carry its own — and [SameAsSshHost] keeps the old arrangement, which is
 * what every connection saved before this had.
 */
sealed interface JumpCredential {

    /** No credential of its own: the first hop uses whatever the second one uses. */
    data object SameAsSshHost : JumpCredential

    data class Key(val keyId: Long) : JumpCredential

    /** Its own password, sealed in `ssh_jump_credential`. */
    data object Password : JumpCredential
}

/**
 * Reads the two stored columns into one answer.
 *
 * A null auth method means "shared", which is how rows written before the columns existed keep
 * behaving exactly as they did. A method that is set but unusable — KEY without a key id — falls
 * back to shared rather than to no credential at all, because a connection that used to work must
 * not stop working because of a half-written row.
 */
object JumpHostCredentials {

    fun resolve(jumpAuthMethod: String?, jumpKeyId: Long?): JumpCredential = when (jumpAuthMethod) {
        null -> JumpCredential.SameAsSshHost
        SshAuthMethod.KEY.name -> jumpKeyId?.let(JumpCredential::Key) ?: JumpCredential.SameAsSshHost
        SshAuthMethod.PASSWORD.name -> JumpCredential.Password
        // An unknown name can only come from a newer version or a hand-edited row; sharing the
        // credential is the behaviour that was there before, so that is what it falls back to.
        else -> JumpCredential.SameAsSshHost
    }

    /** True when the two hops are entered separately, which is what the editor's switch shows. */
    fun isSeparate(jumpAuthMethod: String?, jumpKeyId: Long?): Boolean =
        resolve(jumpAuthMethod, jumpKeyId) != JumpCredential.SameAsSshHost
}
