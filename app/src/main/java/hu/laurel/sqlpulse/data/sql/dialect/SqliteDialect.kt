package hu.laurel.sqlpulse.data.sql.dialect

/**
 * A SQLite file on the phone — phase 2 fills this file in (docs/tobb-motor-terv.md,
 * "SQLite ügynök").
 *
 * Driver: xerial sqlite-jdbc, classes from the `without-natives` jar and the Android `.so` files
 * unpacked into jniLibs by the build (UnpackSqliteNatives in app/build.gradle.kts). Instantiated as
 * `org.sqlite.JDBC()`. The JVM unit tests use the same driver, so this engine's integration tests
 * run in the ordinary check run with no server at all.
 *
 * The connection is a file, not a server: TunnelManager never builds a tunnel or probes a port for
 * it (it goes straight to Active), and JdbcConfig.localFile carries the path. The file the user
 * picks through the Storage Access Framework is copied into app-private storage
 * (`filesDir/sqlite/<connectionId>.sqlite`) and opened there; see the design doc for why not in
 * place.
 *
 * What to override, in this file or in SqliteCatalog.kt / SqliteConnector.kt next to it:
 *  - [grammar]: ANSI plus backtick and `[`…`]` quotes (SQLite accepts all three); write starters
 *    + `replace`, `upsert` is INSERT … ON CONFLICT; read starters + `pragma` (only the read-only ones!).
 *  - [limit], [applyDefaultLimit]: `LIMIT n OFFSET m` — SqlGuards.applyDefaultLimit works.
 *  - [nullSafeEquals]: `c IS ?`; [likeEscape]: ` ESCAPE '\'`;
 *    [blobLengthAndHead]: `length(c), substr(c, 1, n)` (length of a BLOB is its byte count).
 *  - [namespaceSwitch]: null (attached databases are qualified); [useNamespace]: nothing;
 *    [currentNamespace]: "main"; [systemNamespaces]: "temp".
 *  - [connector]: URL `jdbc:sqlite:<path>`, `SQLiteConfig().apply { setReadOnly(true) }` for a
 *    read-only connection (open mode, not just the JDBC flag), busy_timeout, foreign_keys=ON; a
 *    pool of one is enough (SqlSession.MAX_CONNECTIONS applies, but SQLite serialises writers).
 *  - [probe]: never called (no server).
 *  - [catalog]: sqlite_schema + pragma_table_info / pragma_index_list / pragma_foreign_key_list;
 *    [catalog].ddl = the `sql` column of sqlite_schema.
 *  - [failureOf]: SQLITE_BUSY/LOCKED → LOCK, SQLITE_CONSTRAINT_UNIQUE/PRIMARYKEY → DUPLICATE_KEY,
 *    "no such table/column" → UNKNOWN_OBJECT, SQLITE_READONLY → SERVER_BUSY_OR_READ_ONLY.
 *  - finally [connectable] = true and the [features] that work.
 */
object SqliteDialect : UnsupportedDialect(DatabaseEngine.SQLITE)
