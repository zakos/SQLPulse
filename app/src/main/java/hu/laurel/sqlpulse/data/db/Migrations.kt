package hu.laurel.sqlpulse.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history. Migrations rather than a destructive fallback, because the local database holds
 * the user's connections and their encrypted secrets — dropping it would mean re-importing keys.
 */
object Migrations {

    /**
     * v2: the SSH tunnel becomes optional, so `sshKeyId` has to allow NULL and a `useSshTunnel`
     * flag is added.
     *
     * SQLite cannot relax a column's NOT NULL, so the table is rebuilt and copied. The columns are
     * still named `bastionHost`/`bastionPort` here — this migration has to match the schema as it
     * was at version 2, whatever later versions renamed.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
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
            )
            db.execSQL(
                """
                INSERT INTO `connection_new` (
                    id, name, color, useSshTunnel, bastionHost, bastionPort, sshUser, sshKeyId,
                    dbHost, dbPort, `database`, dbUser, readOnly, lastUsedAt
                )
                SELECT id, name, color, 1, bastionHost, bastionPort, sshUser, sshKeyId,
                       dbHost, dbPort, `database`, dbUser, readOnly, lastUsedAt
                FROM `connection`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `connection`")
            db.execSQL("ALTER TABLE `connection_new` RENAME TO `connection`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_sshKeyId` ON `connection` (`sshKeyId`)")
        }
    }

    /**
     * v3: "bastion" is gone from the app's vocabulary, so the two columns carrying it are renamed
     * to what they always were — the SSH host and port.
     *
     * A rename rather than another rebuild: SQLCipher ships a recent SQLite, so RENAME COLUMN is
     * available regardless of the Android version.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `connection` RENAME COLUMN `bastionHost` TO `sshHost`")
            db.execSQL("ALTER TABLE `connection` RENAME COLUMN `bastionPort` TO `sshPort`")
        }
    }

    /**
     * v4: TLS for the MySQL connection itself.
     *
     * Existing connections keep DISABLED: they were all tunnelled, where the tunnel is the
     * encryption, and silently turning on verification would break them.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `connection` ADD COLUMN `sslMode` TEXT NOT NULL DEFAULT 'DISABLED'",
            )
            db.execSQL("ALTER TABLE `connection` ADD COLUMN `caCertificate` TEXT")
        }
    }

    /**
     * v5: the SSH host can be entered with a password as well as a key.
     *
     * Existing connections keep KEY, which is what they all were. The password lives in its own
     * table, sealed the same way as the MySQL one.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `connection` ADD COLUMN `sshAuthMethod` TEXT NOT NULL DEFAULT 'KEY'",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ssh_credential` (
                    `connectionId` INTEGER PRIMARY KEY NOT NULL,
                    `sealedPassword` BLOB NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    /** v6: a connection can reach its SSH host through a first one. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `connection` ADD COLUMN `sshJumpHost` TEXT")
            db.execSQL("ALTER TABLE `connection` ADD COLUMN `sshJumpPort` INTEGER NOT NULL DEFAULT 22")
            db.execSQL("ALTER TABLE `connection` ADD COLUMN `sshJumpUser` TEXT")
        }
    }

    /**
     * v7: a connection says which environment it belongs to, and carries its own timeouts.
     *
     * Existing rows become UNSET rather than PRODUCTION: the app has no way to know, and a wrong
     * guess either cries wolf on every connection or stays quiet on the one that matters. The
     * timeouts default to what the whole app used until now, so nothing changes until they are
     * edited.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `connection` ADD COLUMN `environment` TEXT NOT NULL DEFAULT 'UNSET'",
            )
            db.execSQL(
                "ALTER TABLE `connection` ADD COLUMN `connectTimeoutSeconds` INTEGER NOT NULL DEFAULT 10",
            )
            db.execSQL(
                "ALTER TABLE `connection` ADD COLUMN `queryTimeoutSeconds` INTEGER NOT NULL DEFAULT 30",
            )
        }
    }

    val ALL = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
    )
}
