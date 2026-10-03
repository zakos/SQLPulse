package hu.laurel.sqlpulse.data.sql.dialect

/**
 * Microsoft SQL Server and Azure SQL — phase 2 fills this file in (docs/tobb-motor-terv.md,
 * "SQL Server ügynök").
 *
 * Driver: Microsoft's mssql-jdbc, jre8 build (`com.microsoft.sqlserver:mssql-jdbc`, already in
 * the build and in proguard-rules.pro), instantiated as
 * `com.microsoft.sqlserver.jdbc.SQLServerDriver()`. jTDS was rejected: unmaintained since 2013,
 * `ssl=require` trusts any certificate, no TDS 8 / strict encryption, no Azure AD. Smoke-tested
 * from the JVM against SQL Server 2022 with `encrypt=true`: connect, INFORMATION_SCHEMA,
 * OFFSET … FETCH, Statement.cancel() all behave — but `setReadOnly(true)` is silently ignored
 * (isReadOnly stays false), so the app's own guards and the server grants are the only read-only
 * protection here. Say so in the connection editor.
 *
 * What to override, in this file or in SqlServerCatalog.kt / SqlServerConnector.kt next to it:
 *  - [grammar]: ANSI plus `'[' to ']'`; write starters + `merge`; read starters without `explain`.
 *  - [quoteIdentifier]: `[name]` with `]` doubled; [nullSafeEquals]:
 *    `EXISTS (SELECT c INTERSECT SELECT ?)`; [likeEscape]: ` ESCAPE '\'`.
 *  - [limit]: `… ORDER BY (SELECT NULL) OFFSET m ROWS FETCH NEXT n ROWS ONLY` (the ORDER BY only
 *    when [limit]'s `ordered` is false); [applyDefaultLimit]: `SELECT TOP (n) …` — careful with
 *    `SELECT DISTINCT`, `WITH`, and a statement that already has TOP/OFFSET.
 *  - [blobLengthAndHead]: `DATALENGTH(c), SUBSTRING(c, 1, n)`.
 *  - [namespaceSwitch]: T-SQL `USE db` names a database, not a schema: decide (design doc) — the
 *    app's picker lists schemas of the connection's database.
 *  - [useNamespace]: nothing to set per connection (no default-schema switch in T-SQL); the app
 *    always qualifies names. [currentNamespace]: `SELECT SCHEMA_NAME()`.
 *  - [connector]: URL `jdbc:sqlserver://host:port;databaseName=db`, properties user/password,
 *    `loginTimeout` (s), `socketTimeout` (ms), `encrypt` (false/true/strict) and
 *    `trustServerCertificate` from SslMode, `trustStore` built from the CA file,
 *    `hostNameInCertificate` for VERIFY_IDENTITY, `applicationName=SQLPulse`. Azure SQL needs
 *    `encrypt=true` and port 1433 open to the phone or the tunnel's far end.
 *  - [explain]: SHOWPLAN_XML needs SET … ON on the same connection — leave EXPLAIN out first.
 *  - [failureOf]: SQL Server error numbers (18456 login, 229/230 permission, 4060 db, 208/207
 *    object/column, 102/156 syntax, 1205 deadlock, 1222 lock timeout, 2627/2601 duplicate).
 *  - [catalog]: sys.* views (sys.schemas, sys.tables/views, sys.columns, sys.indexes,
 *    sys.foreign_keys, sys.check_constraints); DDL via sp_helptext only for views/routines.
 *  - finally [connectable] = true and the [features] that work.
 */
object SqlServerDialect : UnsupportedDialect(DatabaseEngine.SQLSERVER)
