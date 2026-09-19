package hu.laurel.sqlpulse.data.diagnostics

/**
 * The shape of a diagnostics report, and the reason it is shaped this way.
 *
 * A bug report needs facts about the build, the device, the driver, the transport and the failure.
 * It never needs the host, the account, the database name or the statement — and on a phone that
 * talks to somebody's production database, those four are the whole of what must not leave the
 * device. The usual way to get that wrong is to assemble the report freely and then strip the
 * dangerous parts out afterwards: the filter is one edit behind the assembler for ever, and the
 * day somebody adds a field the filter has not heard of, the secret ships.
 *
 * So the report cannot express them in the first place. A line's key is one of two enumerations
 * — [DiagnosticsField] or [ServerVariable] — and neither of them has an entry for a host name, a
 * user name, a password, key material or query text. There is no free-text key and no free-text
 * value: [DiagnosticsValue] admits a flag, a count, the name of an enum constant, and a short
 * sanitised token. To leak a host name somebody would have to add an enum entry for it, in this
 * file, on purpose. That is the whole design.
 *
 * The keys are ASCII and untranslated on purpose, even though the app is Hungarian throughout: a
 * report is pasted into a bug tracker and read next to the source, and `tls.cipher` is what both
 * ends can search for. The screen around it is translated; the payload is not.
 */
sealed interface DiagnosticsKey {

    /** The stable identifier the report is written with. */
    val key: String

    val section: DiagnosticsSection
}

/** The four questions a bug report answers, in the order they are worth reading. */
enum class DiagnosticsSection(val key: String) {
    APP("app"),
    CONNECTION("connection"),
    SERVER("server"),
    LAST_ERROR("error"),
}

/**
 * Everything the report can say about the app, the device and the transport.
 *
 * Note what is absent and cannot be added by accident: the connection's name (the user picked it,
 * and people name connections after their customers), the database host, the database name, the
 * account, and anything about the SSH host beyond whether there was one.
 */
enum class DiagnosticsField(
    override val key: String,
    override val section: DiagnosticsSection,
) : DiagnosticsKey {

    APP_VERSION("app.version", DiagnosticsSection.APP),
    APP_BUILD("app.build", DiagnosticsSection.APP),
    ANDROID_RELEASE("android.release", DiagnosticsSection.APP),
    ANDROID_SDK("android.sdk", DiagnosticsSection.APP),

    /** Manufacturer and model. Not the device name, which the user may have made personal. */
    DEVICE_MODEL("device.model", DiagnosticsSection.APP),

    /** Which of the two JDBC drivers the session settled on, by name. */
    DRIVER("connection.driver", DiagnosticsSection.CONNECTION),

    /** Whether the MySQL traffic runs inside an SSH tunnel. */
    TUNNELLED("connection.tunnelled", DiagnosticsSection.CONNECTION),

    /** Whether the tunnel went through an intermediate host. Not which one. */
    JUMP_HOST("connection.jump_host", DiagnosticsSection.CONNECTION),

    /** How the SSH host was entered: by key, by password, keyboard-interactive. */
    SSH_AUTH("connection.ssh_auth", DiagnosticsSection.CONNECTION),

    /** The configured [hu.laurel.sqlpulse.data.sql.SslMode], by name. */
    TLS_MODE("tls.mode", DiagnosticsSection.CONNECTION),

    /** Whether a CA certificate was configured, which is what the verifying modes need. */
    TLS_CA_CONFIGURED("tls.ca_configured", DiagnosticsSection.CONNECTION),

    READ_ONLY("connection.read_only", DiagnosticsSection.CONNECTION),
    ENVIRONMENT("connection.environment", DiagnosticsSection.CONNECTION),
    CONNECT_TIMEOUT("connection.connect_timeout_s", DiagnosticsSection.CONNECTION),
    QUERY_TIMEOUT("connection.query_timeout_s", DiagnosticsSection.CONNECTION),

    /** The failure's classification, which is the part that can be acted on. */
    ERROR_KIND("error.kind", DiagnosticsSection.LAST_ERROR),

    /**
     * The MySQL error number and SQLState.
     *
     * The server's own sentence is not here and cannot be: it quotes the account, the table and
     * often the statement — "Access denied for user 'reports'@'10.0.0.4' to database 'payroll'"
     * is three of the four things this report exists to leave out. The number says the same thing
     * to anyone reading the bug, and says nothing else.
     */
    ERROR_CODE("error.code", DiagnosticsSection.LAST_ERROR),
    ERROR_SQLSTATE("error.sqlstate", DiagnosticsSection.LAST_ERROR),

    /** How long ago it happened, in seconds. Not when, which would place the user in time. */
    ERROR_AGE("error.age_s", DiagnosticsSection.LAST_ERROR),
}

/**
 * The server variables the report may carry, named by what the server calls them.
 *
 * An allow-list, not a filter: the overview query also returns `hostname` and `current_user_name`,
 * and the only way those could reach a report is for someone to add them here, next to this
 * comment saying not to.
 */
enum class ServerVariable(
    override val key: String,
    /** The label the server answers with, matched case-insensitively. */
    val serverLabel: String,
) : DiagnosticsKey {

    VERSION("server.version", "version"),
    BUILD("server.build", "build"),
    VERSION_COMMENT("server.version_comment", "version_comment"),
    MAX_CONNECTIONS("server.max_connections", "max_connections"),
    READ_ONLY("server.read_only", "read_only"),
    TIME_ZONE("server.time_zone", "time_zone"),
    CHARACTER_SET("server.character_set", "charset"),
    UPTIME("server.uptime_s", "Uptime"),
    THREADS_CONNECTED("server.threads_connected", "Threads_connected"),
    THREADS_RUNNING("server.threads_running", "Threads_running"),

    /** Empty when the link is not encrypted at all, which is itself worth reporting. */
    TLS_VERSION("tls.negotiated_version", "Ssl_version"),
    TLS_CIPHER("tls.negotiated_cipher", "Ssl_cipher"),
    ;

    override val section: DiagnosticsSection
        get() = DiagnosticsSection.SERVER

    companion object {
        fun forServerLabel(label: String): ServerVariable? =
            entries.firstOrNull { it.serverLabel.equals(label.trim(), ignoreCase = true) }
    }
}

/**
 * What a line may hold.
 *
 * Four shapes, three of which cannot carry text at all. [Token] is the one that can, and it is
 * deliberately narrow: no control characters, nothing that looks like an address or an account,
 * and short enough that a sentence — a server error, a statement — does not fit through it.
 */
sealed interface DiagnosticsValue {

    data class Flag(val value: Boolean) : DiagnosticsValue

    data class Count(val value: Long) : DiagnosticsValue

    /** The name of an enum constant: a fixed vocabulary, so nothing user-supplied gets in. */
    data class Choice(val name: String) : DiagnosticsValue {
        companion object {
            fun of(value: Enum<*>?): DiagnosticsValue = if (value == null) Absent else Choice(value.name)
        }
    }

    /**
     * A short technical string: a version, a cipher suite, a device model.
     *
     * Built only through [of], which is where the narrowing happens rather than at the call sites.
     */
    class Token private constructor(val value: String) : DiagnosticsValue {

        override fun equals(other: Any?): Boolean = other is Token && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = value

        companion object {
            /** Long enough for `8.0.36-0ubuntu0.22.04.1`, too short for a sentence. */
            private const val MAX_LENGTH = 64

            /**
             * Everything a version string, a cipher suite or a device model is made of. A
             * character outside it is dropped rather than escaped: the point is not to transport
             * the value faithfully, it is to be sure of what is being transported.
             */
            private val ALLOWED = { c: Char ->
                c.isLetterOrDigit() || c in ".-_+/() "
            }

            /**
             * Returns [Absent] for anything that is empty, over-long, or shaped like something
             * that should not be here — `@` is what `user@host` looks like, `:` what `host:port`
             * and a URL look like. No legitimate version or cipher contains either.
             */
            fun of(raw: String?): DiagnosticsValue {
                val text = raw?.trim().orEmpty()
                if (text.isEmpty()) return Absent
                if (text.length > MAX_LENGTH) return Absent
                if (text.any { it == '@' || it == ':' || it == ';' || it.code < 0x20 }) return Absent
                if (!text.all(ALLOWED)) return Absent
                return Token(text)
            }
        }
    }

    /** The app does not know, or the value did not survive [Token.of]. */
    data object Absent : DiagnosticsValue
}

/** One key and its value. The key type is the guarantee: see [DiagnosticsKey]. */
data class DiagnosticsLine(val key: DiagnosticsKey, val value: DiagnosticsValue)

/**
 * A finished report: an ordered list of lines, and the text that gets copied.
 *
 * [asText] is the only rendering, so what the screen shows and what the clipboard gets are the
 * same string. Showing one thing and copying another is how a review of the screen stops proving
 * anything about what actually leaves the device.
 */
data class DiagnosticsReport(val lines: List<DiagnosticsLine>) {

    fun linesIn(section: DiagnosticsSection): List<DiagnosticsLine> =
        lines.filter { it.key.section == section }

    fun asText(): String = buildString {
        append(HEADER).append('\n')
        append(NOTE).append('\n')
        DiagnosticsSection.entries.forEach { section ->
            val sectionLines = linesIn(section)
            if (sectionLines.isEmpty()) return@forEach
            append('\n').append('[').append(section.key).append(']').append('\n')
            sectionLines.forEach { line ->
                append(line.key.key).append(": ").append(render(line.value)).append('\n')
            }
        }
    }

    private fun render(value: DiagnosticsValue): String = when (value) {
        is DiagnosticsValue.Flag -> if (value.value) "yes" else "no"
        is DiagnosticsValue.Count -> value.value.toString()
        is DiagnosticsValue.Choice -> value.name
        is DiagnosticsValue.Token -> value.value
        DiagnosticsValue.Absent -> UNKNOWN
    }

    companion object {
        const val HEADER = "# SQLPulse diagnostics"

        /** Says what is missing, so nobody reading the report asks for it to be filled in. */
        const val NOTE = "# no host names, user names, database names, queries or secrets"

        /** One spelling for "the app does not know", so absence never reads as a value. */
        const val UNKNOWN = "-"
    }
}
