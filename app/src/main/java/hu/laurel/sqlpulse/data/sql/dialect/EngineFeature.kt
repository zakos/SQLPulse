package hu.laurel.sqlpulse.data.sql.dialect

/**
 * The parts of the app that are not the same everywhere: a screen, or a capability a screen
 * offers, that only some engines can back today.
 *
 * Everything not listed here — connecting, the schema browser's table list, a table's data and
 * structure, the SQL editor, the result grid, export — is the baseline every engine has to deliver
 * before it is [SqlDialect.connectable] at all. A dialect lists what it supports in
 * [SqlDialect.features]; the UI hides an entry point or shows "not available for this engine"
 * (EngineGate) for anything missing, and never calls into a repository the engine cannot answer.
 */
enum class EngineFeature {
    /** Server screen: running statements and cancelling them from another session (KILL). */
    SERVER_ACTIVITY,

    /** Replication card on the Server screen. */
    REPLICATION,

    /** "Slow" panel: statement digests (performance_schema in MySQL). */
    SLOW_QUERIES,

    /** Pulse: live server counters. */
    PULSE,

    /** Storage screen: table and index sizes, AUTO_INCREMENT headroom, unused indexes. */
    STORAGE,

    /** Schema map (foreign keys drawn, or guessed from names). */
    SCHEMA_MAP,

    /** Schema comparison from the stored schema. */
    SCHEMA_DIFF,

    /** Search across every table of a database. */
    DATABASE_SEARCH,

    /** EXPLAIN as a plan tree. */
    EXPLAIN,

    /** Editing, inserting and deleting rows from a table's Data tab (§7.6). */
    ROW_EDITING,

    /** Editing the result of a typed SELECT in place. */
    EDITABLE_RESULTS,

    /** Row count and changed-rows preview in front of a typed UPDATE/DELETE. */
    WRITE_PREVIEW,

    /** CSV import into a table. */
    CSV_IMPORT,

    /** Stored procedures and functions in the schema browser. */
    ROUTINES,

    /** Triggers in the schema browser. */
    TRIGGERS,

    /** Scheduled events in the schema browser (MySQL only). */
    EVENTS,

    /** The DDL tab of a table (`SHOW CREATE TABLE` or the engine's equivalent). */
    TABLE_DDL,

    /** Walking foreign keys from a row to its parent and children. */
    ROW_LINKS,
    ;

    companion object {
        /** What MySQL has, which is everything: the app was built for it. */
        val ALL: Set<EngineFeature> = entries.toSet()
    }
}
