package hu.laurel.sqlpulse.ssh

import hu.laurel.sqlpulse.data.db.KnownHostDao
import hu.laurel.sqlpulse.data.db.KnownHostEntity
import hu.laurel.sqlpulse.data.keys.SshKeyParser
import java.security.PublicKey
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * Trust on first use, then pin (§5).
 *
 * First contact asks the user, showing the SHA256 fingerprint, and stores what they accept. If a
 * pinned host later offers a different key the connection is **blocked** — there is no "accept
 * anyway" here; unblocking happens explicitly in the connection editor.
 *
 * sshj calls [verify] on its own transport thread, so blocking on the user's decision is fine.
 */
class PinningHostKeyVerifier(
    private val knownHosts: KnownHostDao,
    private val askUser: suspend (HostKeyPrompt) -> Boolean,
) : HostKeyVerifier {

    /** Set when a pinned key did not match, so the caller can explain exactly what changed. */
    @Volatile
    var blocked: HostKeyPrompt? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = runBlocking {
        val offered = SshKeyParser.fingerprintOf(key)
        val keyType = KeyType.fromKey(key).toString()
        val known = knownHosts.find(hostname, port)

        when {
            known == null -> {
                val accepted = askUser(
                    HostKeyPrompt(
                        host = hostname,
                        port = port,
                        keyType = keyType,
                        offeredFingerprint = offered,
                        storedFingerprint = null,
                    ),
                )
                if (accepted) {
                    knownHosts.upsert(
                        KnownHostEntity(
                            host = hostname,
                            port = port,
                            keyType = keyType,
                            fingerprint = offered,
                            acceptedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                accepted
            }

            known.fingerprint == offered -> true

            else -> {
                blocked = HostKeyPrompt(
                    host = hostname,
                    port = port,
                    keyType = keyType,
                    offeredFingerprint = offered,
                    storedFingerprint = known.fingerprint,
                )
                false
            }
        }
    }

    /**
     * Telling sshj which algorithm we already trust makes the server offer that same host key
     * type, instead of a different one that would look like a mismatch.
     */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = runBlocking {
        knownHosts.find(hostname, port)?.let { listOf(it.keyType) } ?: emptyList()
    }
}
