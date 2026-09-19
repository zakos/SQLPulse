package hu.laurel.sqlpulse.data.connection

/**
 * The four things a session header has to answer before a statement is typed: which server, which
 * database, as whom, and how live it is.
 *
 * It exists as its own model rather than as four reads off the connection entity because the
 * header is drawn on screens that have no business knowing about Room, and because the database is
 * not always the connection's own — a `USE` moves it, and the header has to follow.
 */
data class SessionHeader(
    val connectionName: String,
    /** `user@ssh-host → db-host:port` with a tunnel, `db-host:port` without one. */
    val server: String,
    val database: String,
    /** The MySQL user, not the SSH one: it is the one the server checks grants against. */
    val user: String,
    val environment: ConnectionEnvironment,
    val readOnly: Boolean,
) {
    /** The one environment worth colouring differently. */
    val isProduction: Boolean get() = environment.isProduction

    /** `db-host:port/database`, the form the confirmation dialog and the cards already use. */
    val target: String get() = if (database.isBlank()) server else "$server/$database"
}

/**
 * Builds the header from plain values, so it can be assembled from a saved row, from a half-filled
 * editor form, or from a live session that has moved to another database.
 *
 * @param currentDatabase what the session is actually on; falls back to the connection's own when
 *   nothing has moved it yet.
 */
fun sessionHeader(
    connectionName: String,
    tunnelled: Boolean,
    sshUser: String,
    sshHost: String,
    dbHost: String,
    dbPort: Int,
    database: String,
    currentDatabase: String? = null,
    dbUser: String,
    environment: ConnectionEnvironment,
    readOnly: Boolean,
): SessionHeader = SessionHeader(
    connectionName = connectionName,
    server = if (tunnelled) "$sshUser@$sshHost → $dbHost:$dbPort" else "$dbHost:$dbPort",
    database = currentDatabase?.takeIf { it.isNotBlank() } ?: database,
    user = dbUser,
    environment = environment,
    readOnly = readOnly,
)
