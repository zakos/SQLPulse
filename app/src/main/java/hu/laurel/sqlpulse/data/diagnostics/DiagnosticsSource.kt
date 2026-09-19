package hu.laurel.sqlpulse.data.diagnostics

import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.sql.JdbcDriverKind
import hu.laurel.sqlpulse.data.sql.SqlFailure
import hu.laurel.sqlpulse.data.sql.SslMode

/** The build and the device it is running on. Nothing here identifies the person holding it. */
data class AppFacts(
    val versionName: String? = null,
    val versionCode: Long = 0,
    val androidRelease: String? = null,
    val androidSdk: Int = 0,
    val manufacturer: String? = null,
    /** `Build.MODEL`: the model as the manufacturer named it, not the name the user gave it. */
    val model: String? = null,
)

/**
 * A live connection, as the app holds it.
 *
 * The credential fields exist, default to null, and are never read by [Diagnostics.report]. They
 * are here on purpose rather than left out: the type says plainly that the builder is handed the
 * whole connection and chooses nothing, so the one place to check what a report can contain is
 * the builder, and a test can hand it a connection full of secrets and prove the output is clean.
 * The app itself passes nulls — the screen does not unseal anything to build a report.
 */
data class ConnectionFacts(
    val useSshTunnel: Boolean = false,
    val hasJumpHost: Boolean = false,
    /** Name of an `SshAuthMethod` entry: how the SSH host was entered, not what with. */
    val sshAuthMethod: String? = null,
    val sslMode: SslMode? = null,
    val hasCaCertificate: Boolean = false,
    val readOnly: Boolean = true,
    val environment: ConnectionEnvironment? = null,
    val connectTimeoutSeconds: Int = 0,
    val queryTimeoutSeconds: Int = 0,
    val driver: JdbcDriverKind? = null,

    /* Below this line: everything the report must not contain. Read by nothing. */

    val name: String? = null,
    val dbHost: String? = null,
    val dbPort: Int = 0,
    val database: String? = null,
    val dbUser: String? = null,
    val dbPassword: String? = null,
    val sshHost: String? = null,
    val sshUser: String? = null,
    val sshPassword: String? = null,
    val jumpHost: String? = null,
    val sshKeyFingerprint: String? = null,
    val privateKeyPem: String? = null,
    val caCertificatePath: String? = null,
    val caCertificatePem: String? = null,
)

/**
 * The server overview, exactly as the server answered it.
 *
 * Label and value, in the order they came back — including `hostname` and `current_user_name`,
 * which the overview query returns and which [ServerVariable] has no entry for. Passing the whole
 * answer in and letting the allow-list decide is deliberate: the alternative, picking the safe
 * ones at the call site, puts the decision somewhere nobody reviews.
 */
data class ServerFacts(val variables: List<Pair<String, String>> = emptyList())

/**
 * The last failure, classified.
 *
 * [failure] carries the server's own sentence in `serverMessage`; the report takes the kind, the
 * number and the SQLState from it and leaves the sentence behind. [sql] is here for the same
 * reason the passwords above are: to be visibly unused.
 */
data class LastErrorFacts(
    val failure: SqlFailure,
    /**
     * When it happened, as `System.currentTimeMillis`, or null when nobody wrote it down.
     *
     * Only the age is reported, never the moment: a timestamp places the user's working day, and
     * "four minutes ago" is what the bug report needed anyway.
     */
    val atMs: Long? = null,
    val sql: String? = null,
)

/** Everything the app knows at the moment the user asks for a report. */
data class DiagnosticsSource(
    val app: AppFacts = AppFacts(),
    val connection: ConnectionFacts? = null,
    val server: ServerFacts? = null,
    val lastError: LastErrorFacts? = null,
    val nowMs: Long = 0,
)

/**
 * Builds the report.
 *
 * Every line is written here, by naming a [DiagnosticsKey] — there is no loop over the source's
 * fields and no reflection, so a field added to [ConnectionFacts] tomorrow appears in a report
 * only if someone also adds a key for it here. That is the whole of the redaction: not a filter
 * over a finished report, but a builder that cannot name what it must not say.
 */
object Diagnostics {

    fun report(source: DiagnosticsSource): DiagnosticsReport {
        val lines = mutableListOf<DiagnosticsLine>()

        fun put(key: DiagnosticsKey, value: DiagnosticsValue) {
            lines += DiagnosticsLine(key, value)
        }

        with(source.app) {
            put(DiagnosticsField.APP_VERSION, DiagnosticsValue.Token.of(versionName))
            put(DiagnosticsField.APP_BUILD, DiagnosticsValue.Count(versionCode))
            put(DiagnosticsField.ANDROID_RELEASE, DiagnosticsValue.Token.of(androidRelease))
            put(DiagnosticsField.ANDROID_SDK, DiagnosticsValue.Count(androidSdk.toLong()))
            // One token, so a model with a space in it still fits the shape of a value.
            put(
                DiagnosticsField.DEVICE_MODEL,
                DiagnosticsValue.Token.of(
                    listOfNotNull(manufacturer, model)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                ),
            )
        }

        source.connection?.let { connection ->
            put(DiagnosticsField.DRIVER, DiagnosticsValue.Choice.of(connection.driver))
            put(DiagnosticsField.TUNNELLED, DiagnosticsValue.Flag(connection.useSshTunnel))
            put(DiagnosticsField.JUMP_HOST, DiagnosticsValue.Flag(connection.hasJumpHost))
            put(
                DiagnosticsField.SSH_AUTH,
                // A name from a fixed set, but it arrives as stored text, so it is narrowed like
                // any other string rather than trusted because of where it came from.
                if (connection.useSshTunnel) {
                    DiagnosticsValue.Token.of(connection.sshAuthMethod)
                } else {
                    DiagnosticsValue.Absent
                },
            )
            put(DiagnosticsField.TLS_MODE, DiagnosticsValue.Choice.of(connection.sslMode))
            put(
                DiagnosticsField.TLS_CA_CONFIGURED,
                DiagnosticsValue.Flag(connection.hasCaCertificate),
            )
            put(DiagnosticsField.READ_ONLY, DiagnosticsValue.Flag(connection.readOnly))
            put(DiagnosticsField.ENVIRONMENT, DiagnosticsValue.Choice.of(connection.environment))
            put(
                DiagnosticsField.CONNECT_TIMEOUT,
                DiagnosticsValue.Count(connection.connectTimeoutSeconds.toLong()),
            )
            put(
                DiagnosticsField.QUERY_TIMEOUT,
                DiagnosticsValue.Count(connection.queryTimeoutSeconds.toLong()),
            )
        }

        source.server?.variables?.forEach { (label, value) ->
            // No entry in the allow-list means the variable is not reportable. Silently, and
            // without looking at the value: `hostname` is dropped because of its name.
            val variable = ServerVariable.forServerLabel(label) ?: return@forEach
            put(variable, DiagnosticsValue.Token.of(value))
        }

        source.lastError?.let { error ->
            put(DiagnosticsField.ERROR_KIND, DiagnosticsValue.Choice(error.failure.kind.name))
            put(DiagnosticsField.ERROR_CODE, DiagnosticsValue.Count(error.failure.errorCode.toLong()))
            put(DiagnosticsField.ERROR_SQLSTATE, sqlState(error.failure.sqlState))
            put(
                DiagnosticsField.ERROR_AGE,
                error.atMs?.let { DiagnosticsValue.Count(ageSeconds(source.nowMs, it)) }
                    ?: DiagnosticsValue.Absent,
            )
        }

        return DiagnosticsReport(lines)
    }

    /**
     * A SQLState is five characters of the standard's own alphabet. Anything else did not come
     * from the standard and is not worth the risk of printing.
     */
    private fun sqlState(value: String?): DiagnosticsValue {
        val text = value?.trim().orEmpty()
        if (text.length != 5 || !text.all { it.isLetterOrDigit() }) return DiagnosticsValue.Absent
        return DiagnosticsValue.Token.of(text)
    }

    /** Never negative, whatever the clock did between the failure and the report. */
    private fun ageSeconds(nowMs: Long, atMs: Long): Long =
        ((nowMs - atMs) / 1_000L).coerceAtLeast(0L)
}
