# Több adatbázismotor — terv és az 1. fázis eredménye

Állapot: 2026-10-03, 1. fázis (alapozás) kész. A 2. fázisban három ügynök párhuzamosan egy-egy
motort tölt ki (PostgreSQL, SQL Server, SQLite) — ez a dokumentum az ő munkaleírásuk is.

Röviden:

- **Driverek:** PostgreSQL → pgjdbc 42.7.13; SQL Server/Azure SQL → Microsoft mssql-jdbc
  13.6.0.jre8 (a jTDS-t elvetettük); SQLite → xerial sqlite-jdbc 3.53.4.0 (`without-natives` jar
  + az Android `.so`-k a buildben kicsomagolva). Mindhárom benne van a release APK-ban, R8-cal zöld.
- **APK:** 20,97 MB → 26,60 MB (arm64 + armv7, x86 nélkül; +2,95 MB a két szerver-driver,
  +2,65 MB az SQLite). A 30 MB-os fájlküldési korlát alatt marad, de közel van hozzá.
- **Absztrakció:** `data/sql/dialect/` — `DatabaseEngine`, `SqlDialect` (+`SqlSyntax`),
  `SqlGrammar`, `SchemaCatalog`, `EngineConnector`, `EngineFeature`. A `MySqlDialect` a régi kódot
  hívja vagy a régi kódot tartalmazza változatlanul; a másik három motor egy-egy csonk fájl.
- **MySQL viselkedése változatlan:** 991 unit teszt zöld (köztük az új `MySqlDialectTest`, amely
  a régi objektumok válaszaihoz és a pontos SQL-szövegekhez köti a dialektust); az integrációs
  csomag (82 teszt) zöld MariaDB 10.11-en és MySQL 8.0.46-on.
- **Room v11:** `connection.engine` (alapértelmezés `'MYSQL'`), `fileUri`, `fileName`. A mentés
  formátuma bővült; régi mentésből minden kapcsolat MySQL lesz.
- **UI:** motorválasztó a kapcsolatszerkesztő tetején; a nem kész motor „hamarosan” állapotú
  (kiválasztható, de nem menthető); `EngineGate` + `LocalEngineFeatures` rejti a csak-MySQL
  képernyőket.

## 1. Megvalósíthatósági mérések

Minden mérés ebben a környezetben készült (JDK 17, AGP 8.7.3, R8, minSdk 28).

### 1.1 R8 / Android-osztályok

A drivereket egyszerre adtam a buildhez `-dontwarn` nélkül, és az R8 hiányzó-osztály jelentését
driverenként szétválogattam (`app/build/outputs/mapping/release/missing_rules.txt` + a log
„referenced from” sorai):

| Driver | Hiányzó osztályok (Androidon nincsenek) | Mire kellenek | Kell-e nekünk? |
| --- | --- | --- | --- |
| pgjdbc 42.7.13 | `waffle.*`, `com.sun.jna.*` | Windows SSPI | nem |
| | `org.ietf.jgss.*` | Kerberos/GSSAPI | nem |
| | `org.osgi.*`, `javax.transaction.xa.*` | OSGi, XA | nem |
| | `org.checkerframework.*` | annotációk (a függőséget ki is zárjuk) | nem |
| mssql-jdbc 13.6.0.jre8 | `com.microsoft.aad.msal4j.*`, `com.azure.*`, `reactor.*`, `com.google.gson.*` | Azure AD / Key Vault (opcionális Maven-függőségek, nem jönnek le) | nem (1. kör: SQL-hitelesítés) |
| | `org.antlr.v4.*` | Always Encrypted lekérdezés-elemzés | nem |
| | `org.ietf.jgss.*`, `javax.security.auth.*` | Kerberos / JAAS | nem |
| | `javax.xml.stream.*`, `javax.xml.transform.stax.*` | SQLXML | nem |
| | `java.sql.JDBCType`, `java.sql.SQLType` | JDBC 4.2 `setObject(…, SQLType)` | nem hívjuk |
| | `java.lang.management.*` | csak `MaxResultBufferParser.getMaxMemory()` — százalékos `maxResultBuffer` esetén | nem állítjuk be |
| | `java.beans.Transient` | annotáció | nem |
| jTDS 1.3.1 (csak összevetésre) | `jcifs.*` | named pipe, Windows-auth | nem |
| sqlite-jdbc 3.53.4.0 | — (a `-dontwarn org.sqlite.**` csak óvatosság) | | |

A `javax.naming.*`-ra a meglévő szabály (`-dontwarn javax.naming.**`) már vonatkozik.
Az `mssql-jdbc` `java.lang.management` hivatkozását bájtkód-szinten ellenőriztem (`javap`): csak a
`MaxResultBufferParser.getMaxMemory` használja. Az ART lustán verifikál, így egy soha le nem futó
metódus hiányzó osztálya nem okoz hibát — ezt viszont **készüléken még nem láttuk** (ld. Kockázatok).

Egyéb, amit a build kért:

- `META-INF/versions/11/OSGI-INF/MANIFEST.MF` ütközik (pgjdbc ↔ BouncyCastle) → packaging exclude.
- A szabályok: `app/proguard-rules.pro` „Further engines” szakasza; mindhárom driver csomagja
  `-keep … { *; }` (név szerint töltik be a socket/SSL-gyárakat, auth-pluginokat, üzenet-bundle-öket,
  az SQLite natív kódja pedig név szerint hív vissza Java-metódusokat).

### 1.2 Valódi szerverrel, JVM-en (`Smoke.java`, a scratchpadban)

- **PostgreSQL 16.13** (apt, saját klaszter a 5433-as porton, SCRAM-SHA-256): csatlakozás 410 ms,
  `information_schema`, `EXPLAIN (FORMAT JSON)`, `Statement.cancel()` → `57014 canceling statement
  due to user request` 0,8 mp után, `setReadOnly(true)` működik.
- **SQL Server 2022 CU27** (a `dockerd` itt elindítható, az `mcr.microsoft.com/mssql/server:2022-latest`
  image letölthető): mssql-jdbc `encrypt=true;trustServerCertificate=true` → csatlakozás 499 ms,
  `sys.databases`, `INFORMATION_SCHEMA`, `OFFSET … FETCH`, `cancel()` → `HY008` 0,8 mp után.
  **`setReadOnly(true)` csendben hatástalan** (`isReadOnly()` false marad). `encrypt=strict`
  (TDS 8) a nem erre konfigurált szerveren handshake-hibát ad — várt viselkedés.
- **jTDS** ugyanezen a szerveren: csatlakozik, `ssl=require` mellett is — de ilyenkor **minden
  tanúsítványt elfogad** (MITM ellen nem véd), `getSchema()` → `AbstractMethodError` (JDBC 3-as
  kód), `cancel()` → „The server returned an unspecified error”.

### 1.3 APK-méret (release, R8, x86/x86_64 törölve — CLAUDE.md „APK helyben”)

| Build | Méret | Változás |
| --- | --- | --- |
| alap (MariaDB + MySQL legacy) | 20 969 720 B | — |
| + pgjdbc + mssql-jdbc | 23 922 101 B | +2,95 MB |
| + sqlite-jdbc (`.so` arm64 1,26 MB + armv7 1,18 MB, tömörítetlenül tárolva) | 26 569 577 B | +2,65 MB |
| az 1. fázis végén (+ dialektus, UI) | 26 604 721 B | +0,04 MB |

`apkanalyzer` dex-méret csomagonként: `org.postgresql` 609 KB, `com.microsoft.sqlserver` 924 KB
(összevetésül `org.mariadb.jdbc` 405 KB, `com.mysql.jdbc` 666 KB).

## 2. Driverdöntések

**PostgreSQL — pgjdbc.** Nincs valódi alternatíva; a hiányzó osztályok mind opcionális
funkciókhoz tartoznak. Sima jelszavas/SCRAM kapcsolat, TLS a `sslmode`/`sslrootcert`
tulajdonságokkal.

**SQL Server — mssql-jdbc (jre8 build).** A jTDS 2013 óta nem frissül, a `ssl=require` módja nem
ellenőriz tanúsítványt, nincs TDS 8 / `encrypt=strict`, nincs Azure AD. Azure SQL TLS 1.2-t és
titkosítást követel — ezt az mssql-jdbc rendesen tudja, a jTDS csak „titkosít, de bárkinek”.
A jre8 build azért, mert az Android `java.*` API-ja a Java 8-hoz áll közelebb. Ára: 924 KB dex,
és a csak-olvasás jelzőt a driver figyelmen kívül hagyja (ld. Kockázatok).

**SQLite — sqlite-jdbc, nem az Android beépített `android.database.sqlite`.** Az indok az
architektúra: az app minden rétege JDBC-re épül (`SqlSession` pool, `ResultTable.from(ResultSet)`,
`QueryExecutor`, `SchemaRepository`, `RowEditor`). A beépített SQLite-hoz egy második, nem-JDBC
kódút kellene minden rétegbe — vagy egy saját JDBC-burok, ami több ezer sor. A sqlite-jdbc-vel a
SQLite ugyanazon az úton megy, mint a szerverek, és **a JVM unit tesztek ugyanezt a drivert
használják** (a `MigrationSqlTest` már most is), így a SQLite integrációs tesztjei a sima
`testDebugUnitTest`-ben futnak, szerver nélkül. Ára +2,65 MB. A build a `without-natives` jarból
veszi az osztályokat, és az `UnpackSqliteNatives` feladat a `natives-android` jarból rakja a
`.so`-kat a jniLibs-be (konfigurációs cache-sel is ellenőrizve). A teszt-classpath ugyanerre a
verzióra (3.53.4.0) lépett; a migrációs teszt zöld.

Tartalék, ha a méret fontosabb lenne: az appban már benne lévő SQLCipher (`net.zetetic`) üres
kulccsal sima SQLite-fájlt is megnyit, 0 bájt többletért — de az sem JDBC.

## 3. Architektúra

```
data/sql/dialect/
  DatabaseEngine.kt     MYSQL | POSTGRESQL | SQLSERVER | SQLITE (név szerint tárolva: soha ne nevezd át)
  EngineFeature.kt      képernyők/képességek, amelyek nem minden motoron vannak
  SqlGrammar.kt         lexikai szabályok a közös szkennereknek (idézőjelek, kommentek, $$, kezdőszavak)
  SqlDialect.kt         SqlSyntax (SQL-szöveg építése) + SqlDialect (minden más) + SqlDialects registry
  SchemaCatalog.kt      a séma olvasása (névterek, táblák, oszlopok, indexek, FK-k, DDL, …)
  EngineConnector.kt    egy JDBC-kapcsolat nyitása a poolnak
  MySqlDialect.kt       MySqlDialect + MySqlConnector (a régi SqlSession.openWith, szó szerint)
  MySqlCatalog.kt       a régi SchemaRepository information_schema lekérdezései, szó szerint
  UnsupportedDialect.kt a csonkok közös őse: nem connectable, nincs feature, minden motor-specifikus tag dob
  PostgresDialect.kt    csonk — 2. fázis
  SqlServerDialect.kt   csonk — 2. fázis
  SqliteDialect.kt      csonk — 2. fázis
data/connection/LocalDatabaseFiles.kt   filesDir/sqlite/<id>.sqlite — a SQLite-kapcsolat saját fájlmásolata
ui/engine/EngineGate.kt                 LocalEngineFeatures, EngineFeaturesViewModel, EngineGate, EngineUnavailableContent
```

Adatfolyam motorral:

1. `ConnectionEntity.engine` → `DatabaseEngine.fromName` (ismeretlen/üres → MYSQL).
2. `TunnelManager`: ha a motornak nincs szervere (SQLite) → azonnal `Active`, se alagút, se próba,
   se hálózatfigyelés. Különben az alagút motorfüggetlen; az „adatbázis lépés” próbája
   `dialect.probe(host, port)` — MySQL: `MysqlProbe` (a handshake-ből a verzió), a többi
   alapértelmezésben csak TCP-kapcsolódás (verzió null).
3. `SqlSessionManager.open`: `activeDialect = SqlDialects.forEngine(engine)`;
   `SqlSession(JdbcConfig(…, engine, localFile))` → `dialect.connector(config)`; a kezdő névtér
   `dialect.initialNamespace(conn, entity.database)`; hibák `dialect.failureOf(e)`.
4. `withConnection`: a kiválasztott névtér `dialect.useNamespace(conn, ns)` (MySQL: catalog).
5. `QueryExecutor`: `namespaceSwitch` (USE), `classify`, `isUnguardedWrite`, `applyDefaultLimit`,
   `bindParameters`, `writeCountQuery`, `writePreviewQuery` — mind a dialektustól.
6. `SchemaRepository`: a katalógus-olvasás `dialect.catalog`-on, a lapozás `dialect.limit`,
   a nevek `dialect.qualify/quoteIdentifier`, a BLOB-előnézet `dialect.blobLengthAndHead`.
7. `RowEditor`, `CsvImport(er)`, `RowLinks`, `TableQuery`: `SqlSyntax` paraméter, alapértelmezés
   `MySqlDialect` (így a régi hívások és tesztek változatlanok), a repository-k a session
   dialektusát adják át.

### 3.1 A névtér-modell

Az app adatbázis-választója egyszintű marad. Ami benne van:

| Motor | „Adatbázis” mező a kapcsolatban | Választó listája (névtér) | Váltás |
| --- | --- | --- | --- |
| MySQL | kezdő adatbázis (üres is lehet) | adatbázisok (`SHOW DATABASES`) | `setCatalog` |
| PostgreSQL | az adatbázis, amihez csatlakozik (kötelező) | sémák ebben az adatbázisban | `setSchema` (search_path) |
| SQL Server | az adatbázis (`databaseName`) | sémák ebben az adatbázisban | nincs per-kapcsolat; a nevek mindig minősítettek |
| SQLite | nem használt | `main` (+ csatolt) | nincs |

Másik PostgreSQL/SQL Server *adatbázisra* váltás = másik kapcsolat (külön profil). Ez egyszerű,
és a pool minden kapcsolatára biztosan igaz; a kétszintű fa későbbi döntés.

### 3.2 SQLite-fájl a telefonról

Javaslat (a 2. fázis ezt valósítja meg): **másolat az app privát tárhelyén.** A felhasználó SAF-fal
(`ACTION_OPEN_DOCUMENT`) választ fájlt; az app bemásolja `filesDir/sqlite/<connectionId>.sqlite`
alá (`LocalDatabaseFiles.pathFor`), a `ConnectionEntity.fileUri`/`fileName` megjegyzi, honnan jött.

Miért nem helyben: a SQLite-nak valódi, véletlen elérésű útvonal kell, és a napló-/WAL-fájlt a
fájl mellé írja. A SAF content URI-nak nincs útvonala, nincs „mellette” könyvtár, és a szolgáltató
(Drive, USB) tranzakció közben eltűnhet — ez fájlsérülés. A `/proc/self/fd/N` trükk csak olvasásra
és napló nélkül működik.

#### Kiírás az eredeti fájlba (`data/connection/WriteBack.kt`, `SqliteWriteBack.kt`)

- **Szennyezettség (dirty)**: nincs számláló, az írási fojtópontokba nem kell horog. A másolat
  mellé (`<id>.sqlite.baseline`) a másoláskor/kiíráskor egy rekord kerül: az eredeti mérete és
  `LAST_MODIFIED` ideje, valamint a másolat saját mérete és mtime-ja. A másolat akkor szennyezett,
  ha a mérete vagy mtime-ja eltér ettől, vagy nem üres `-wal` fájl van mellette. Újraindítás után
  is helyes, és bármilyen jövőbeli írási hely is lefedi. Baseline nélküli (a funkció előtti)
  másolat soha nem szennyezett; egy „Másolat frissítése” létrehozza.
- **Jog**: a választó (`OpenWritableDocument`) írási jogot is kér és tartósít; ha a szolgáltató nem
  adja, a kapcsolat csak olvasható marad az eredeti felé (a másolat és a frissítés működik).
- **Kilépés a táblanézetből** (vissza gomb, rendszer-vissza): csak SQLite-kapcsolatnál és csak ha
  van szennyezett másolat és megvan az írási jog, kérdés jön: „Kiírod a változásokat az eredeti
  fájlba?” — *Kiírás* / *Csak a másolatban* (kilép, a másolat szennyezett marad, a szerkesztőben
  később kiírható) / *Mégse* (marad). Éles kapcsolatnál piros kártya.
- **Biztonságos kiírás** (`WriteBackPolicy`, `WriteBack.perform`): nyitott kézi tranzakciónál
  megtagadja (előbb commit/rollback); WAL esetén `PRAGMA wal_checkpoint(TRUNCATE)`; az eredeti
  mostani méretét/idejét a baseline-hoz hasonlítja — ha megváltozott vagy nem ellenőrizhető, második
  figyelmeztetés („Az eredeti fájl közben megváltozott — felülírod?”); a kiírás
  `openOutputStream(uri, "wt")`-vel streamelve megy (a SAF-nak nincs ideiglenes-fájl + átnevezés
  művelete), utána méret- és SQLite-fejléc-ellenőrzés; siker esetén új baseline + írási napló
  bejegyzés (`WRITE_BACK`); hiba esetén üzenet, a másolat szennyezett marad. **Korlát**: a helyben
  felülírás félúton megszakadva megsérthet(i) az eredetit — erre való az utólagos ellenőrzés és az,
  hogy a másolat megmarad az újrapróbához.
- **Kapcsolatszerkesztő**: a fájl szakaszban „Kiírás az eredetibe” gomb (szennyezettnél), és a
  „Másolat frissítése” csak szennyezett másolatnál kérdez rá (az elvesző változásokra).

Következmények: alapból csak olvasás (a `readOnly` kapcsoló a SQLite megnyitási módját is
állítsa); írás a másolatba megy; a változások a lenti módon kiírhatók az eredetibe; „Másolat
megosztása” később. A mentésbe a fájl nem kerül (adat, és nagy lehet), és a `fileUri` sem (a SAF-engedély
eszközhöz kötött): visszatöltés után a SQLite-kapcsolat újra fájlt kér.

### 3.3 Room v11 és a mentés

- `MIGRATION_10_11`: `engine TEXT NOT NULL DEFAULT 'MYSQL'`, `fileUri TEXT`, `fileName TEXT`
  (sima ADD COLUMN, nincs táblaújraépítés). `MigrationSqlTest`: új teszt a 10→11 lépésre (a meglévő
  sor MYSQL lesz, a régi app módjára beszúrt új sor is), `CURRENT_VERSION = 11`.
- Mentés: `BackupConnection.engine` (alapértelmezés `"MYSQL"`) és `fileName`; a dekóder hiányzó
  kulcsnál MYSQL-t ad. A formátumverzió marad 1 (bővítés, mint korábban a jump host mezőknél) —
  egy régebbi app a nem-MySQL kapcsolatot MySQL-ként töltené vissza; elfogadható, mert csatlakozni
  úgysem tudna vele. Tesztek: `BackupCodecTest` két új esete.

### 3.4 UI

- Kapcsolatszerkesztő: „Adatbázismotor” szakasz legfelül (`SegmentedChoice`, négy motor). A port a
  motorral együtt vált, amíg az előző motor alapértelmezése áll benne (`ConnectionForm.withEngine`).
  SQLite-nál nincs SSH- és szerverrész, helyette „Adatbázisfájl” kártya (a választógomb a 2. fázisig
  tiltott). Nem kész motor: figyelmeztető sor, a Mentés/Teszt gomb tiltva (`canSave` →
  `SqlDialects.forEngine(engine).connectable`). Képernyőképek: `EngineShots` (+ az újrarögzített
  `EditorShots_editor`).
- `LocalEngineFeatures` (SqlPulseApp adja, `EngineFeaturesViewModel` a session állapotából): a
  sémaböngésző elrejti a keresés/térkép/pulzus/szerver/tárhely belépőt és a rutin/trigger/esemény
  chipet, ha a motor nem tudja. Nincs session vagy MySQL → minden látszik (a meglévő képernyőképek
  ezért nem változtak).
- `EngineGate`: a SERVER, PULSE, MAP, SEARCH, STORAGE útvonal köré tekerve; nem támogatott motornál
  „Nem érhető el … kapcsolaton” oldal (`EngineUnavailableContent`).

## 4. A dialektus felülete

`SqlSyntax` (tiszta, JVM-en tesztelhető): `engine`, `grammar`, `quoteIdentifier`, `qualify`,
`stringLiteral` (csak megjelenítésre), `nullSafeEquals` (pontosan egy `?`), `likeEscape`,
`limit(select, limit, offset, ordered)`, `blobLengthAndHead`.

`SqlDialect : SqlSyntax`: `connectable`, `features`, `classify`, `applyDefaultLimit`,
`isUnguardedWrite`, `namespaceSwitch`, `bindParameters`, `parameters`, `explain`,
`writeCountQuery`, `writePreviewQuery`, `resultEditability`, `connector`, `useNamespace`,
`currentNamespace`, `initialNamespace`, `probe`, `catalog`, `systemNamespaces`, `failureOf`.

| Tag | MySQL (kész) | PostgreSQL | SQL Server | SQLite |
| --- | --- | --- | --- | --- |
| `grammar` | `` ` `` `"` `'`, `\` escape, `#` komment | ANSI + `$$` | ANSI + `[…]` | ANSI + `` ` `` + `[…]` |
| `quoteIdentifier` | `` `a``b` `` | `"a""b"` | `[a]]b]` | `"a""b"` |
| `nullSafeEquals` | `c <=> ?` | `c IS NOT DISTINCT FROM ?` | `EXISTS (SELECT c INTERSECT SELECT ?)` | `c IS ?` |
| `likeEscape` | (üres) | (üres) | `ESCAPE '\'` | `ESCAPE '\'` |
| `limit` | `LIMIT n OFFSET m` | ugyanaz | `ORDER BY (SELECT NULL) OFFSET m ROWS FETCH NEXT n ROWS ONLY` | `LIMIT n OFFSET m` |
| `applyDefaultLimit` | `… LIMIT 500` | ugyanaz (+ `FETCH FIRST` felismerése) | `SELECT TOP (500) …` | `… LIMIT 500` |
| `namespaceSwitch` | `USE db` | `SET search_path TO s` | döntés: `USE db` = másik kapcsolat | nincs |
| `useNamespace` | `setCatalog` | `setSchema` | semmi | semmi |
| `explain` | `EXPLAIN FORMAT=JSON` | `EXPLAIN (FORMAT JSON)` + új olvasó | 1. körben nincs | `EXPLAIN QUERY PLAN` (fa később) |
| `blobLengthAndHead` | `LENGTH, SUBSTRING` | `octet_length, substring(… from 1 for n)` | `DATALENGTH, SUBSTRING` | `length, substr` |
| `catalog` | information_schema | information_schema + pg_catalog | sys.* | sqlite_schema + pragma_* |
| `failureOf` | MySQL hibakódok | SQLSTATE | SQL Server hibaszámok | SQLite eredménykódok |
| tranzakció, cancel | JDBC (`autoCommit`, `cancel()`) | ugyanaz (mérve) | ugyanaz (mérve) | ugyanaz |

## 5. Funkciómátrix

✔ = a 2. fázis végére, ◐ = a 2. fázisban opcionális (ha belefér), — = később / nem.

| Funkció (EngineFeature) | MySQL | PostgreSQL | SQL Server | SQLite |
| --- | --- | --- | --- | --- |
| Csatlakozás, sémaböngésző (táblák, nézetek), Adat + Szerkezet fül, SQL szerkesztő, eredmény, export | ✔ | ✔ | ✔ | ✔ |
| ROW_EDITING (§7.6) | ✔ | ✔ | ✔ | ✔ |
| TABLE_DDL | ✔ | ◐ (összerakva) | ◐ (`sp_helptext` csak nézet/rutin) | ✔ (`sqlite_schema.sql`) |
| ROW_LINKS, SCHEMA_MAP | ✔ | ✔ | ✔ | ✔ |
| WRITE_PREVIEW | ✔ | ◐ | ◐ | ◐ |
| EDITABLE_RESULTS | ✔ | ◐ | ◐ | ◐ |
| CSV_IMPORT | ✔ | ✔ | ✔ | ✔ |
| ROUTINES / TRIGGERS | ✔ | ◐ | ◐ | — / ◐ (trigger) |
| EVENTS | ✔ | — | — | — |
| EXPLAIN | ✔ | ◐ | — | — |
| DATABASE_SEARCH | ✔ | ◐ | ◐ | ◐ |
| SCHEMA_DIFF (tárolt sémából) | ✔ | ◐ (ha a cache-t tölti) | ◐ | ◐ |
| SERVER_ACTIVITY (futó lekérdezések, KILL) | ✔ | ◐ (`pg_stat_activity`, `pg_cancel_backend`) | ◐ (`sys.dm_exec_requests`) | — |
| PULSE, SLOW_QUERIES, REPLICATION, STORAGE | ✔ | — | — | — |

## 6. Mit csinál a három 2. fázisú ügynök

### Közös szabályok

- A saját motorod fájljain kívül csak az itt felsoroltakhoz nyúlj. A közös fájlok (`SqlDialect.kt`,
  `SchemaCatalog.kt`, `SqlGrammar.kt`, `EngineGate.kt`, `SqlPulseApp.kt`, `SqlSessionManager.kt`,
  `TunnelManager.kt`) változtatása csak akkor, ha nélküle nem megy — és akkor írd le a jelentésben,
  mert a másik kettő is érinti.
- A kész motor ismérve: a dialektus minden dobó tagját felülírtad, `connectable = true`, a
  `features` csak azt tartalmazza, ami működik és tesztelt.
- Szövegek: `strings_postgres.xml` / `strings_sqlserver.xml` / `strings_sqlite.xml` (angol + magyar).
- Unit teszt: `XxxDialectTest` a `MySqlDialectTest` mintájára — a felépített SQL-szövegek
  szó szerint rögzítve, a `SqlGrammar` a motor trükkös eseteivel.
- Integrációs teszt: `integration/<motor>/…`, környezeti változóval kapcsolva (mint a
  `TestServer`); a `IntegrationSessions.manager(…, dialect = XxxDialect)` már motorfüggetlen.
- A MySQL-útnak bájtra azonosnak kell maradnia: a teljes unit csomag, a `lintDebug`, az
  `assembleRelease` és — ha a közös kódhoz nyúltál — az integrációs csomag MariaDB-n.

### PostgreSQL ügynök

Fájlok: `data/sql/dialect/PostgresDialect.kt` (kitöltés), új `PostgresCatalog.kt`,
`PostgresConnector.kt`; tesztek `data/sql/dialect/PostgresDialectTest.kt`,
`integration/postgres/…`.

1. `PostgresConnector`: `org.postgresql.Driver()` példány (nem DriverManager), URL
   `jdbc:postgresql://host:port/db`, `connectTimeout`/`socketTimeout` **másodpercben**, `sslmode`
   (`disable`/`require`/`verify-ca`/`verify-full`) a `SslMode`-ból, `sslrootcert` = a CA-fájl,
   `ApplicationName=SQLPulse`, majd `isReadOnly`, `autoCommit = true`.
2. Dialektus a 4. fejezet táblázata szerint; `failureOf` SQLSTATE-ekkel (28P01, 42501, 3D000,
   42P01/42703, 42601, 40P01/55P03, 23505, 57014).
3. `PostgresCatalog`: `information_schema.schemata/tables/columns`, indexek `pg_index`-ből,
   FK-k `information_schema.referential_constraints` + `key_column_usage` (vagy `pg_constraint`),
   CHECK `pg_constraint`, `systemNamespaces` = `pg_catalog`, `information_schema`, `pg_toast`.
4. Helyi szerver a teszthez: `apt-get install -y postgresql`, majd
   `initdb -D <scratchpad>/pg/data -U postgres --pwfile=… -A scram-sha-256` és
   `pg_ctl … -o '-p 5433 -k /tmp -c listen_addresses=127.0.0.1' start` a `postgres`
   felhasználóként (a scratchpad könyvtárainak `chmod 755` kell). Példa: a scratchpad
   `engines/pg-start.sh`.

### SQL Server ügynök

Fájlok: `data/sql/dialect/SqlServerDialect.kt` (kitöltés), új `SqlServerCatalog.kt`,
`SqlServerConnector.kt`; tesztek `SqlServerDialectTest.kt`, `integration/sqlserver/…`.

1. `SqlServerConnector`: `com.microsoft.sqlserver.jdbc.SQLServerDriver()` példány, URL
   `jdbc:sqlserver://host:port;databaseName=db`, `loginTimeout` (s), `socketTimeout` (ms),
   `encrypt`/`trustServerCertificate`/`hostNameInCertificate` a `SslMode`-ból (DISABLED →
   `encrypt=false`; REQUIRED → `true` + `trustServerCertificate=true`; VERIFY_CA → truststore a
   CA-ból; VERIFY_IDENTITY → + hostnév), `applicationName=SQLPulse`. Azure SQL: `encrypt=true`.
2. **Csak-olvasás:** a driver a `setReadOnly`-t figyelmen kívül hagyja — a kapcsolatszerkesztőben
   SQL Servernél írd ki, hogy a védelem az app őrei + a szerver jogosultságai.
3. `applyDefaultLimit` → `SELECT TOP (n)`; figyelj a `DISTINCT`, `WITH`, meglévő `TOP`/`OFFSET`
   esetekre; `limit` → `OFFSET … FETCH` (rendezés nélkül `ORDER BY (SELECT NULL)`).
4. `SqlServerCatalog`: `sys.schemas`, `sys.tables`/`sys.views`, `sys.columns` + `sys.types`,
   `sys.indexes` + `sys.index_columns`, `sys.foreign_keys` + `sys.foreign_key_columns`,
   `sys.check_constraints`.
5. Helyi szerver: itt a `dockerd` elindul (háttérben, hosszú `timeout`-tal), majd
   `docker run -d -e ACCEPT_EULA=Y -e 'MSSQL_SA_PASSWORD=…' -p 127.0.0.1:1433:1433
   mcr.microsoft.com/mssql/server:2022-latest`. A CI-ban `services:` blokk ugyanezzel az image-dzsel.

### SQLite ügynök

Fájlok: `data/sql/dialect/SqliteDialect.kt` (kitöltés), új `SqliteCatalog.kt`,
`SqliteConnector.kt`, `data/connection/LocalDatabaseFiles.kt` (másolás, frissítés, törlés),
a kapcsolatszerkesztő `FileSection`-je (`ConnectionEditorScreen.kt`) + a ViewModel fájlválasztója;
tesztek `SqliteDialectTest.kt`, `integration/sqlite/…` (ideiglenes fájllal, szerver nélkül —
ezek a sima check-futásban is futhatnak).

1. Fájlválasztás SAF-fal → másolás `LocalDatabaseFiles.pathFor(id)`-ra (a mentés után, amikor
   már van id), `fileUri`/`fileName` kitöltése; a kapcsolat törlésekor `LocalDatabaseFiles.delete`
   (`ConnectionRepository.delete`).
2. `SqliteConnector`: `org.sqlite.JDBC()`, URL `jdbc:sqlite:<config.localFile>`,
   `SQLiteConfig().apply { setReadOnly(config.readOnly); busyTimeout; enforceForeignKeys(true) }`;
   hiányzó fájlnál érthető hiba.
3. `SqliteCatalog`: `sqlite_schema`, `pragma_table_info`, `pragma_index_list`/`index_info`,
   `pragma_foreign_key_list`; DDL = `sqlite_schema.sql`.
4. A `ProductionPolicy` (TLS/alagút szabályok) SQLite-ra értelmetlen — a szerkesztőben és a
   policyben kezeld (fájlnál ne legyen „védtelen” elutasítás).
5. A kapcsolatlista kártyája SQLite-nál a fájlnevet mutassa a host helyett.

## 7. Ami még közvetlenül MySQL-es (tudatosan, a feature-zászló mögött)

Ezek nem a dialektuson mennek; amíg a motor `features`-e nem tartalmazza a megfelelő elemet, a UI
nem jut el hozzájuk. Aki bekapcsolja a zászlót, ezeket kell motorfüggővé tennie:

- `ServerRepository`, `ServerMetrics`, `SlowStatements`, `ReplicationStatus` (SERVER_ACTIVITY,
  PULSE, SLOW_QUERIES, REPLICATION), `StorageRepository` (STORAGE).
- `DatabaseSearch` + `DatabaseSearchRepository` (`LEFT`, `CAST AS CHAR`, MySQL típusnevek,
  `information_schema`).
- `SchemaDiffRepository.SYSTEM_SCHEMAS`; a séma-cache a `SchemaRepository`-n át már motorfüggetlen.
- `ui/query/QueryEditorViewModel`: `EXPLAIN_JSON` konstans → `sessions.dialect().explain(sql)`;
  az EXPLAIN gomb a `LocalEngineFeatures.current.has(EXPLAIN)` alapján. `ExplainJson` MySQL-alakot olvas.
- `ui/query/ResultEditing.kt`: `ResultEditabilities.analyse` → `sessions.dialect().resultEditability`.
- `SqlHighlighter`, `SqlCompletion`, `SqlFormatter`: MySQL kulcsszavak (kozmetika).
- `ColumnEditor`: MySQL típusnevek (`tinyint(1)`, `enum`, `set`) → szerkesztő-választás.
- `ResultSerializer` INSERT-exportja backtick-et ír (`quoteIdentifier`).
- `TableDetailScreen`: a DDL-fül, a sorszerkesztés és a FK-bejárás még nem nézi a
  `TABLE_DDL`/`ROW_EDITING`/`ROW_LINKS` zászlót.
- Szövegek: a kapcsolódási lépés „MySQL” címkéje, a „közvetlenül a MySQL-hez” megjegyzés;
  `DiagnosticsSource` nem írja ki a motort.

## 8. Kockázatok

1. **Készüléken még semmi nem futott a három új driverből.** Az R8-build és a JVM-teszt zöld, de
   az ART futásidejű különbségei (TLS-szolgáltató, `java.lang.management`, `SSLSocket` viselkedés
   az mssql-jdbc TDS-be csomagolt TLS-ével, a sqlite-jdbc `System.loadLibrary` útja) csak
   telefonon derülnek ki. Az első 2. fázisú build után kézi próba kell mindhárommal.
2. **SQL Server csak-olvasás:** a driver nem érvényesíti; a `QueryExecutor` őrei és a szerver
   jogai maradnak. Éles SQL Server kapcsolatot csak olvasó felhasználóval érdemes menteni.
3. **APK-méret:** 26,6 MB; a fájlküldési korlát 30 MB. Ha tovább nő, az x86 natívok már most
   ki vannak dobva — következő lépés az ABI-split vagy a SQLCipher-alapú SQLite.
4. **mssql-jdbc mérete és frissítése:** havi kiadások; a `jre8` sor támogatását a Microsoft
   egyszer megszüntetheti — akkor a `jre11` buildet kell Android-kompatibilitásra mérni.
5. **Névtér-modell:** PostgreSQL/SQL Server esetén a „másik adatbázis = másik kapcsolat” szabály
   meglepheti a felhasználót; a felület mondja ki.
6. **Közös fájlok a 2. fázisban:** a `ConnectionEditorScreen` (SQLite ügynök) és a három
   `strings_*.xml` kivételével a három ügynöknek nem kell ugyanahhoz a fájlhoz nyúlnia; ha mégis,
   az összefésüléskor kell rendezni.
