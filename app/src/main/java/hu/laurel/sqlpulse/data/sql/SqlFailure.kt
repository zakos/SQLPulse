package hu.laurel.sqlpulse.data.sql

import java.sql.SQLException

/**
 * What went wrong, at the level where the user can do something about it.
 *
 * The server's own sentence is always kept and shown — this only decides which explanation goes
 * beside it. "Access denied for user" and "SELECT command denied to user" are both denials, but
 * one is fixed by correcting the password and the other by asking for a grant, and the raw text
 * does not make that difference obvious on a phone screen.
 */
enum class SqlFailureKind {
    /** Wrong user or password. */
    AUTHENTICATION,

    /** The user is known, but not allowed to do this. */
    PRIVILEGE,

    /** The named database does not exist, or is not visible to this user. */
    UNKNOWN_DATABASE,

    /** A table, column or other object in the statement does not exist. */
    UNKNOWN_OBJECT,

    /** The statement does not parse. */
    SYNTAX,

    /** TLS could not be established or the certificate was not accepted. */
    TLS,

    /** The server could not be reached at all. */
    UNREACHABLE,

    /** The statement ran too long, or the connection idled out. */
    TIMEOUT,

    /** The connection died mid-statement. */
    CONNECTION_LOST,

    /** Another transaction holds the rows. */
    LOCK,

    /** The write would duplicate a unique key. */
    DUPLICATE_KEY,

    /** The server itself refuses writes, or has no free connections. */
    SERVER_BUSY_OR_READ_ONLY,

    /** Everything else: the server's own message is all there is to say. */
    OTHER,
}

/** A classified server error. [serverMessage] is the server's wording, kept verbatim (§11). */
data class SqlFailure(
    val kind: SqlFailureKind,
    val errorCode: Int,
    val sqlState: String?,
    val serverMessage: String,
)

/**
 * Turns a driver exception into something explainable.
 *
 * MySQL error numbers are the primary signal because they are stable across server versions and
 * languages; SQLState and the message text are only consulted where the number is generic. Nothing
 * here changes what the app does — it decides what it says.
 */
object SqlFailures {

    fun of(e: SQLException): SqlFailure = SqlFailure(
        kind = classify(e.errorCode, e.sqlState, e.message.orEmpty()),
        errorCode = e.errorCode,
        sqlState = e.sqlState,
        serverMessage = e.message.orEmpty(),
    )

    fun classify(errorCode: Int, sqlState: String?, message: String): SqlFailureKind {
        byErrorCode(errorCode)?.let { return it }
        bySqlState(sqlState)?.let { return it }
        return byMessage(message)
    }

    private fun byErrorCode(code: Int): SqlFailureKind? = when (code) {
        1045, 1698, 1251 -> SqlFailureKind.AUTHENTICATION
        // 1044 is "access denied to database": the user is right, the grant is missing.
        1044, 1142, 1143, 1227, 1370, 1211 -> SqlFailureKind.PRIVILEGE
        1049 -> SqlFailureKind.UNKNOWN_DATABASE
        1146, 1054, 1109, 1305 -> SqlFailureKind.UNKNOWN_OBJECT
        1064, 1149 -> SqlFailureKind.SYNTAX
        1205 -> SqlFailureKind.LOCK
        1213 -> SqlFailureKind.LOCK
        1062, 1586 -> SqlFailureKind.DUPLICATE_KEY
        1040, 1203, 1226 -> SqlFailureKind.SERVER_BUSY_OR_READ_ONLY
        1290, 1836 -> SqlFailureKind.SERVER_BUSY_OR_READ_ONLY
        1317, 3024 -> SqlFailureKind.TIMEOUT
        // 2003 cannot connect, 2005 unknown host; 2006/2013 are a connection that died.
        2003, 2005 -> SqlFailureKind.UNREACHABLE
        2006, 2013 -> SqlFailureKind.CONNECTION_LOST
        else -> null
    }

    private fun bySqlState(sqlState: String?): SqlFailureKind? = when (sqlState) {
        null -> null
        "28000" -> SqlFailureKind.AUTHENTICATION
        "42000" -> SqlFailureKind.SYNTAX
        "42S02", "42S22" -> SqlFailureKind.UNKNOWN_OBJECT
        "23000" -> SqlFailureKind.DUPLICATE_KEY
        "40001" -> SqlFailureKind.LOCK
        "08001" -> SqlFailureKind.UNREACHABLE
        "08S01", "08003" -> SqlFailureKind.CONNECTION_LOST
        "HYT00", "HYT01" -> SqlFailureKind.TIMEOUT
        else -> null
    }

    /**
     * The last resort. TLS failures in particular arrive as a generic connection error whose only
     * distinguishing mark is the text, because the handshake fails before MySQL says anything.
     */
    private fun byMessage(message: String): SqlFailureKind {
        val text = message.lowercase()
        return when {
            text.containsAny("ssl", "tls", "certificat", "certpath", "pkix", "trust anchor") ->
                SqlFailureKind.TLS

            text.containsAny("unknownhost", "unknown host", "name or service not known",
                "nodename nor servname", "failed to resolve") -> SqlFailureKind.UNREACHABLE

            text.containsAny("connection refused", "no route to host", "network is unreachable",
                "socket fail to connect", "could not connect") -> SqlFailureKind.UNREACHABLE

            text.containsAny("timed out", "timeout") -> SqlFailureKind.TIMEOUT

            text.containsAny("connection reset", "broken pipe", "connection is closed",
                "unexpected end of stream", "socket closed") -> SqlFailureKind.CONNECTION_LOST

            text.containsAny("access denied") -> SqlFailureKind.AUTHENTICATION

            else -> SqlFailureKind.OTHER
        }
    }

    private fun String.containsAny(vararg needles: String) = needles.any { contains(it) }
}
