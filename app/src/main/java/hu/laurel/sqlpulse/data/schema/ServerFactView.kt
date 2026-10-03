package hu.laurel.sqlpulse.data.schema

/** What a row of the server overview is about, so the screen can name it in the user's language. */
enum class FactKind {
    VERSION, BUILD, EDITION, LEVEL, HOST, USER, DATABASE, ROLE, UPTIME, CONNECTIONS, RUNNING,
    MAX_CONNECTIONS, TLS, READ_ONLY, TIME_ZONE, CHARSET, COLLATION, DRIVER,
}

/**
 * One row of the overview as the screen draws it. [kind] is null for a variable nobody mapped; the
 * raw name then stays as [label]. [flag] carries a yes/no where the value is one (read-only, and
 * "is the connection encrypted" on engines that cannot say with what).
 */
data class PresentedFact(
    val kind: FactKind?,
    val label: String,
    val value: String,
    val flag: Boolean? = null,
)

/**
 * Turns the engines' raw overview rows (server variable names, raw numbers) into what the card
 * shows: known names classified, TLS version and cipher merged into one row, and the facts people
 * look for first (version, host, user, uptime, connections, TLS) put on top. The producers stay
 * dumb on purpose: three dialects, one place that decides how it reads.
 */
object ServerFactView {

    /** Which kind a producer's key means, ignoring case; null for anything unknown. */
    fun kindOf(key: String): FactKind? = when (key.trim().lowercase()) {
        "version" -> FactKind.VERSION
        "build" -> FactKind.BUILD
        "edition" -> FactKind.EDITION
        "level" -> FactKind.LEVEL
        "hostname", "server_name" -> FactKind.HOST
        "current_user", "current_user_name" -> FactKind.USER
        "database" -> FactKind.DATABASE
        "role" -> FactKind.ROLE
        "uptime" -> FactKind.UPTIME
        "connections", "threads_connected" -> FactKind.CONNECTIONS
        "threads_running" -> FactKind.RUNNING
        "max_connections" -> FactKind.MAX_CONNECTIONS
        "ssl_version", "ssl_cipher", "encrypted" -> FactKind.TLS
        "read_only" -> FactKind.READ_ONLY
        "time_zone" -> FactKind.TIME_ZONE
        "charset" -> FactKind.CHARSET
        "collation" -> FactKind.COLLATION
        "jdbc driver" -> FactKind.DRIVER
        else -> null
    }

    private val TOP = listOf(
        FactKind.VERSION, FactKind.HOST, FactKind.USER, FactKind.UPTIME, FactKind.CONNECTIONS, FactKind.TLS,
    )

    fun present(facts: List<ServerFact>): List<PresentedFact> {
        var tlsVersion: String? = null
        var tlsCipher: String? = null
        var encrypted: Boolean? = null
        var sawTls = false
        val rows = mutableListOf<PresentedFact>()
        for (fact in facts) {
            val kind = kindOf(fact.label)
            if (kind == null) {
                rows += PresentedFact(null, fact.label, fact.value)
                continue
            }
            when (fact.label.trim().lowercase()) {
                "ssl_version" -> {
                    sawTls = true
                    tlsVersion = blankToNull(fact.value)
                }
                "ssl_cipher" -> {
                    sawTls = true
                    tlsCipher = blankToNull(fact.value)
                }
                "encrypted" -> {
                    sawTls = true
                    encrypted = fact.value.equals("yes", ignoreCase = true)
                }
                "read_only" -> rows += PresentedFact(
                    kind, fact.label, fact.value,
                    flag = fact.value != "0" && !fact.value.equals("off", ignoreCase = true),
                )
                else -> rows += PresentedFact(kind, fact.label, fact.value)
            }
        }
        if (sawTls) {
            val text = listOfNotNull(tlsVersion, tlsCipher).joinToString(" · ")
            rows += PresentedFact(FactKind.TLS, "TLS", text, flag = text.isNotEmpty() || encrypted == true)
        }
        // Stable sort: the unlisted kinds keep the order the producer gave them.
        return rows.sortedBy { row -> TOP.indexOf(row.kind).let { if (it < 0) TOP.size else it } }
    }

    /** MySQL writes "no cipher" as an empty string, and the card used to turn that into a dash. */
    private fun blankToNull(value: String): String? = value.trim().takeIf { it.isNotEmpty() && it != "—" }

    /** Days, hours, minutes of an uptime: "1 nap 15 óra" reads faster than 142747. */
    fun uptimeParts(seconds: Long): Triple<Long, Long, Long> =
        Triple(seconds / 86_400, (seconds % 86_400) / 3_600, (seconds % 3_600) / 60)
}
