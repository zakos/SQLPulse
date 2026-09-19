package hu.laurel.sqlpulse.data.db

/**
 * The SQL every schema migration runs, as data.
 *
 * [Migrations] holds the Room [androidx.room.migration.Migration] objects; each of them does
 * nothing but execute the matching list from here, in order. The split exists so the statements
 * can be tested: this file imports nothing from Room or Android, so a plain JVM unit test can
 * apply the very same strings to a real SQLite engine (see
 * `app/src/test/java/hu/laurel/sqlpulse/data/db/MigrationSqlTest.kt`). There is one copy of the
 * SQL — the test does not restate it, so it cannot drift from what ships.
 *
 * Nothing here may gain a Room or Android import. That would take the file out of the unit tests'
 * reach and the migrations would go untested again.
 */
object MigrationStatements {

    /**
     * v2: the SSH tunnel becomes optional, so `sshKeyId` has to allow NULL and a `useSshTunnel`
     * flag is added.
     *
     * SQLite cannot relax a column's NOT NULL, so the table is rebuilt and copied. The columns are
     * still named `bastionHost`/`bastionPort` here — this migration has to match the schema as it
     * was at version 2, whatever later versions renamed.
     */
    val MIGRATION_1_2: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `connection_new` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `color` TEXT NOT NULL,
            `useSshTunnel` INTEGER NOT NULL,
            `bastionHost` TEXT NOT NULL,
            `bastionPort` INTEGER NOT NULL,
            `sshUser` TEXT NOT NULL,
            `sshKeyId` INTEGER,
            `dbHost` TEXT NOT NULL,
            `dbPort` INTEGER NOT NULL,
            `database` TEXT NOT NULL,
            `dbUser` TEXT NOT NULL,
            `readOnly` INTEGER NOT NULL,
            `lastUsedAt` INTEGER,
            FOREIGN KEY(`sshKeyId`) REFERENCES `ssh_key`(`id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
        """
        INSERT INTO `connection_new` (
            id, name, color, useSshTunnel, bastionHost, bastionPort, sshUser, sshKeyId,
            dbHost, dbPort, `database`, dbUser, readOnly, lastUsedAt
        )
        SELECT id, name, color, 1, bastionHost, bastionPort, sshUser, sshKeyId,
               dbHost, dbPort, `database`, dbUser, readOnly, lastUsedAt
        FROM `connection`
        """.trimIndent(),
        "DROP TABLE `connection`",
        "ALTER TABLE `connection_new` RENAME TO `connection`",
        "CREATE INDEX IF NOT EXISTS `index_connection_sshKeyId` ON `connection` (`sshKeyId`)",
    )

    /**
     * v3: "bastion" is gone from the app's vocabulary, so the two columns carrying it are renamed
     * to what they always were — the SSH host and port.
     *
     * A rename rather than another rebuild: SQLCipher ships a recent SQLite, so RENAME COLUMN is
     * available regardless of the Android version. (ALTER TABLE ... RENAME COLUMN needs SQLite
     * 3.25; the engine bundled with the platform on the oldest supported API is older than that,
     * which is why the SQLCipher build is the one that matters. The JVM test runs on sqlite-jdbc,
     * whose engine is newer still, so it exercises the statement but cannot prove the floor.)
     */
    val MIGRATION_2_3: List<String> = listOf(
        "ALTER TABLE `connection` RENAME COLUMN `bastionHost` TO `sshHost`",
        "ALTER TABLE `connection` RENAME COLUMN `bastionPort` TO `sshPort`",
    )

    /**
     * v4: TLS for the MySQL connection itself.
     *
     * Existing connections keep DISABLED: they were all tunnelled, where the tunnel is the
     * encryption, and silently turning on verification would break them.
     */
    val MIGRATION_3_4: List<String> = listOf(
        "ALTER TABLE `connection` ADD COLUMN `sslMode` TEXT NOT NULL DEFAULT 'DISABLED'",
        "ALTER TABLE `connection` ADD COLUMN `caCertificate` TEXT",
    )

    /**
     * v5: the SSH host can be entered with a password as well as a key.
     *
     * Existing connections keep KEY, which is what they all were. The password lives in its own
     * table, sealed the same way as the MySQL one.
     */
    val MIGRATION_4_5: List<String> = listOf(
        "ALTER TABLE `connection` ADD COLUMN `sshAuthMethod` TEXT NOT NULL DEFAULT 'KEY'",
        """
        CREATE TABLE IF NOT EXISTS `ssh_credential` (
            `connectionId` INTEGER PRIMARY KEY NOT NULL,
            `sealedPassword` BLOB NOT NULL
        )
        """.trimIndent(),
    )

    /** v6: a connection can reach its SSH host through a first one. */
    val MIGRATION_5_6: List<String> = listOf(
        "ALTER TABLE `connection` ADD COLUMN `sshJumpHost` TEXT",
        "ALTER TABLE `connection` ADD COLUMN `sshJumpPort` INTEGER NOT NULL DEFAULT 22",
        "ALTER TABLE `connection` ADD COLUMN `sshJumpUser` TEXT",
    )

    /**
     * v7: a connection says which environment it belongs to, and carries its own timeouts.
     *
     * Existing rows become UNSET rather than PRODUCTION: the app has no way to know, and a wrong
     * guess either cries wolf on every connection or stays quiet on the one that matters. The
     * timeouts default to what the whole app used until now, so nothing changes until they are
     * edited.
     */
    val MIGRATION_6_7: List<String> = listOf(
        "ALTER TABLE `connection` ADD COLUMN `environment` TEXT NOT NULL DEFAULT 'UNSET'",
        "ALTER TABLE `connection` ADD COLUMN `connectTimeoutSeconds` INTEGER NOT NULL DEFAULT 10",
        "ALTER TABLE `connection` ADD COLUMN `queryTimeoutSeconds` INTEGER NOT NULL DEFAULT 30",
    )

    /**
     * v8: the jump host can be entered with a credential of its own.
     *
     * Existing rows get NULL in `sshJumpAuthMethod` and in `sshJumpKeyId`, and NULL is defined to
     * mean "the first hop uses the same credential as the second" — which is what every two-hop
     * connection saved so far has been doing. Nothing is rewritten and nothing changes behaviour:
     * a connection only splits its credentials once the user fills the new fields in. The two
     * columns therefore must stay nullable and must not gain a DEFAULT.
     *
     * The new password gets its own table rather than a column on `ssh_credential`, whose
     * `sealedPassword` is NOT NULL and would have to be rebuilt to take a second, optional one.
     */
    val MIGRATION_7_8: List<String> = listOf(
        "ALTER TABLE `connection` ADD COLUMN `sshJumpAuthMethod` TEXT",
        "ALTER TABLE `connection` ADD COLUMN `sshJumpKeyId` INTEGER",
        """
        CREATE TABLE IF NOT EXISTS `ssh_jump_credential` (
            `connectionId` INTEGER PRIMARY KEY NOT NULL,
            `sealedPassword` BLOB NOT NULL
        )
        """.trimIndent(),
    )

    /**
     * Every migration in order, keyed by the version it leaves the database at: index 0 is 1→2.
     * The test walks this list, so a ninth migration added to [Migrations] without being added
     * here goes untested — and a migration added here without being wired into [Migrations] does
     * nothing at all. Keep the two in step.
     */
    val IN_ORDER: List<Pair<Int, List<String>>> = listOf(
        1 to MIGRATION_1_2,
        2 to MIGRATION_2_3,
        3 to MIGRATION_3_4,
        4 to MIGRATION_4_5,
        5 to MIGRATION_5_6,
        6 to MIGRATION_6_7,
        7 to MIGRATION_7_8,
    )
}
