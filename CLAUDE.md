# CLAUDE.md — SQLPulse projekttérkép

Ez a fájl a Claude-munkamenetek közös emlékezete: a repó térképe, a fontos szabályok,
a haladás és a teendők. Minden munkamenet végén frissítendő.

## Mi ez?

**SQLPulse** — belső használatú Android MySQL kliens (Kotlin, Jetpack Compose).
A kapcsolat SSH alagúton vagy közvetlenül éri el a MySQL/MariaDB kiszolgálót, a MySQL
kapcsolat lehet sima vagy TLS-es. Package / applicationId: `hu.laurel.sqlpulse`.

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
| Helyi adatbázis | Room 2.6.1 + SQLCipher 4.6.1 (titkosított), séma verzió **9** |
| Beállítások | DataStore Preferences |
| SSH | sshj 0.38.0 (+ BouncyCastle, EdDSA) |
| MySQL | MariaDB Connector/J 3.4.1; régi (< MySQL 5.5.3) szerverre automatikusan MySQL Connector/J 5.1.49 |
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
  db/          Room: Entities, Daos, SqlPulseDatabase, Migrations + MigrationStatements (1→9)
  keys/        KeyParsing (OpenSSH v1, PKCS#8, PEM; .ppk/DSA elutasítva), SshKeyRepository
  connection/  ConnectionRepository, környezet (dev/test/éles), ProductionPolicy, időkorlátok,
               CertificateStore (CA), JumpHostCredentials, WriteUnlockStore, SessionHeader(s)
  sql/         SqlSession (JDBC, driverválasztás), SqlSessionManager (pool, tranzakció),
               QueryExecutor (read-only, WHERE nélküli írás tiltás), SqlGuards, SqlScript,
               QueryParameters (:param), RowEditor/RowSqlBuilder/ColumnEditor (sorszerkesztés),
               SqlFormatter, SqlHighlighter, ExplainJson/ExplainAdvice, SqlFailure, SslMode, …
  schema/      SchemaRepository (information_schema), SchemaExtras, SchemaCache(+Repository)
               (offline séma), SchemaGraph (térkép elrendezés), RowLinks/LinkTrail/LinkGuesser
               (FK-bejárás), ServerRepository + ServerMetrics (Pulzus)
  query/       QueryRepository (előzmény, kedvencek), QueryDrafts/QueryDraftStore
  export/      ResultSerializer (CSV/TSV/JSON/INSERT), ExportManager (share sheet)
  csv/         CsvParser, CsvImport, CsvImporter
  backup/      Jelszavas mentés/visszatöltés: BackupCodec, BackupCrypto, BackupMerge, …
  snapshot/    ResultSnapshot, ResultDiff („időgép”)
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
  explain/     terv fa nézet   chart/  diagram   snapshot/  pillanatfelvétel
  backup/ settings/ diagnostics/ components/ theme/
```

### Navigációs útvonalak (`ui/SqlPulseApp.kt`)
CONNECTIONS → EDITOR, KEYS, SERVER, PULSE, BACKUP, MAP, SETTINGS, QUERY, SCHEMA → TABLE

### Helyi adatbázis táblák (Room, v9)
`ssh_key`, `connection`, `db_credential`, `ssh_credential`, `ssh_jump_credential`, `known_host`,
`query_history`, `saved_query`, `cached_database`, `cached_table`, `cached_column`,
`cached_index`, `cached_foreign_key`

## Fő adatfolyam

1. `ConnectionRepository` betölti a profilt → titkok Keystore mögül (biometrikus feloldás).
2. `TunnelManager` → `SshTunnel` (ha SSH be van kapcsolva) helyi port forward, `TunnelService` foreground.
3. `SqlSessionManager` → `SqlSession` JDBC a `127.0.0.1:<port>`-ra (vagy közvetlenül); MariaDB driver,
   a „SET NAMES utf8mb4” elutasításakor legacy MySQL driver.
4. `QueryExecutor` őrök: read-only, WHERE nélküli UPDATE/DELETE tiltás, auto LIMIT, éles-kapcsolat szabályok.
5. UI ViewModel-ek (Hilt) → Compose képernyők.

## Tesztek és CI

- Unit tesztek: `app/src/test/...` — `./gradlew testDebugUnitTest`
- Integrációs tesztek (valódi MySQL 8.0 / 5.7 / MariaDB 11 a CI-ban): `integration/` csomag
- Instrumentált UI tesztek: `app/src/androidTest/` (csak kézi workflow)
- Workflow-k (`.github/workflows/`):
  - `check.yml` — minden push/PR: unit teszt + lint
  - `build.yml` — push: + aláírt debug/release APK artifact
  - `integration.yml` — PR: integrációs tesztek
  - `security.yml` — PR/push: biztonsági ellenőrzések
  - `instrumentation.yml` — kézi: emulátoros tesztek
- **Fontos:** ebben a felhő környezetben nincs Android SDK, így a build/teszt csak a CI-ban fut.

## Fontos szabályok, konvenciók

- A specifikáció (§2) kizárja: DDL, tömeges műveletek, felhasználókezelés, offline sync.
- Titok soha nem kerülhet a repóba (aláíró kulcs csak repository secretben).
- Új Room entitás/oszlop → új migráció a `MigrationStatements`-ben + `MigrationSqlTest` frissítése,
  `SqlPulseDatabase.version` emelése.
- Új szöveg → angol **és** magyar `strings_*.xml` is.
- Commit üzenetek stílusa: rövid, angol, „mit csinál az app” megfogalmazás
  (pl. „Keep more than one query open at a time”).
- Kódkommentek angolul, a „miért”-et magyarázzák.

## Haladás (napló)

- 2026-09-22: Repó feltérképezése, ez a `CLAUDE.md` létrehozva. Kódváltozás nem történt.

## Teendők / nyitott pontok

A `docs/roadmap.md` „Ami ezután jön” szakasza alapján:

- [ ] **Séma-összehasonlítás** — két kapcsolat szerkezete egymás mellett (dev vs. éles eltérések).
- [ ] **Éles próba minden képernyőn** — eddig csak a kapcsolat, a legacy driver ág és az SSH ág
      van valódi szerveren kipróbálva.
- [ ] (Megfigyelés) `ui/query/QueryEditorScreen.kt` (~1500 sor) és `QueryEditorViewModel.kt`
      (~1200 sor) nagyok — esetleges szétbontás jelölt, ha hozzányúlunk.
