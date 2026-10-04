package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.PulseProfile
import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ServerFact
import hu.laurel.sqlpulse.data.schema.ServerSample
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.sql.ResultTable
import java.sql.Connection

/** How a running statement or session can be stopped from the Server screen. */
enum class KillAction {
    /** Ends the current statement and leaves the session (MySQL `KILL QUERY`, `pg_cancel_backend`). */
    CANCEL,

    /** Ends the whole session, rolling its transaction back (`pg_terminate_backend`, T-SQL `KILL`). */
    TERMINATE,
}

/**
 * What the Server and Pulse screens may offer for an engine, beyond which panels exist (those are
 * [EngineFeature]s). Defaults are MySQL's, so a fixed state in a screenshot test needs no setup.
 */
data class ServerCapabilities(
    val killActions: Set<KillAction> = setOf(KillAction.CANCEL),
    /** The process list can hide idle sessions, and starts with them hidden (PostgreSQL, SQL Server). */
    val idleFilter: Boolean = false,
    /** Transactions are drawn as cards with the state highlighted rather than as a grid. */
    val transactionCards: Boolean = false,
) {
    /** The gentle action when there is one: what a tap on "Kill" does. */
    val primaryKill: KillAction
        get() = if (KillAction.CANCEL in killActions) KillAction.CANCEL else KillAction.TERMINATE

    companion object {
        val MYSQL = ServerCapabilities()
    }
}

/**
 * How one engine answers the DBA role's questions (§3): the running statements, transactions,
 * lock waits, replication, slow statements, accounts, and the counters behind Pulse.
 *
 * One implementation per engine, reached through [SqlDialect.server]; ServerRepository runs them
 * on a pooled connection. Every method is read-only except [stop], and none of them manages
 * accounts (§2). The tables keep the column shape the screens read — `Id`, `User`, `Host`, `db`,
 * `Time` (seconds), `State`, `Info` for the process list — so the UI never asks which engine it
 * is drawing, only what [capabilities] says.
 *
 * "No permission" is an answer, not a failure: where a panel needs a privilege the account may
 * lack (VIEW SERVER STATE, pg_read_all_stats, PROCESS), the report types carry a NoPrivilege
 * state; the table-returning methods simply return what the server shows this account.
 */
interface ServerCatalog {

    val capabilities: ServerCapabilities

    /** Which counters Pulse draws for this engine and how they are computed from two samples. */
    val pulse: PulseProfile

    /**
     * Running statements. [includeIdle] only matters where [ServerCapabilities.idleFilter] is set.
     * This connection's own session is never in the list.
     */
    fun processList(connection: Connection, includeIdle: Boolean, limit: Int): ResultTable

    /**
     * Stops [id] and returns whether the server found it. Refuses this connection's own session
     * (throwing [OwnSessionException]): ending it would sever the screen's own connection.
     */
    fun stop(connection: Connection, id: Long, action: KillAction): Boolean

    /** Open transactions, oldest first. */
    fun transactions(connection: Connection, limit: Int): ResultTable

    /** Who waits for whom, with the session ids in columns whose label ends in `Id`. */
    fun lockWaits(connection: Connection, limit: Int): ResultTable

    fun replication(connection: Connection): ReplicationReport

    fun slowStatements(connection: Connection, sort: SlowSort): SlowStatementsReport

    /** The accounts, first column being the account name (a tap on a row asks for [grants]). */
    fun users(connection: Connection, limit: Int): ResultTable

    /** What [account] may do, as readable lines. */
    fun grants(connection: Connection, account: String): List<String>

    /** The short card at the top: version, uptime, connections, encryption of this session. */
    fun overview(connection: Connection): List<ServerFact>

    /** One reading of the counters [pulse] needs. */
    fun sample(connection: Connection): ServerSample
}

/** A kill aimed at the session that is running it. */
class OwnSessionException(val id: Long) :
    IllegalArgumentException("session $id is this app's own connection")

/**
 * A panel needs a permission the account lacks (SQL Server's VIEW SERVER STATE). The screen shows
 * [privilege] by name rather than the driver's message, so the fix is obvious to whoever can grant it.
 */
class MissingPrivilegeException(val privilege: String, cause: Throwable? = null) :
    Exception("missing privilege: $privilege", cause)

/** SQLite: a file, not a server; the screens are hidden by [EngineFeature] and nothing calls this. */
object NoServerCatalog : ServerCatalog {
    private fun refuse(): Nothing = throw EngineNotSupportedException(DatabaseEngine.SQLITE, "server screens")

    override val capabilities = ServerCapabilities()
    override val pulse: PulseProfile get() = refuse()
    override fun processList(connection: Connection, includeIdle: Boolean, limit: Int) = refuse()
    override fun stop(connection: Connection, id: Long, action: KillAction) = refuse()
    override fun transactions(connection: Connection, limit: Int) = refuse()
    override fun lockWaits(connection: Connection, limit: Int) = refuse()
    override fun replication(connection: Connection) = refuse()
    override fun slowStatements(connection: Connection, sort: SlowSort) = refuse()
    override fun users(connection: Connection, limit: Int) = refuse()
    override fun grants(connection: Connection, account: String) = refuse()
    override fun overview(connection: Connection) = refuse()
    override fun sample(connection: Connection) = refuse()
}
