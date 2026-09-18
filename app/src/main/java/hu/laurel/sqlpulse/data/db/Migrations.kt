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

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
