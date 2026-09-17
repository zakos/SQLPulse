package hu.laurel.sqlpulse.ssh

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Confirms that the far end of the tunnel really is MySQL, without a JDBC driver.
 *
 * MySQL speaks first: right after the TCP connect the server sends an initial handshake packet
 * whose payload starts with the protocol version and a NUL-terminated server version string. That
 * is enough for the "MySQL handshake" step of the connection indicator (§7.1) and for the
 * connection test (§7.2); running a real SELECT VERSION() belongs to the query phase.
 */
object MysqlProbe {

    class ProbeFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** @return the server version string, e.g. "8.0.36-0ubuntu0.22.04.1". */
    fun serverVersion(localPort: Int, timeoutMs: Int = 10_000): String {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(SshTunnel.LOOPBACK, localPort), timeoutMs)
            val input = DataInputStream(socket.getInputStream().buffered())

            // Packet header: 3-byte little-endian payload length, then a 1-byte sequence id.
            val length = input.read().let { b0 ->
                val b1 = input.read()
                val b2 = input.read()
                if (b0 < 0 || b1 < 0 || b2 < 0) throw ProbeFailed("no handshake from the server")
                b0 or (b1 shl 8) or (b2 shl 16)
            }
            if (length !in 1..MAX_HANDSHAKE_BYTES) throw ProbeFailed("unexpected handshake length $length")
            input.read() // sequence id

            val payload = ByteArray(length)
            input.readFully(payload)

            val protocolVersion = payload[0].toInt() and 0xFF
            if (protocolVersion == ERROR_PACKET) {
                // The server answered, but refused us — typically host not allowed to connect.
                throw ProbeFailed(String(payload, 3, payload.size - 3, Charsets.UTF_8).trim())
            }
            if (protocolVersion != PROTOCOL_V10) {
                throw ProbeFailed("the service on the far side does not speak the MySQL protocol")
            }

            val end = payload.indexOfFirst(1) { it == 0.toByte() }
            if (end <= 1) throw ProbeFailed("malformed handshake")
            return String(payload, 1, end - 1, Charsets.UTF_8)
        }
    }

    private inline fun ByteArray.indexOfFirst(from: Int, predicate: (Byte) -> Boolean): Int {
        for (i in from until size) if (predicate(this[i])) return i
        return -1
    }

    private const val PROTOCOL_V10 = 10
    private const val ERROR_PACKET = 0xFF
    private const val MAX_HANDSHAKE_BYTES = 1024
}
