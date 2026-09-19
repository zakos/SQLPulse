package hu.laurel.sqlpulse.data.db

import java.io.File

/**
 * The column list each `@Entity` in `Entities.kt` declares, read out of the source file.
 *
 * The migration test has to compare what the migrations build against what Room will expect, and
 * what Room expects is the entity classes. Those classes cannot be loaded here — they carry Room
 * annotations and the Room artifacts are not on the unit-test classpath — so the declarations are
 * parsed out of the source text instead. Crude, but it is the only reading of the entities that
 * updates itself: add a field to [ConnectionEntity] without migrating for it and the test fails,
 * which is exactly the failure that matters.
 *
 * Kept deliberately simple, and it only has to handle the shapes actually used in `Entities.kt`:
 * a `@Entity(...)` annotation naming `tableName`, followed by a `data class` whose constructor
 * lists one `val name: Type` per column.
 */
object EntitySource {

    data class Property(val name: String, val type: String) {
        val nullable: Boolean get() = type.endsWith("?")

        /** The SQLite affinity Room gives this Kotlin type. Enums go through a converter to TEXT. */
        val affinity: String
            get() = when (type.removeSuffix("?")) {
                "Long", "Int", "Boolean" -> "INTEGER"
                "String", "KeyMaterialFormat", "SshKeyAlgorithm" -> "TEXT"
                "ByteArray" -> "BLOB"
                else -> error("no known SQLite affinity for `$type` — teach EntitySource about it")
            }
    }

    /** tableName -> the properties of the entity that declares it. */
    val tables: Map<String, List<Property>> by lazy { parse(readEntitiesKt()) }

    private fun readEntitiesKt(): String {
        val relative = "app/src/main/java/hu/laurel/sqlpulse/data/db/Entities.kt"
        val bare = relative.removePrefix("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, bare))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        error("Entities.kt not found from ${System.getProperty("user.dir")}")
    }

    private val TABLE_NAME = Regex("""tableName\s*=\s*"([^"]+)"""")
    private val DATA_CLASS = Regex("""^data class \w+\(""")
    // A leading `@PrimaryKey(...)` is allowed before the `val`.
    private val PROPERTY = Regex("""(?:^|\s)val (\w+):\s*([A-Za-z0-9_]+\??)""")

    private fun parse(source: String): Map<String, List<Property>> {
        // Comments would otherwise contribute stray `val` lines and stray `tableName =` text.
        val lines = source.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .toList()

        val tables = LinkedHashMap<String, List<Property>>()
        var pendingTable: String? = null
        var current: MutableList<Property>? = null
        for (line in lines) {
            val currentProperties = current
            if (currentProperties == null) {
                TABLE_NAME.find(line)?.let { pendingTable = it.groupValues[1] }
                if (pendingTable != null && DATA_CLASS.containsMatchIn(line)) current = mutableListOf()
                continue
            }
            if (line.startsWith(")")) {
                tables[pendingTable!!] = currentProperties
                pendingTable = null
                current = null
                continue
            }
            PROPERTY.find(line)?.let { m ->
                currentProperties += Property(m.groupValues[1], m.groupValues[2])
            }
        }
        check(tables.isNotEmpty()) { "no @Entity tables parsed out of Entities.kt" }
        return tables
    }
}
