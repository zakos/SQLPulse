package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.KillAction
import hu.laurel.sqlpulse.data.sql.dialect.ServerCapabilities
import hu.laurel.sqlpulse.data.sql.dialect.ServerCatalog
import javax.inject.Inject
import javax.inject.Singleton

/** One line of the server overview. */
data class ServerFact(val label: String, val value: String)

/**
 * What the DBA role of §3 asks for: the running queries, and enough server state to see what is
 * going on.
 *
 * Engine-neutral: each method runs the live engine's [ServerCatalog] (reached through the
 * session's dialect) on a pooled connection. For MySQL that is the code that always lived here;
 * PostgreSQL and SQL Server have their own catalogs. The screens read [capabilities] to learn
 * what this engine can do (which kill actions, an idle filter) and the engine's features to learn
 * which panels exist.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val sessions: SqlSessionManager,
) {

    private val catalog: ServerCatalog get() = sessions.dialect().server

    /** What the live engine's Server screen can offer beyond the panels themselves. */
    val capabilities: ServerCapabilities get() = catalog.capabilities

    /** How Pulse turns two samples into tiles, for the live engine. */
    val pulse: PulseProfile get() = catalog.pulse

    /** The running statements; [includeIdle] is honoured only by engines that can filter them. */
    suspend fun processList(includeIdle: Boolean = false): ResultTable {
        val catalog = catalog
        return sessions.withConnection { catalog.processList(it, includeIdle, MAX_PROCESSES) }
    }

    /**
     * Ends one session's statement ([KillAction.CANCEL]) or the whole session ([KillAction.TERMINATE]);
     * returns false when the server did not find it (it may have finished a moment ago).
     */
    suspend fun stop(processId: Long, action: KillAction): Boolean {
        val catalog = catalog
        return sessions.withConnection { catalog.stop(it, processId, action) }
    }

    /** Open transactions, oldest first (research summary, §2.0). */
    suspend fun transactions(): ResultTable {
        val catalog = catalog
        return sessions.withConnection { catalog.transactions(it, MAX_PROCESSES) }
    }

    /** Who is waiting for whom. */
    suspend fun lockWaits(): ResultTable {
        val catalog = catalog
        return sessions.withConnection { catalog.lockWaits(it, MAX_PROCESSES) }
    }

    /** Replication, as this server sees it; see [ReplicationReport] for the distinct non-answers. */
    suspend fun replication(): ReplicationReport {
        val catalog = catalog
        return sessions.withConnection { catalog.replication(it) }
    }

    /** The statements that cost the most time, from the engine's own statistics. */
    suspend fun slowStatements(sort: SlowSort): SlowStatementsReport {
        val catalog = catalog
        return sessions.withConnection { catalog.slowStatements(it, sort) }
    }

    /** The server's accounts, read-only; nothing here can change one. */
    suspend fun users(): ResultTable {
        val catalog = catalog
        return sessions.withConnection { catalog.users(it, MAX_PROCESSES) }
    }

    /** What one account may do, as the server words it. */
    suspend fun grants(account: String): List<String> {
        val catalog = catalog
        return sessions.withConnection { catalog.grants(it, account) }
    }

    /** One reading for the live screen. */
    suspend fun sample(): ServerSample {
        val catalog = catalog
        return sessions.withConnection { catalog.sample(it) }
    }

    /** A handful of facts worth seeing at a glance. */
    suspend fun overview(): List<ServerFact> {
        val catalog = catalog
        val facts = sessions.withConnection { catalog.overview(it) }.toMutableList()
        // Which driver this session settled on. Worth showing: on an old server it explains why
        // TLS verification is unavailable, and it is the first thing to check if something the
        // modern driver does is missing. Only the MySQL family has more than one to choose from.
        if (sessions.dialect().engine == DatabaseEngine.MYSQL) {
            sessions.driverInUse()?.let { facts += ServerFact("JDBC driver", it.name.lowercase()) }
        }
        return facts
    }

    private companion object {
        /** A busy server can have thousands of connections; the screen shows the first page. */
        const val MAX_PROCESSES = 500
    }
}
