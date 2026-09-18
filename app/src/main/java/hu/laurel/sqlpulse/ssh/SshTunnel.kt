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

/** Everything the tunnel needs; deliberately free of persistence types. */
data class TunnelConfig(
    val sshHost: String,
    val sshPort: Int,
    val sshUser: String,
    val dbHost: String,
    val dbPort: Int,
    val connectTimeoutMs: Int = 10_000,
)

/**
 * One SSH connection carrying one local port forward (§4). MySQL traffic never leaves this path:
 * the JDBC client will talk to 127.0.0.1 on an ephemeral port, and sshj forwards it through the
 * SSH host to the database.
 */
class SshTunnel(
    private val config: TunnelConfig,
    private val keyPair: KeyPair,
    private val verifier: PinningHostKeyVerifier,
) {

    private var client: SSHClient? = null
    private var serverSocket: ServerSocket? = null
    private var forwarder: LocalPortForwarder? = null
    private var forwarderThread: Thread? = null

    @Volatile
    var localPort: Int = 0
        private set

    val isAlive: Boolean
        get() = client?.isConnected == true && client?.isAuthenticated == true

    /** Connects and authenticates. Blocking — call it on an IO dispatcher. */
    fun connect() {
        // Idempotent, and the one place that must never run against Android's stripped provider.
        CryptoProviders.install()
        val ssh = SSHClient(AndroidConfig())
        ssh.addHostKeyVerifier(verifier)
        ssh.connectTimeout = config.connectTimeoutMs
        ssh.timeout = config.connectTimeoutMs
        client = ssh
        ssh.connect(config.sshHost, config.sshPort)
        ssh.authPublickey(config.sshUser, KeyPairProvider(keyPair))
        // Cheap liveness signal: a dead mobile link is noticed without waiting for a query.
        ssh.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
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
        runCatching { client?.disconnect() }
        forwarderThread?.interrupt()
        forwarder = null
        serverSocket = null
        client = null
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
