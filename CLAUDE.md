# CLAUDE.md — SQLPulse projekttérkép

Ez a fájl a Claude-munkamenetek közös emlékezete: a repó térképe, a fontos szabályok,
a haladás és a teendők. Minden munkamenet végén frissítendő.

## Mi ez?

**SQLPulse** — belső használatú Android SQL kliens (Kotlin, Jetpack Compose): MySQL/MariaDB,
PostgreSQL, SQL Server / Azure SQL és helyi SQLite-fájl. A szerveres kapcsolat SSH alagúton vagy
közvetlenül megy, lehet sima vagy TLS-es. Motor-réteg: `data/sql/dialect/` (`docs/tobb-motor-terv.md`). Package / applicationId: `hu.laurel.sqlpulse`.

- Specifikáció (magyar): `docs/specification.md` — a kódban a `§5`, `§8` stb. hivatkozások ide mutatnak.
- Ütemterv / mi kész, mi hiányzik (magyar): `docs/roadmap.md`
- Build, aláírás, CI, biztonsági megjegyzések (angol): `README.md`

## Méret

- ~28 800 sor főkód (`app/src/main`), ~8 200 sor teszt (`app/src/test`, `app/src/androidTest`)
- Egyetlen Gradle modul: `:app`
- Szövegek angolul (`res/values/`) és magyarul (`res/values-hu/`), témánként külön `strings_*.xml`

## Technológiai verem

| Réteg | Eszköz (verziók: `gradle/libs.versions.toml`) |
| --- | --- |
| Build | AGP 8.7.3, Kotlin 2.0.21, KSP, compileSdk/targetSdk 35, minSdk 28, JDK 17 |
| UI | Jetpack Compose (BOM 2024.12.01), Material3, Navigation Compose |
| DI | Hilt 2.52 |
| Helyi adatbázis | Room 2.6.1 + SQLCipher 4.6.1 (titkosított), séma verzió **12** |
| Beállítások | DataStore Preferences |
| SSH | sshj 0.38.0 (+ BouncyCastle, EdDSA) |
| MySQL | MariaDB Connector/J 3.4.1; régi (< MySQL 5.5.3) szerverre automatikusan MySQL Connector/J 5.1.49 |
| Más motorok | pgjdbc 42.7.x (PostgreSQL ≥ 12), mssql-jdbc 13.x jre8 (SQL Server/Azure SQL), sqlite-jdbc 3.5x (natív libek az APK-ban) |
| Biztonság | Android Keystore (AES-256-GCM), BiometricPrompt |
| Teszt | JUnit4, MockK, coroutines-test, sqlite-jdbc (migrációs teszt), Compose UI test |

## Könyvtárszerkezet (`app/src/main/java/hu/laurel/sqlpulse/`)

```
MainActivity.kt, SqlPulseApplication.kt   belépési pontok (FLAG_SECURE az első frame előtt)
di/            AppModule, DatabaseModule (Hilt)
security/      LockManager (tétlenségi zár + alagút bontás), BiometricUnlock
net/           NetworkWatcher, NetworkStatus, ReconnectPolicy (hálózatváltás → alagút újraépítés)
ssh/           TunnelManager (állapotgép), SshTunnel (port forward, jump host), TunnelService
               (foreground service), PinningHostKeyVerifier (TOFU + pinning), SshAuthMethod
               (kulcs/jelszó/keyboard-interactive), MysqlProbe (handshake-csomag ellenőrzés)
data/
  crypto/      KeystoreCrypto, Sealed blobok, DatabaseKeyProvider (SQLCipher jelmondat)
  db/          Room: Entities, Daos, SqlPulseDatabase, Migrations + MigrationStatements (1→12)
  keys/        KeyParsing (OpenSSH v1, PKCS#8, PEM; .ppk/DSA elutasítva), SshKeyRepository
  connection/  ConnectionRepository, környezet (dev/test/éles), ProductionPolicy, időkorlátok,
               CertificateStore (CA), JumpHostCredentials, WriteUnlockStore, SessionHeader(s)
  sql/dialect/ DatabaseEngine, EngineFeature, SqlDialect/SqlSyntax/SqlGrammar, SchemaCatalog,
               EngineConnector; MySql/Postgres/SqlServer/Sqlite Dialect+Catalog+Connector;
               keywords/ (motoronkénti kulcsszavak). LocalDatabaseFiles: SQLite-másolatok.
  sql/         SqlSession (JDBC, driverválasztás), SqlSessionManager (pool, tranzakció),
               QueryExecutor (read-only, WHERE nélküli írás tiltás), SqlGuards, SqlScript,
               QueryParameters (:param), RowEditor/RowSqlBuilder/ColumnEditor (sorszerkesztés),
               WriteImpact (darabszám + DML előnézet), ResultEditability, WriteGate (éles írászár),
               SqlFormatter, SqlHighlighter, ExplainJson/ExplainAdvice, SqlFailure, SslMode, …
  schema/      SchemaRepository (information_schema), SchemaExtras, SchemaCache(+Repository)
               (offline séma), SchemaGraph (térkép elrendezés), RowLinks/LinkTrail/LinkGuesser
               (FK-bejárás), ServerRepository + ServerMetrics (Pulzus)
  query/       QueryRepository (előzmény, kedvencek), QueryDrafts/QueryDraftStore
  search/      DatabaseSearch(+Repository), SearchHitFilter (találat → tábla a sorra szűrve)
  writelog/    WriteLogger + retenció/export (írási napló; minden írási fojtópontból hívva)
  alerts/      AlertRules(+Repository, DataStore JSON), AlertMonitor (30 mp, csak élő kapcsolat), AlertNotifier
  format/      LocaleFormat (szám/dátum/méret explicit locale-lal; UI: appLocale())
  shortcuts/   ShortcutPlan, LauncherShortcuts, ShortcutRequests (indítóikon-parancsikonok)
  export/      ResultSerializer (CSV/TSV/JSON/INSERT), ExportManager (share sheet)
  csv/         CsvParser, CsvImport, CsvImporter
  backup/      Jelszavas mentés/visszatöltés: BackupCodec, BackupCrypto, BackupMerge, …
  snapshot/    ResultSnapshot, ResultDiff („időgép”), SnapshotVault (kapcsolatváltást túlélő felvétel)
  chart/       ResultChart
  grid/        ResultFilter
  diagnostics/ DiagnosticsReport (titokmentes hibajelentés)
  settings/    SettingsRepository
ui/            Compose képernyők; navigáció: ui/SqlPulseApp.kt
  connections/ lista + szerkesztő      keys/     kulcstár
  query/       SQL szerkesztő, fülek, kiegészítés, billentyűparancsok (legnagyobb fájlok)
  grid/        eredménytábla, szűrő, szerkesztő dialógusok, lapok
  schema/      sémaböngésző + tábla részletek   map/  séma-térkép
  server/      futó lekérdezések, KILL          pulse/ élő metrikák
  schemadiff/  séma-összehasonlítás            search/ keresés az adatbázisban
  storage/     Tárhely (méretek, indexek)      writelog/ írási napló
  alerts/      riasztás-szabályok lapja (Pulzus fejléc harang)
  handoff/     EditorHandoff/TableFilterHandoff (SQL → szerkesztő új fül, szűrő → tábla; egyszer fogyasztva)
  explain/     terv fa nézet   chart/  diagram   snapshot/  pillanatfelvétel
  backup/ settings/ diagnostics/ components/ theme/
```

### Navigációs útvonalak (`ui/SqlPulseApp.kt`)
CONNECTIONS → EDITOR, KEYS, SERVER, PULSE, BACKUP, MAP, SETTINGS → WRITE_LOG, SCHEMA_DIFF, QUERY, SCHEMA → TABLE, SEARCH, STORAGE

### Helyi adatbázis táblák (Room, v12; v11: `connection.engine/fileUri/fileName`; v12: séma-objektumok a cache-ben)
`ssh_key`, `connection`, `db_credential`, `ssh_credential`, `ssh_jump_credential`, `known_host`,
`query_history`, `saved_query`, `cached_database`, `cached_table`, `cached_column`,
`cached_index`, `cached_foreign_key` (+onDelete/onUpdate), `cached_check`, `cached_view`,
`cached_trigger`, `cached_object_capture`, `write_log` (írási napló, FK nélkül, 90 nap / 5000 bejegyzés)

## Fő adatfolyam

1. `ConnectionRepository` betölti a profilt → titkok Keystore mögül (biometrikus feloldás).
2. `TunnelManager` → `SshTunnel` (ha SSH be van kapcsolva) helyi port forward, `TunnelService` foreground.
3. `SqlSessionManager` → `SqlSession` JDBC a `127.0.0.1:<port>`-ra (vagy közvetlenül); MariaDB driver,
   a „SET NAMES utf8mb4” elutasításakor legacy MySQL driver.
4. `QueryExecutor` őrök: read-only, WHERE nélküli UPDATE/DELETE tiltás, auto LIMIT, éles-kapcsolat szabályok.
5. UI ViewModel-ek (Hilt) → Compose képernyők.

## Tesztek és CI

- Unit tesztek: `app/src/test/...` — `./gradlew testDebugUnitTest`
- Integrációs tesztek: `integration/` csomag (MySQL/MariaDB, `postgres/`, `sqlserver/`, `sqlite/`) —
  **csak helyben** futnak (env nélkül kihagyják magukat), CI-ban nem.
- Instrumentált UI tesztek: `app/src/androidTest/` (CI nincs hozzá, csak kézzel/emulátoron)
- Workflow: **csak `.github/workflows/build.yml`**, és az is **csak akkor fut, ha egy `main`-re
  nyitott PR-t összefésülnek** (`pull_request: closed` + `merged == true`; a merge commitot
  buildeli): unit teszt + lint + aláírt release APK/AAB + debug APK artifact. A verziószám a
  `BUILD_COMMIT_SHA`-ból jön (a `GITHUB_SHA` PR-eseménynél nem a main commitja). 2026-10-03: a felhasználó kérésére a check/integration/security/instrumentation
  workflow-k törölve — más CI ne kerüljön vissza.
- Helyi build is megy (Android SDK: `/opt/android-sdk`), a Maven tükörrel — ld. „Látványterv a kódban”.
- **Integrációs tesztek helyben** (PostgreSQL: `apt-get install postgresql`, saját klaszter pl. 5433;
  SQL Server: `dockerd` háttérben, `mcr.microsoft.com/mssql/server:2022-latest`; env:
  `SQLPULSE_TEST_POSTGRES_URL/_USER/_PASSWORD`, `SQLPULSE_TEST_MSSQL_URL/_USER/_PASSWORD`;
  SQLite-hoz nem kell szerver; `pg_stat_statements`-hez külön előtöltött klaszter: `/var/tmp/pg-preload`,
  5434, `SQLPULSE_TEST_POSTGRES_PRELOAD_URL`). MySQL/MariaDB: nincs mindig Docker-démon, de root-ként megy az
  `apt-get install mariadb-server`; `mariadbd --datadir=<scratchpad>/… --port=3399` indítás, root
  jelszó `sqlpulse`. MySQL 8: a `mysql-server-core-8.0` .deb-et kicsomagolva (apt-tal ütközne a
  MariaDB-vel). Futtatás: `SQLPULSE_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:<port>/sqlpulse_test`,
  `_USER=root`, `_PASSWORD=sqlpulse`, `_LEGACY=true|false` (+ `SQLPULSE_TEST_MYSQL_REPLICA_URL`),
  `./gradlew --no-build-cache cleanTestDebugUnitTest testDebugUnitTest --tests 'hu.laurel.sqlpulse.integration.*'`
  (`--no-build-cache` kell, mert az env nem Gradle-bemenet → különben a cache-elt eredményt játssza vissza).
- Párhuzamos subagentek (worktree): a worktree a `main`-ből indul → először
  `git merge --ff-only claude/repo-mapping-bjwm40`; `--no-daemon`, és soha `./gradlew --stop`
  (a többi agent buildjét is megöli); mindenki saját `strings_<funkció>.xml`-be ír.

## Fontos szabályok, konvenciók

- A specifikáció (§2) kizárja: DDL, tömeges műveletek, felhasználókezelés, offline sync.
- Titok soha nem kerülhet a repóba (aláíró kulcs csak repository secretben).
- Új Room entitás/oszlop → új migráció a `MigrationStatements`-ben + `MigrationSqlTest` frissítése,
  `SqlPulseDatabase.version` emelése.
- Új szöveg → angol **és** magyar `strings_*.xml` is.
- Commit üzenetek stílusa: rövid, angol, „mit csinál az app” megfogalmazás
  (pl. „Keep more than one query open at a time”).
- Kódkommentek angolul, a „miért”-et magyarázzák.

## Látványterv a kódban

- Terv: https://claude.ai/artifact/KrDZqBn9ggQpZBMYXu9eKH
- Betűk: `res/font/` (Inter 400/500/600/700, JetBrains Mono 400/500/600, OFL licenc:
  `assets/licenses/`), `ui/theme/Type.kt`.
- Színek: `ui/theme/Theme.kt` — minden Material slot kitöltve (ne maradjon baseline lila);
  `SemanticColors.cellNumber/cellDate/cellNull` világos témában sötétebb árnyalat.
- Formák: mező/menü 12, chip teljes kör, kártya 16, dialógus/lap 24 (`SqlPulseShapes`, `Shapes.sheet`).
- Fejléc: minden `TopAppBar` → `colors = sqlPulseTopBarColors()` (háttérszínű sáv).
- Dialógusok: nem `AlertDialog`, hanem `BasicAlertDialog { XCard(...) }` — a kártya (`DialogCard`,
  `DialogHeading`, `SqlBlock`, `DialogButtons` a `components/Dialogs.kt`-ban) külön is kirajzolható,
  mert a Paparazzi az igazi Dialog-ablakot nem kapja el.
- Közös komponensek (`ui/components/Components.kt`): `StepIndicator` (összekötött, pipás lépések),
  `ColorRail` (teljes magasság, `IntrinsicSize.Min` sor kell hozzá), `InfoBadge`, `SectionCaption`.
- Új szövegek: `res/values*/strings_design.xml` (angol + magyar).
- Ikon: `drawable/ic_launcher_{background,foreground,monochrome}.xml`, `ic_stat_pulse.xml`.
- Helyi build: ebben a környezetben VAN Android SDK (`/opt/android-sdk`). A Maven Central 429-cel
  válaszol a Gradle-nek, ezért a `~/.gradle/init.d/mirror.gradle.kts` (csak helyi, nincs a repóban)
  a Google tükröt teszi előre. `./gradlew --no-configuration-cache compileDebugKotlin`.
- A build által generált `app/schemas/` nincs verziókezelve — ne commitold.
- **Képernyőképek a terv mellé (Paparazzi)**: `./gradlew --no-configuration-cache recordPaparazziDebug`
  → `app/src/test/snapshots/images/` (verziókezelve). Tesztek: `app/src/test/.../ui/screenshots/`,
  eszköz: 390×844 dp @2× (`DesignPhone`), magyar locale. Minden képernyőnek van `…Content(…, viewModel: XController)`
  változata; a ViewModel implementálja az interfészt, a teszt hamis controllerrel rajzol.
  A lint a tesztforrásokat kihagyja (`ignoreTestSources`), mert a Paparazzi layoutlibje mellett összeomlik rajtuk.
- **APK helyben** (a debug APK ~94 MB, a fájlküldés korlátja 30 MB): `assembleRelease` (R8, aláíratlan)
  → `zip -d … 'lib/x86/*' 'lib/x86_64/*'` → `zipalign -p 4` → `apksigner sign` egy scratchpadban
  generált próbakulccsal → ~20 MB. A próbakulcs nem a CI kulcsa, ezért ez az APK nem frissít
  CI-ből telepített appot (és fordítva): előtte az appot el kell távolítani.

## Haladás (napló)

- 2026-09-22: Repó feltérképezése, ez a `CLAUDE.md` létrehozva. Kódváltozás nem történt.
- 2026-09-22: Teljes látványterv és új ikon: https://claude.ai/artifact/KrDZqBn9ggQpZBMYXu9eKH
  (Design vászon, 28 artboard, magyar szöveg). A §8 színeire és betűire épül, sötét alapértelmezéssel.
  Sorok: ikon + rendszer · belépés/kapcsolatok · lekérdezés/eredmény/írás · elemzés · séma ·
  szerver/pulzus/beállítások · világos téma + táblagép. Kódváltozás nem történt.
- 2026-09-22: A látványterv beépítése a kódba (ld. „Látványterv a kódban” szakasz).
- 2026-09-23: Helyben fordított APK (0.1.1, R8, arm64 + armv7) átadva próbára.
- 2026-09-23: A felhasználó szerint az első APK-ban a tervből alig látszott valami (jogos: csak
  átszínezés volt). Paparazzi képernyőképekkel képernyőnként összevetve a tervvel és átépítve:
  kapcsolatlista, SQL szerkesztő, eredményrács, séma, tábla, térkép (+ nagyítási hiba javítva),
  pulzus, szerver, beállítások, kulcstár, szerkesztő, mentés, diagnosztika, zárolás. Új APK átadva.
- 2026-09-28: A felhasználó szerint az APK elindul és működik. Következő kör: az írás-megerősítő és
  törlő ablak (`DialogCard`, `SqlBlock`, `StatementLayout`), az export lap, a CSV import terv-kártya,
  az EXPLAIN fa (a legdrágább lépés kiemelve, költség-sáv, tördelődő tények), a diagram (kártya,
  fejléc, rácsvonalak) és az időgép táblázata — mind képernyőképpel a terv mellett.
- 2026-10-02: A t8y2/dbx (Rust/Tauri asztali kliens, Apache-2.0) átnézve: kód közvetlenül nem
  vehető át, az algoritmusok igen. Elemzés és rangsor: `docs/dbx-elemzes.md`.
- 2026-10-02: A dbx-ötletek beépítése párhuzamos subagentekkel (külön git worktree-kben, a
  feladat méretéhez választott modellel), majd összefésülés: séma-összehasonlítás, DML előnézet,
  szerkeszthető lekérdezés-eredmény, keresés az egész adatbázisban, adat-összehasonlítás két
  kapcsolat között, Markdown export. Közben talált és javított rés: a sorszerkesztés és a CSV
  import nem nézte az éles írászárat → `data/sql/WriteGate.kt`. 775 unit teszt + lint zöld.

- 2026-10-02: A felhasználó szerint a dbx-funkciós APK működik. Javasolt következő fejlesztések
  listája lent („Javasolt következő fejlesztések”) — felhasználói döntésre vár.

- 2026-10-02: A javasolt kör kész (párhuzamos subagentekkel): integrációs tesztek az új
  funkciókra (+ talált hiba: backtickes táblanévnél nem volt darabszám/előnézet — javítva),
  replikáció állapotkártya + „Lassú” panel a Szerver képernyőn (`ui/server/ServerOpsPanels.kt`),
  Tárhely képernyő (`ui/storage/`, sémaböngésző ⋮ menü). 879 unit teszt + lint zöld; a teljes
  integrációs csomag (82) zöld helyben MySQL 8.0.46-on és MariaDB 10.11-en, replikával is.

- 2026-10-02: Újabb kör (4 subagent): írási napló (Room v10, `write_log`), oszlop-összesítés
  (oszlopfejléc hosszan nyomva, minden rácsban), indítóikon-parancsikonok (alapból ki, éles soha;
  az exportált Activity csak a tervben szereplő kapcsolatot nyitja), „megnyitás a szerkesztőben”
  a Lassú panelről és a futó lekérdezésekből (`?` → `:p1`), keresési találat → szűrt tábla,
  időgép-sor kapcsolatneve, magyar szám/időformátum, DML előnézet allekérdezés-szűrés.
  911 unit teszt + lint zöld, integrációs csomag (82) zöld MySQL 8.0.46-on és MariaDB 10.11-en.

- 2026-10-03: Egy freemium mobil SQL-kliens funkciólistája összevetve: `docs/funkcio-osszevetes.md`.
  Javasolt: szerkesztő undo/redo, snippetek, szkript megosztása, kiugró értékek, bővebb gombsor,
  SQLite fájl. Döntésre vár: más motorok (PostgreSQL stb.). Nem: DDL-varázsló, AI, hirdetés.

- 2026-10-03: Szerkesztő: korlátlan undo/redo fülenként, kódminták (beépített + saját, DataStore),
  testreszabható gombsor (Beállítások), lekérdezés megosztása. Kiugró értékek (IQR + robusztus z)
  az oszlop-összesítőben, a rácsban és a diagramon.
- 2026-10-03: Több motor: 1. szakasz (Opus) motor-absztrakció + Room v11 + driverek; 2. szakasz
  párhuzamosan: PostgreSQL, SQL Server/Azure SQL (mssql-jdbc; a driver a read-only-t figyelmen
  kívül hagyja → az app őrei + szerverjog), SQLite-fájl (SAF → privát másolat, alapból csak olvas),
  motorfüggő szerkesztő. Összefésüléskor talált hiba: SQL Server `bit` szövegként jött → javítva.
  1324 unit teszt + lint zöld; integrációs tesztek helyben zöldek: MySQL 8.0.46, MariaDB 10.11,
  PostgreSQL 16, SQL Server 2022, SQLite. A felhasználó kérésére csak a `build.yml` CI maradt.

- 2026-10-03: CI: csak a `build.yml`, és csak `main`-re nyitott PR összefésülésekor (release APK/AAB).
- 2026-10-03: Motorok kiterjesztése (3 subagent): EXPLAIN-fa PostgreSQL (`EXPLAIN (FORMAT JSON)`),
  SQL Server (`SET SHOWPLAN_XML`, becsült terv; ha a visszaállítás hibázik, a kapcsolat bezárul) és
  SQLite (`EXPLAIN QUERY PLAN`) — `data/sql/plan/`; SQLite szerkeszthető eredmény; keresés,
  séma-összehasonlítás (`EngineSchemaText`, különböző motor → nem hasonlít), Tárhely
  (`StorageEngines.kt`) mindhárom motorra; Szerver + Pulzus PG-re és SQL Serverre
  (`SqlDialect.server: ServerCatalog`, `PulseProfile`). 1546 unit/integrációs teszt zöld (MySQL 8,
  MariaDB, PG 16 + pg_stat_statements-es PG a 5434-en, SQL Server 2022, SQLite) + lint.

- 2026-10-03: A felhasználó készüléken kipróbálta (valódi MySQL 8.4 „mantis” SSH-n át + SQLite-fájl):
  működik. Talált hibák (javítás folyamatban, 2 subagent): sémaböngésző fejléc levágja a nevet; DDL
  sorok levágva; üres alapérték „alap:”; Szerver áttekintő nyers angol kulcsok/másodpercek; séma-
  térkép 0 kitalált kapcsolat `mantis_*_table` nevekkel + apró dobozok; szerkesztő „500 sor” felirat
  5000-es beállítás mellett; SQLite-fájlnál „éles kiszolgáló” szöveg + régi dialógus; kulcsimport
  gomb betűnként tördel; SQLite UPDATE `"..."` szöveggel → nincs darabszám. Képek: scratchpad/device/.
  → Javítva (2 subagent): ⋮ menü a fejlécekben, egysoros `ConnectionTitle`; DDL vízszintes görgetés;
  `alap: ''`/`NULL`; `ServerFactView` (magyar kulcsok, üzemidő, egy TLS-sor); éles megerősítő kártya
  fájl-szöveggel; kulcsimport kártya; `LinkGuesser` közös elő-/utótag + szerep-oszlopok (reporter_id
  → user), olvasható térképdobozok; a szerkesztő sorlimitje a beállítást követi (eddig fix 500 volt!);
  SQLite egyedi-kulcs hiba magyarul. A „nincs darabszám” a sikertelen írások naplója volt, nem hiba.

- 2026-10-04: Újabb kör (4 subagent): minden maradék `AlertDialog` → design-kártya (host key:
  első/megváltozott, piros), magyar formátumok (`data/format/LocaleFormat.kt`); riasztások a
  Pulzusból (`data/alerts/`, alapból ki, csak élő kapcsolat + feloldott app); mélyebb
  séma-összehasonlítás (nézet, trigger, CHECK, FK-szabály; Room v12, `ObjectText` normalizáló);
  terv-eltérések: felugró kiegészítő lista, CSV kézi oszlop-párosítás (`CsvMapping`), „teljes
  találat” export (`FullExporter`, streamelve, 1M sor/200 MB plafon; MariaDB fetchSize hiba javítva).
  1705 teszt zöld (MySQL 8, MariaDB, PG 16, SQL Server 2022, SQLite integrációval) + lint.

## Javasolt következő fejlesztések (2026-10-02)

A. Megbízhatóság (ajánlott első):
- [x] Integrációs tesztek az új funkciókra (`integration/`), helyben MySQL 8.0 + MariaDB 10.11 zöld;
      MySQL 5.7 csak a CI-ban fut.
- [x] Ismert korlátok javítása: keresési találat → szűrt tábla; időgép-sor kapcsolatneve; DML
      előnézet allekérdezés-szűrés; magyar szám/időformátum (Lassú panel, időgép).
- [x] Maradék locale (sémaböngésző, tábla, mentés, előzmény, diagram, EXPLAIN). Marad: írási napló
      ISO dátuma (szándékos); `formatByteSize` a data rétegben (Pulzus/replikáció) még ponttal.
B. Üzemeltetés telefonról (csak olvasó, §2-vel összefér):
- [x] Replikáció állapota (csatornánként kártya, késés, szálak, hiba; „Nyers” kapcsoló).
- [x] Leglassabb lekérdezések („Lassú” panel; koppintás → vágólap, sosem futtat).
- [x] Tábla- és indexméretek, AUTO_INCREMENT-tartalék, nem használt/redundáns indexek (Tárhely).
- [x] A „Lassú” lekérdezés és a futó lekérdezés megnyitása a szerkesztőben (`EditorHandoff`).
- [x] Döntés (2026-10-04): a keresés arra keres, amit a felhasználó beírt — az app nem végez
      ékezet-összevonást (MySQL `*_ci` collation magától ékezetfüggetlen lehet; ezt nem írjuk felül).
- [x] Riasztás a Pulzusból (harang a Pulzus fejlécben). Nincs még: „leghosszabb futó lekérdezés”,
      „kapcsolatok %”; készüléken nem próbált: értesítés-koppintás zárolva, engedélykérés.
C. Biztonság, elszámolhatóság:
- [x] Írási napló (Beállítások → Írási napló): szűrés, keresés, CSV/JSON export, törlés.
- [x] Döntés (2026-10-04): az írási napló NEM takar ki semmit (a beírt értékeket is tárolja).
D. Kényelem:
- [x] Oszlop-összesítés (darab, nem NULL, különböző, összeg, átlag, min/max) — fejléc hosszan nyomva.
- [x] Indítóikon-parancsikonok (Beállítások, alapból ki; a 3 legutóbbi nem éles kapcsolat).
- [ ] Parancsikonok készüléken kipróbálva még nincsenek (hidegindítás, `onNewIntent`).
- [x] Terv-eltérések: kiegészítés felugró listaként, CSV kézi oszloppárosítás, export „teljes találat”
      (csak a szerkesztőből; a felugró ablakot a Paparazzi nem rögzíti → készüléken nézni).
- [x] Séma-összehasonlítás mélyítése (nézet, trigger, CHECK, FK-szabály) — Room v12.
E. A funkció-összevetésből (`docs/funkcio-osszevetes.md`) — 2026-10-03: a felhasználó jóváhagyta,
   a csapat PostgreSQL-t, MariaDB-t és MS SQL-t is használ:
- [x] Undo/redo a SQL szerkesztőben · snippetek · szkript megosztása · bővebb gombsor.
- [x] Kiugró értékek (oszlop-összesítés + rács + diagram).
- [x] Több motor 1–2. szakasz: PostgreSQL · SQL Server/Azure SQL · SQLite fájl.
- [x] EXPLAIN-fa, keresés, séma-összehasonlítás, Tárhely mindhárom motoron; Szerver/Pulzus PG-n és
      SQL Serveren; SQLite-on eredmény-szerkesztés. (SQLite-on Szerver/Pulzus értelemszerűen nincs.)
- [x] Döntés (2026-10-04): a munkamenet leállítása éles kapcsolaton sem kér erősebb megerősítést.
- [ ] Nincs élőben kipróbálva: PG standby (replika), SQL Server availability group; redundáns
      index csak MySQL-en; SQLite keresés kis/nagybetű-független csak ASCII-re.
- [ ] Készüléken kipróbálni: pgjdbc/mssql-jdbc TLS Androidon, SQLite fájlválasztó, SSH-alagút PG/MSSQL-lel.
- [ ] SQL Server: `GO` elválasztó nem támogatott; varbinary/text/ntext/xml cellaszerkesztés hibázhat;
      Azure AD nincs. PostgreSQL: a jsonb `?` operátort a driver paraméternek veszi.
- [x] SQLite visszaírás (2026-10-04): tábla nézetből kilépéskor rákérdez, ha a másolat „piszkos”
      (`<id>.sqlite.baseline` mtime/méret alapján); kézi gomb a kapcsolatszerkesztőben; írás előtt
      ellenőrzi, nem változott-e az eredeti; írási naplóba `WRITE_BACK`. `data/connection/WriteBack.kt`.
      Korlát: régi kapcsolatoknál csak olvasási jog van → újra ki kell választani a fájlt; régi
      másolatnak nincs alapvonala → frissítés után működik; SAF-on nincs atomi csere.
- [ ] SQLite: kulcs nélküli tábla csak olvasható; WAL-fájlok nem másolódnak.
- [ ] Döntésre vár: a diagram csak IQR-kiugrókat jelöl (a lap a z-szabályt is listázza).
- [ ] Oracle: nem kérték.

## Teendők / nyitott pontok

A `docs/roadmap.md` „Ami ezután jön” szakasza alapján:

- [x] **Séma-összehasonlítás** — `ui/schemadiff/`, a tárolt sémából (az app egyszerre csak egy
      élő kapcsolatot tart). Nézet/trigger/CHECK/FK-szabály nincs a cache-ben → nem hasonlít.
- [x] dbx-ötletek (`docs/dbx-elemzes.md`): DML előnézet, szerkeszthető lekérdezés-eredmény,
      keresés az egész adatbázisban (`ui/search/`), adat-összehasonlítás (`SnapshotVault`), Markdown export.
- [ ] Felhasználói döntésre vár: éles keresés alapértékei (20 sor/tábla, 50 tábla, 1M sor felett
      kihagy); séma-összehasonlítás színei (A zöld, B piros — vagy semleges?).
- [ ] Ismert korlátok az új funkciókban: DML előnézet a SET-be rejtett allekérdezésben lévő
      mellékhatásos függvényt nem szűri; a keresési találatról a tábla szűretlenül nyílik; a
      pillanatfelvétel-sor nem írja ki, melyik kapcsolatról való; az időbélyeg a JVM locale-t követi.
- [ ] **Éles próba minden képernyőn** — eddig csak a kapcsolat, a legacy driver ág és az SSH ág
      van valódi szerveren kipróbálva.
- [x] **Új ikon beépítése** — adaptív ikon (`mipmap-anydpi-v26`), monochrome réteg, értesítés ikon.
- [x] Látványterv beépítése (ld. lent).
- [x] Terv és kód maradék eltérései (kiegészítés, CSV párosítás, teljes export, dialógusok).
      Marad: a táblagépes nézet a meglévő kéthasábos elrendezés.
- [ ] Készüléken megnézni: betűk, ikon a különböző launcherekben, világos téma kontrasztja.
- [ ] (Megfigyelés) `ui/query/QueryEditorScreen.kt` (~1500 sor) és `QueryEditorViewModel.kt`
      (~1200 sor) nagyok — esetleges szétbontás jelölt, ha hozzányúlunk.
