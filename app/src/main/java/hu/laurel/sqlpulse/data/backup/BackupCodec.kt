package hu.laurel.sqlpulse.data.backup

/**
 * Payload <-> JSON.
 *
 * Every field is written out by name. Reading tolerates a missing optional field (an older writer
 * that did not have it) but refuses a missing required one, so a half-written file is a refusal
 * rather than a connection with an empty host.
 */
object BackupCodec {

    fun encode(payload: BackupPayload): String = JsonValue.Obj(
        linkedMapOf(
            "sshKeys" to JsonValue.Arr(payload.sshKeys.map(::encodeKey)),
            "certificates" to JsonValue.Arr(payload.certificates.map(::encodeCertificate)),
            "connections" to JsonValue.Arr(payload.connections.map(::encodeConnection)),
        ),
    ).write()

    fun decode(text: String): BackupPayload {
        val root = try {
            JsonValue.parse(text).asObject("payload")
        } catch (e: JsonException) {
            throw BackupFormatException("the backup payload is not readable: ${e.message}")
        }
        return try {
            BackupPayload(
                sshKeys = root.arr("sshKeys").map { decodeKey(it.asObject("sshKey")) },
                certificates = root.arr("certificates")
                    .map { decodeCertificate(it.asObject("certificate")) },
                connections = root.arr("connections")
                    .map { decodeConnection(it.asObject("connection")) },
            )
        } catch (e: JsonException) {
            throw BackupFormatException("the backup payload is incomplete: ${e.message}")
        }
    }

    private fun encodeKey(key: BackupSshKey): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(key.name),
            "algorithm" to JsonValue.Str(key.algorithm),
            "bits" to JsonValue.Num(key.bits),
            "fingerprint" to JsonValue.Str(key.fingerprint),
            "publicKey" to JsonValue.Str(key.publicKey),
            "createdAt" to JsonValue.Num(key.createdAt),
            "materialFormat" to JsonValue.Str(key.materialFormat),
            "privateKey" to key.privateKeyBase64.orJsonNull(),
        ),
    )

    private fun decodeKey(obj: JsonValue.Obj) = BackupSshKey(
        name = obj.str("name"),
        algorithm = obj.str("algorithm"),
        bits = obj.int("bits"),
        fingerprint = obj.str("fingerprint"),
        publicKey = obj.str("publicKey"),
        createdAt = obj.long("createdAt"),
        materialFormat = obj.str("materialFormat"),
        privateKeyBase64 = obj.strOrNull("privateKey"),
    )

    private fun encodeCertificate(certificate: BackupCertificate): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(certificate.name),
            "pem" to JsonValue.Str(certificate.pem),
        ),
    )

    private fun decodeCertificate(obj: JsonValue.Obj) =
        BackupCertificate(name = obj.str("name"), pem = obj.str("pem"))

    private fun encodeQuery(query: BackupSavedQuery): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(query.name),
            "sql" to JsonValue.Str(query.sql),
            "parameters" to JsonValue.Str(query.parameters),
        ),
    )

    private fun decodeQuery(obj: JsonValue.Obj) = BackupSavedQuery(
        name = obj.str("name"),
        sql = obj.str("sql"),
        parameters = obj.strOrNull("parameters").orEmpty(),
    )

    private fun encodeConnection(connection: BackupConnection): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(connection.name),
            "color" to JsonValue.Str(connection.color),
            "useSshTunnel" to JsonValue.Bool(connection.useSshTunnel),
            "sshHost" to JsonValue.Str(connection.sshHost),
            "sshPort" to JsonValue.Num(connection.sshPort),
            "sshUser" to JsonValue.Str(connection.sshUser),
            "sshAuthMethod" to JsonValue.Str(connection.sshAuthMethod),
            "sshKeyFingerprint" to connection.sshKeyFingerprint.orJsonNull(),
            "sshJumpHost" to connection.sshJumpHost.orJsonNull(),
            "sshJumpPort" to JsonValue.Num(connection.sshJumpPort),
            "sshJumpUser" to connection.sshJumpUser.orJsonNull(),
            "sshJumpAuthMethod" to connection.sshJumpAuthMethod.orJsonNull(),
            "sshJumpKeyFingerprint" to connection.sshJumpKeyFingerprint.orJsonNull(),
            "dbHost" to JsonValue.Str(connection.dbHost),
            "dbPort" to JsonValue.Num(connection.dbPort),
            "database" to JsonValue.Str(connection.database),
            "dbUser" to JsonValue.Str(connection.dbUser),
            "readOnly" to JsonValue.Bool(connection.readOnly),
            "environment" to JsonValue.Str(connection.environment),
            "connectTimeoutSeconds" to JsonValue.Num(connection.connectTimeoutSeconds),
            "queryTimeoutSeconds" to JsonValue.Num(connection.queryTimeoutSeconds),
            "sslMode" to JsonValue.Str(connection.sslMode),
            "caCertificate" to connection.caCertificate.orJsonNull(),
            "savedQueries" to JsonValue.Arr(connection.savedQueries.map(::encodeQuery)),
            "dbPassword" to connection.dbPassword.orJsonNull(),
            "sshPassword" to connection.sshPassword.orJsonNull(),
            "jumpSshPassword" to connection.jumpSshPassword.orJsonNull(),
        ),
    )

    private fun decodeConnection(obj: JsonValue.Obj) = BackupConnection(
        name = obj.str("name"),
        color = obj.strOrNull("color").orEmpty(),
        useSshTunnel = obj.boolOr("useSshTunnel", true),
        sshHost = obj.strOrNull("sshHost").orEmpty(),
        sshPort = obj.intOr("sshPort", DEFAULT_SSH_PORT),
        sshUser = obj.strOrNull("sshUser").orEmpty(),
        sshAuthMethod = obj.strOrNull("sshAuthMethod") ?: "KEY",
        sshKeyFingerprint = obj.strOrNull("sshKeyFingerprint"),
        sshJumpHost = obj.strOrNull("sshJumpHost"),
        sshJumpPort = obj.intOr("sshJumpPort", DEFAULT_SSH_PORT),
        sshJumpUser = obj.strOrNull("sshJumpUser"),
        sshJumpAuthMethod = obj.strOrNull("sshJumpAuthMethod"),
        sshJumpKeyFingerprint = obj.strOrNull("sshJumpKeyFingerprint"),
        dbHost = obj.str("dbHost"),
        dbPort = obj.intOr("dbPort", DEFAULT_DB_PORT),
        database = obj.strOrNull("database").orEmpty(),
        dbUser = obj.strOrNull("dbUser").orEmpty(),
        readOnly = obj.boolOr("readOnly", true),
        environment = obj.strOrNull("environment") ?: "UNSET",
        connectTimeoutSeconds = obj.intOr("connectTimeoutSeconds", DEFAULT_CONNECT_TIMEOUT),
        queryTimeoutSeconds = obj.intOr("queryTimeoutSeconds", DEFAULT_QUERY_TIMEOUT),
        sslMode = obj.strOrNull("sslMode") ?: "DISABLED",
        caCertificate = obj.strOrNull("caCertificate"),
        savedQueries = obj.arr("savedQueries").map { decodeQuery(it.asObject("savedQuery")) },
        dbPassword = obj.strOrNull("dbPassword"),
        sshPassword = obj.strOrNull("sshPassword"),
        jumpSshPassword = obj.strOrNull("jumpSshPassword"),
    )

    private fun String?.orJsonNull(): JsonValue =
        if (this == null) JsonValue.Null else JsonValue.Str(this)

    private const val DEFAULT_SSH_PORT = 22
    private const val DEFAULT_DB_PORT = 3306
    private const val DEFAULT_CONNECT_TIMEOUT = 10
    private const val DEFAULT_QUERY_TIMEOUT = 30
}
