package hu.laurel.sqlpulse.ssh

import hu.laurel.sqlpulse.data.crypto.CryptoProviders
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

/** Everything the tunnel needs; deliberately free of persistence types. */
data class TunnelConfig(
    val sshHost: String,
    val sshPort: Int,
    val sshUser: String,
    val dbHost: String,
    val dbPort: Int,
    /**
     * A first SSH host to reach [sshHost] through, for a network where the database's own SSH host
     * is not reachable from outside. Null for the ordinary single-hop case.
     */
    val jumpHost: String? = null,
    val jumpPort: Int = 22,
    val jumpUser: String? = null,
    val connectTimeoutMs: Int = 10_000,
) {
    val usesJumpHost: Boolean get() = !jumpHost.isNullOrBlank() && !jumpUser.isNullOrBlank()
}

/** What we present to the SSH host: a key pair, or a password. */
sealed interface SshCredential {
    data class Key(val keyPair: KeyPair) : SshCredential

    /**
     * The characters are used for this one handshake. They are not copied into a String, because a
     * String would stay in the heap until the garbage collector felt like moving it.
     */
    data class Password(val password: CharArray) : SshCredential {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}

/**
 * One SSH connection carrying one local port forward (§4). MySQL traffic never leaves this path:
 * the JDBC client will talk to 127.0.0.1 on an ephemeral port, and sshj forwards it through the
 * SSH host to the database.
 */
class SshTunnel(
    private val config: TunnelConfig,
    private val credential: SshCredential,
    private val verifier: PinningHostKeyVerifier,
    /**
     * What the first hop is entered with, when it is not the same as the second.
     *
     * Null means shared, which is what every connection did before the jump host could carry its
     * own credential — and what most still do.
     */
    private val jumpCredential: SshCredential? = null,
) {

    private var client: SSHClient? = null

    /** The first hop, when there is one. Closed after the second, in the order they were opened. */
    private var jumpClient: SSHClient? = null
    private var serverSocket: ServerSocket? = null
    private var forwarder: LocalPortForwarder? = null
    private var forwarderThread: Thread? = null

    @Volatile
    var localPort: Int = 0
        private set

    val isAlive: Boolean
        get() = client?.isConnected == true && client?.isAuthenticated == true

    /**
     * Connects and authenticates. Blocking — call it on an IO dispatcher.
     *
     * With a jump host the first hop is dialled and authenticated, and the second is opened
     * *through* it: sshj carries the second SSH connection inside a channel of the first, so the
     * database's SSH host never has to be reachable from the phone. Both hops are verified
     * against the known-hosts store. The first hop uses [jumpCredential] where one is stored — a
     * jump host is often a different machine with its own account — and the second one's otherwise,
     * which is how every connection behaved before that was possible.
     */
    fun connect() {
        // Idempotent, and the one place that must never run against Android's stripped provider.
        CryptoProviders.install()

        val ssh = newClient()
        client = ssh
        if (config.usesJumpHost) {
            val jump = newClient()
            jumpClient = jump
            jump.connect(config.jumpHost, config.jumpPort)
            authenticate(jump, config.jumpUser!!, jumpCredential ?: credential)
            ssh.connectVia(jump.newDirectConnection(config.sshHost, config.sshPort))
        } else {
            ssh.connect(config.sshHost, config.sshPort)
        }
        authenticate(ssh, config.sshUser, credential)

        // Cheap liveness signal: a dead mobile link is noticed without waiting for a query.
        ssh.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
        jumpClient?.connection?.keepAlive?.keepAliveInterval = KEEPALIVE_SECONDS
    }

    private fun newClient(): SSHClient = SSHClient(AndroidConfig()).apply {
        addHostKeyVerifier(verifier)
        connectTimeout = config.connectTimeoutMs
        timeout = config.connectTimeoutMs
    }

    private fun authenticate(ssh: SSHClient, user: String, credential: SshCredential) {
        when (credential) {
            is SshCredential.Key -> ssh.authPublickey(user, KeyPairProvider(credential.keyPair))

            // Both password methods, because a host configured for keyboard-interactive refuses
            // plain "password" even though what it wants is the same password.
            is SshCredential.Password -> {
                val finder = OneAnswerPasswordFinder(credential.password)
                ssh.auth(
                    user,
                    AuthPassword(finder),
                    AuthKeyboardInteractive(PasswordResponseProvider(finder)),
                )
            }
        }
    }

    /** Binds 127.0.0.1 on an ephemeral port and starts forwarding. @return the local port. */
    fun startForwarding(): Int {
        val ssh = checkNotNull(client) { "connect() first" }
        val loopback = InetAddress.getByName(LOOPBACK)
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(loopback, 0))
        }
        serverSocket = socket
        localPort = socket.localPort

        val parameters = Parameters(
            LOOPBACK,
            localPort,
            config.dbHost,
            config.dbPort,
        )
        val local = ssh.newLocalPortForwarder(parameters, socket)
        forwarder = local
        forwarderThread = Thread({
            try {
                local.listen()
            } catch (e: IOException) {
                // Closing the socket is how we stop listening; that is not an error.
            }
        }, "sqlpulse-forwarder").apply {
            isDaemon = true
            start()
        }
        return localPort
    }

    fun close() {
        runCatching { forwarder?.close() }
        runCatching { serverSocket?.close() }
        // The inner connection first: it lives inside a channel of the outer one.
        runCatching { client?.disconnect() }
        runCatching { jumpClient?.disconnect() }
        forwarderThread?.interrupt()
        forwarder = null
        serverSocket = null
        client = null
        jumpClient = null
        forwarderThread = null
        localPort = 0
    }

    /** sshj wants a KeyProvider; ours is already unwrapped in memory for this tunnel only. */
    private class KeyPairProvider(private val pair: KeyPair) : KeyProvider {
        override fun getPrivate(): PrivateKey = pair.private
        override fun getPublic(): PublicKey = pair.public
        override fun getType(): KeyType = KeyType.fromKey(pair.public)
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        private const val KEEPALIVE_SECONDS = 30
    }
}

/**
 * Hands the same password to whichever method asks for it, and never retries.
 *
 * A copy per request, because sshj blanks the array it is given once it is done with it, and the
 * second method would otherwise be handed an empty password. The caller wipes the original.
 */
private class OneAnswerPasswordFinder(private val password: CharArray) : PasswordFinder {
    override fun reqPassword(resource: Resource<*>?): CharArray = password.copyOf()

    // Retrying a rejected password just locks the account out that much faster.
    override fun shouldRetry(resource: Resource<*>?): Boolean = false
}
