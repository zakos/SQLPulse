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
     * SQLite cannot relax a column's NOT NULL, so the table is rebuilt and copied. Existing
     * connections were all tunnelled, hence the flag defaults to 1 for them.
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

    val ALL = arrayOf(MIGRATION_1_2)
}
