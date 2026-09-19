package hu.laurel.sqlpulse.data.db

/**
 * The schema as it stood at version 1, written out by hand.
 *
 * `app/schemas` was never committed, so Room's exported JSON for version 1 does not exist and
 * there is nothing to restore it from — the shape below is reconstructed from the migrations
 * themselves (the 1→2 rebuild names every column of `connection` as it was, twice) and from the
 * entities for the tables no migration ever touched. Writing it down is half the point of this
 * test: from here on the historical shape is in the repository, and the first migration is
 * checked against it instead of against nobody's memory.
 *
 * Deliberately in Room's own DDL style — backticked identifiers, `INTEGER PRIMARY KEY
 * AUTOINCREMENT NOT NULL` for a generated id — because that is what a device upgrading from
 * version 1 actually has on disk.
 */
object SchemaV1 {

    val STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `ssh_key` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `algorithm` TEXT NOT NULL,
            `bits` INTEGER NOT NULL,
            `fingerprint` TEXT NOT NULL,
            `materialFormat` TEXT NOT NULL,
            `sealedPrivateKey` BLOB NOT NULL,
            `publicKey` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL
        )
        """.trimIndent(),
        // `sshKeyId` is NOT NULL here: at version 1 the SSH tunnel was not optional and every
        // connection was entered with a key. Relaxing it is the whole reason for the 1→2 rebuild.
        """
        CREATE TABLE IF NOT EXISTS `connection` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `color` TEXT NOT NULL,
            `bastionHost` TEXT NOT NULL,
            `bastionPort` INTEGER NOT NULL,
            `sshUser` TEXT NOT NULL,
            `sshKeyId` INTEGER NOT NULL,
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
        "CREATE INDEX IF NOT EXISTS `index_connection_sshKeyId` ON `connection` (`sshKeyId`)",
        """
        CREATE TABLE IF NOT EXISTS `db_credential` (
            `connectionId` INTEGER PRIMARY KEY NOT NULL,
            `sealedPassword` BLOB NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `known_host` (
            `host` TEXT NOT NULL,
            `port` INTEGER NOT NULL,
            `keyType` TEXT NOT NULL,
            `fingerprint` TEXT NOT NULL,
            `acceptedAt` INTEGER NOT NULL,
            PRIMARY KEY(`host`, `port`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `query_history` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `connectionId` INTEGER NOT NULL,
            `sql` TEXT NOT NULL,
            `executedAt` INTEGER NOT NULL,
            `durationMs` INTEGER NOT NULL,
            `rowCount` INTEGER NOT NULL,
            FOREIGN KEY(`connectionId`) REFERENCES `connection`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_query_history_connectionId` ON `query_history` (`connectionId`)",
        """
        CREATE TABLE IF NOT EXISTS `saved_query` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `connectionId` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `sql` TEXT NOT NULL,
            `parameters` TEXT NOT NULL,
            FOREIGN KEY(`connectionId`) REFERENCES `connection`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )

    const val INSERT_SSH_KEY =
        "INSERT INTO `ssh_key` (id, name, algorithm, bits, fingerprint, materialFormat," +
            " sealedPrivateKey, publicKey, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

    const val INSERT_CONNECTION =
        "INSERT INTO `connection` (id, name, color, bastionHost, bastionPort, sshUser, sshKeyId," +
            " dbHost, dbPort, `database`, dbUser, readOnly, lastUsedAt)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    const val INSERT_DB_CREDENTIAL =
        "INSERT INTO `db_credential` (connectionId, sealedPassword) VALUES (?, ?)"
}
