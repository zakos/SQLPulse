package hu.laurel.sqlpulse.data.connection

import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.sql.SslMode

/**
 * The saved row as a header. Kept apart from [SessionHeader] itself so that the model and its
 * rules stay free of Room, and can be unit tested without a device.
 */
fun ConnectionEntity.toSessionHeader(currentDatabase: String? = null): SessionHeader = sessionHeader(
    connectionName = name,
    tunnelled = useSshTunnel,
    sshUser = sshUser,
    sshHost = sshHost,
    dbHost = dbHost,
    dbPort = dbPort,
    database = database,
    currentDatabase = currentDatabase,
    dbUser = dbUser,
    environment = ConnectionEnvironment.fromName(environment),
    readOnly = readOnly,
)

/** The shape [ProductionPolicy] judges, taken off a saved row. */
fun ConnectionEntity.productionShape(): ProductionShape = ProductionShape(
    environment = ConnectionEnvironment.fromName(environment),
    tunnelled = useSshTunnel,
    sslMode = SslMode.fromName(sslMode),
    readOnly = readOnly,
    queryTimeoutSeconds = queryTimeoutSeconds,
)
