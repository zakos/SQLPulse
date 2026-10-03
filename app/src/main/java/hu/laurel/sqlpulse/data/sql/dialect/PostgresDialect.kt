package hu.laurel.sqlpulse.data.sql.dialect

/**
 * PostgreSQL — phase 2 fills this file in (docs/tobb-motor-terv.md, "PostgreSQL ügynök").
 *
 * Driver: pgjdbc (`org.postgresql:postgresql`, already in the build and in proguard-rules.pro),
 * instantiated as `org.postgresql.Driver()` — never through DriverManager (see MySqlConnector).
 * Smoke-tested from the JVM against PostgreSQL 16 with SCRAM-SHA-256: connect, information_schema,
 * EXPLAIN (FORMAT JSON), Statement.cancel() and setReadOnly all behave.
 *
 * What to override, in this file or in PostgresCatalog.kt / PostgresConnector.kt next to it:
 *  - [grammar]: ANSI plus `dollarQuotes = true`; read starters + `show`, `table`; no `#` comments.
 *  - [limit], [applyDefaultLimit]: `LIMIT n OFFSET m` (SqlGuards.applyDefaultLimit works with the
 *    grammar; teach hasLimit about `FETCH FIRST`).
 *  - [blobLengthAndHead]: `octet_length(c), substring(c from 1 for n)`; [likeEscape] = "".
 *  - [namespaceSwitch]: `SET search_path TO s` / `SET SCHEMA 's'`; [useNamespace]: `connection.schema = s`.
 *  - [currentNamespace]: `connection.schema`; [systemNamespaces]: pg_catalog, information_schema, pg_toast.
 *  - [connector]: URL `jdbc:postgresql://host:port/db`, properties user/password, connectTimeout
 *    and socketTimeout in SECONDS, `sslmode` from SslMode (disable/require/verify-ca/verify-full,
 *    `sslrootcert` = the CA file), `ApplicationName=SQLPulse`; then readOnly + autoCommit.
 *  - [explain]: `EXPLAIN (FORMAT JSON) …` and a PostgreSQL reader for the plan tree (ExplainJson
 *    is MySQL's shape) — or leave EXPLAIN out of [features] for phase 2.
 *  - [writeCountQuery] / [writePreviewQuery]: WriteImpact is MySQL-flavoured; reuse its parser
 *    with the grammar or return null (the dialog then says "unknown", which is honest).
 *  - [resultEditability]: reuse ResultEditabilities if its tokenizer handles `"name"`, else UNSUPPORTED.
 *  - [failureOf]: map SQLSTATE classes (28P01 auth, 42501 privilege, 3D000 unknown db,
 *    42P01/42703 unknown object, 42601 syntax, 40P01/55P03 lock, 23505 duplicate, 57014 cancel).
 *  - [catalog]: information_schema + pg_catalog (indexes from pg_index; DDL is not available
 *    server-side — build it or leave TABLE_DDL out).
 *  - finally [connectable] = true and the [features] that work.
 */
object PostgresDialect : UnsupportedDialect(DatabaseEngine.POSTGRESQL)
