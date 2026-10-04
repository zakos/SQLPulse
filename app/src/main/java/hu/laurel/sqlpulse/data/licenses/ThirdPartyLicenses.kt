package hu.laurel.sqlpulse.data.licenses

/** Where a bundled component is listed on the Licenses screen. */
enum class LicenseGroup { LIBRARIES, FONTS }

/**
 * One license a component is distributed under. [assets] are text files under
 * `assets/licenses/`, shown in order: a license that comes with a separate exception (GPL-2.0 +
 * Universal FOSS Exception) is two files, so each stays a verbatim canonical text.
 */
data class LicenseInfo(val label: String, val assets: List<String>) {
    constructor(label: String, asset: String) : this(label, listOf(asset))
}

/**
 * A bundled component.
 *
 * [gradleAliases] are the `libs.*` version-catalog aliases (dots, as written in
 * `app/build.gradle.kts`) the entry covers, so a unit test can fail when a runtime dependency has
 * no entry. [versionKey] names its row in `gradle/libs.versions.toml` for the same kind of check on
 * the version string; Kotlin's own standard library has neither.
 */
data class LicensedComponent(
    val name: String,
    val version: String?,
    val group: LicenseGroup,
    val licenses: List<LicenseInfo>,
    val sourceUrl: String,
    /** Copyright holder lines the MIT/BSD-style licenses require us to reproduce. */
    val copyright: List<String> = emptyList(),
    val gradleAliases: List<String> = emptyList(),
    val versionKey: String? = null,
    /** One sentence of context, e.g. why a GPL library may sit in an Apache-2.0 app. */
    val note: String? = null,
) {
    /** Stable id for navigation state and tests. */
    val id: String get() = name
}

/**
 * Mirror of THIRD_PARTY_NOTICES.md: everything the release APK bundles, with its license. Keep the
 * two in step; `ThirdPartyLicensesTest` fails when a dependency, a version or an asset drifts.
 */
object ThirdPartyLicenses {
    const val APP_LICENSE_NAME = "Apache License 2.0"
    const val APP_COPYRIGHT = "© 2026 Zimmermann Ákos"
    const val APP_REPO_URL = "https://github.com/zakos/SQLPulse"
    const val DBX_URL = "https://github.com/t8y2/dbx"

    /** The repo's NOTICE file, bundled verbatim. */
    const val NOTICE_ASSET = "NOTICE.txt"
    const val APP_LICENSE_ASSET = "Apache-2.0.txt"
    const val ASSET_DIR = "licenses"

    private val apache = LicenseInfo("Apache-2.0", "Apache-2.0.txt")
    private val mit = LicenseInfo("MIT", "MIT.txt")
    private val bsd2 = LicenseInfo("BSD-2-Clause", "BSD-2-Clause.txt")
    private val lgpl = LicenseInfo("LGPL-2.1", "LGPL-2.1.txt")
    private val gplFoss = LicenseInfo("GPL-2.0 + FOSS Exception", listOf("GPL-2.0.txt", "Universal-FOSS-Exception-1.0.txt"))
    private val cc0 = LicenseInfo("CC0-1.0", "CC0-1.0.txt")
    private val bouncy = LicenseInfo("Bouncy Castle", "BouncyCastle.txt")
    private val sqlcipher = LicenseInfo("BSD-style (Zetetic)", "SQLCipher.txt")
    private val sqlitePublicDomain = LicenseInfo("SQLite: public domain", "SQLite-PublicDomain.txt")

    private const val ANDROIDX = "https://developer.android.com/jetpack/androidx"

    private fun androidx(name: String, version: String, key: String, vararg aliases: String) = LicensedComponent(
        name = name, version = version, group = LicenseGroup.LIBRARIES, licenses = listOf(apache),
        sourceUrl = ANDROIDX, gradleAliases = aliases.toList(), versionKey = key,
    )

    val components: List<LicensedComponent> = listOf(
        androidx("AndroidX Core", "1.15.0", "coreKtx", "androidx.core.ktx"),
        androidx(
            "AndroidX Lifecycle", "2.8.7", "lifecycle",
            "androidx.lifecycle.runtime.ktx", "androidx.lifecycle.runtime.compose",
            "androidx.lifecycle.viewmodel.compose", "androidx.lifecycle.process",
        ),
        androidx("AndroidX Activity", "1.9.3", "activityCompose", "androidx.activity.compose"),
        androidx(
            "Jetpack Compose (UI, Material 3, icons)", "BOM 2024.12.01", "composeBom",
            "androidx.compose.bom", "androidx.compose.ui", "androidx.compose.ui.graphics",
            "androidx.compose.ui.tooling.preview", "androidx.compose.material3", "androidx.compose.material.icons",
        ),
        androidx("AndroidX Navigation", "2.8.5", "navigation", "androidx.navigation.compose"),
        androidx("AndroidX Room", "2.6.1", "room", "androidx.room.runtime", "androidx.room.ktx"),
        androidx("AndroidX SQLite", "2.4.0", "sqlite", "androidx.sqlite"),
        androidx("AndroidX Biometric", "1.2.0-alpha05", "biometric", "androidx.biometric"),
        androidx("AndroidX DataStore", "1.1.1", "datastore", "androidx.datastore.preferences"),
        androidx("AndroidX Hilt Navigation", "1.2.0", "hiltNavigation", "hilt.navigation.compose"),
        LicensedComponent(
            name = "Dagger Hilt", version = "2.52", group = LicenseGroup.LIBRARIES, licenses = listOf(apache),
            sourceUrl = "https://github.com/google/dagger", gradleAliases = listOf("hilt.android"), versionKey = "hilt",
        ),
        LicensedComponent(
            name = "Kotlin standard library", version = "2.0.21", group = LicenseGroup.LIBRARIES,
            licenses = listOf(apache), sourceUrl = "https://github.com/JetBrains/kotlin", versionKey = "kotlin",
        ),
        LicensedComponent(
            name = "kotlinx.coroutines", version = "1.9.0", group = LicenseGroup.LIBRARIES, licenses = listOf(apache),
            sourceUrl = "https://github.com/Kotlin/kotlinx.coroutines",
            gradleAliases = listOf("kotlinx.coroutines.android"), versionKey = "coroutines",
        ),
        LicensedComponent(
            name = "SQLCipher for Android", version = "4.6.1", group = LicenseGroup.LIBRARIES, licenses = listOf(sqlcipher),
            sourceUrl = "https://github.com/sqlcipher/sqlcipher-android", copyright = listOf("Copyright (c) 2025, ZETETIC LLC"),
            gradleAliases = listOf("sqlcipher.android"), versionKey = "sqlcipher",
        ),
        LicensedComponent(
            name = "sshj", version = "0.38.0", group = LicenseGroup.LIBRARIES, licenses = listOf(apache),
            sourceUrl = "https://github.com/hierynomus/sshj", gradleAliases = listOf("sshj"), versionKey = "sshj",
        ),
        LicensedComponent(
            name = "Bouncy Castle (bcprov, bcpkix)", version = "1.78.1", group = LicenseGroup.LIBRARIES,
            licenses = listOf(bouncy), sourceUrl = "https://github.com/bcgit/bc-java",
            copyright = listOf("Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)"),
            gradleAliases = listOf("bouncycastle.prov", "bouncycastle.pkix"), versionKey = "bouncycastle",
        ),
        LicensedComponent(
            name = "EdDSA-Java", version = "0.3.0", group = LicenseGroup.LIBRARIES, licenses = listOf(cc0),
            sourceUrl = "https://github.com/str4d/ed25519-java", gradleAliases = listOf("eddsa"), versionKey = "eddsa",
        ),
        LicensedComponent(
            name = "SLF4J NOP binding", version = "2.0.16", group = LicenseGroup.LIBRARIES, licenses = listOf(mit),
            sourceUrl = "https://github.com/qos-ch/slf4j", copyright = listOf("Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)"),
            gradleAliases = listOf("slf4j.nop"), versionKey = "slf4j",
        ),
        LicensedComponent(
            name = "MariaDB Connector/J", version = "3.4.1", group = LicenseGroup.LIBRARIES, licenses = listOf(lgpl),
            sourceUrl = "https://github.com/mariadb-corporation/mariadb-connector-j",
            gradleAliases = listOf("mariadb.client"), versionKey = "mariadb",
            note = "Included unmodified as a separate library; its source is available at the link above.",
        ),
        LicensedComponent(
            name = "MySQL Connector/J", version = "5.1.49", group = LicenseGroup.LIBRARIES, licenses = listOf(gplFoss),
            sourceUrl = "https://github.com/mysql/mysql-connector-j",
            copyright = listOf("Copyright (c) 2000, 2020, Oracle and/or its affiliates."),
            gradleAliases = listOf("mysql.legacy.client"), versionKey = "mysql-legacy",
            note = "Used only for servers older than MySQL 5.5.3. Oracle's Universal FOSS Exception permits " +
                "distributing it with this Apache-2.0 application. Source: the 5.1 branch of the repository.",
        ),
        LicensedComponent(
            name = "PostgreSQL JDBC Driver", version = "42.7.13", group = LicenseGroup.LIBRARIES, licenses = listOf(bsd2),
            sourceUrl = "https://github.com/pgjdbc/pgjdbc",
            copyright = listOf(
                "Copyright (c) 1997, PostgreSQL Global Development Group",
                "Bundled SCRAM/SASLprep code: Copyright (c) 2017, 2019 OnGres, Inc.",
            ),
            gradleAliases = listOf("postgresql.client"), versionKey = "postgresql",
        ),
        LicensedComponent(
            name = "Microsoft JDBC Driver for SQL Server", version = "13.6.0.jre8", group = LicenseGroup.LIBRARIES,
            licenses = listOf(mit), sourceUrl = "https://github.com/microsoft/mssql-jdbc",
            copyright = listOf("Copyright (c) Microsoft Corporation"),
            gradleAliases = listOf("mssql.client"), versionKey = "mssql",
        ),
        LicensedComponent(
            name = "SQLite JDBC (with SQLite)", version = "3.53.4.0", group = LicenseGroup.LIBRARIES,
            licenses = listOf(apache, bsd2, sqlitePublicDomain), sourceUrl = "https://github.com/xerial/sqlite-jdbc",
            copyright = listOf("Portions Copyright (c) 2006, David Crawshaw (BSD-2-Clause)"),
            gradleAliases = listOf("sqlite.jdbc"), versionKey = "sqliteJdbc",
            note = "The SQLite library itself (https://sqlite.org) is in the public domain.",
        ),
        LicensedComponent(
            name = "Inter", version = null, group = LicenseGroup.FONTS,
            licenses = listOf(LicenseInfo("OFL-1.1", "Inter-OFL.txt")), sourceUrl = "https://github.com/rsms/inter",
            copyright = listOf("Copyright 2020 The Inter Project Authors"),
        ),
        LicensedComponent(
            name = "JetBrains Mono", version = null, group = LicenseGroup.FONTS,
            licenses = listOf(LicenseInfo("OFL-1.1", "JetBrainsMono-OFL.txt")),
            sourceUrl = "https://github.com/JetBrains/JetBrainsMono",
            copyright = listOf("Copyright 2020 The JetBrains Mono Project Authors"),
        ),
    )

    fun byGroup(group: LicenseGroup): List<LicensedComponent> = components.filter { it.group == group }

    /** Every asset file the screen may ask for; the unit test checks each one exists. */
    val allAssets: Set<String> =
        (components.flatMap { c -> c.licenses.flatMap { it.assets } } + NOTICE_ASSET + APP_LICENSE_ASSET).toSet()
}
