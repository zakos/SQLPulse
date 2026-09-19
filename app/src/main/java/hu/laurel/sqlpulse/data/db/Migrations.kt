package hu.laurel.sqlpulse.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history. Migrations rather than a destructive fallback, because the local database holds
 * the user's connections and their encrypted secrets — dropping it would mean re-importing keys.
 *
 * The SQL itself lives in [MigrationStatements], which has no Room or Android import and can
 * therefore be applied to a real SQLite engine from an ordinary JVM unit test. Each [Migration]
 * below executes exactly one of those lists, in order, and holds no SQL of its own: what the test
 * runs is what the app runs.
 */
object Migrations {

    /** A [Migration] that runs [statements] in order. See [MigrationStatements] for the why. */
    private fun migration(from: Int, to: Int, statements: List<String>): Migration =
        object : Migration(from, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                statements.forEach { db.execSQL(it) }
            }
        }

    /** See [MigrationStatements.MIGRATION_1_2]. */
    val MIGRATION_1_2: Migration = migration(1, 2, MigrationStatements.MIGRATION_1_2)

    /** See [MigrationStatements.MIGRATION_2_3]. */
    val MIGRATION_2_3: Migration = migration(2, 3, MigrationStatements.MIGRATION_2_3)

    /** See [MigrationStatements.MIGRATION_3_4]. */
    val MIGRATION_3_4: Migration = migration(3, 4, MigrationStatements.MIGRATION_3_4)

    /** See [MigrationStatements.MIGRATION_4_5]. */
    val MIGRATION_4_5: Migration = migration(4, 5, MigrationStatements.MIGRATION_4_5)

    /** See [MigrationStatements.MIGRATION_5_6]. */
    val MIGRATION_5_6: Migration = migration(5, 6, MigrationStatements.MIGRATION_5_6)

    /** See [MigrationStatements.MIGRATION_6_7]. */
    val MIGRATION_6_7: Migration = migration(6, 7, MigrationStatements.MIGRATION_6_7)

    /** See [MigrationStatements.MIGRATION_7_8]. */
    val MIGRATION_7_8: Migration = migration(7, 8, MigrationStatements.MIGRATION_7_8)

    /** See [MigrationStatements.MIGRATION_8_9]. */
    val MIGRATION_8_9: Migration = migration(8, 9, MigrationStatements.MIGRATION_8_9)

    val ALL = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        MIGRATION_7_8, MIGRATION_8_9,
    )
}
