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
               WriteImpact (darabszám + DML előnézet), ResultEditability, WriteGate (éles írászár),
               SqlFormatter, SqlHighlighter, ExplainJson/ExplainAdvice, SqlFailure, SslMode, …
  schema/      SchemaRepository (information_schema), SchemaExtras, SchemaCache(+Repository)
               (offline séma), SchemaGraph (térkép elrendezés), RowLinks/LinkTrail/LinkGuesser
               (FK-bejárás), ServerRepository + ServerMetrics (Pulzus)
  query/       QueryRepository (előzmény, kedvencek), QueryDrafts/QueryDraftStore
  search/      DatabaseSearch(+Repository): érték keresése az egész adatbázisban
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
  explain/     terv fa nézet   chart/  diagram   snapshot/  pillanatfelvétel
  backup/ settings/ diagnostics/ components/ theme/
```

### Navigációs útvonalak (`ui/SqlPulseApp.kt`)
CONNECTIONS → EDITOR, KEYS, SERVER, PULSE, BACKUP, MAP, SETTINGS, SCHEMA_DIFF, QUERY, SCHEMA → TABLE, SEARCH

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
- Helyi build is megy (Android SDK: `/opt/android-sdk`), a Maven tükörrel — ld. „Látványterv a kódban”.
- **Integrációs tesztek helyben**: nincs Docker-démon, de root-ként megy az
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

## Javasolt következő fejlesztések (2026-10-02)

A. Megbízhatóság (ajánlott első):
- [x] Integrációs tesztek az új funkciókra (`integration/`), helyben MySQL 8.0 + MariaDB 10.11 zöld;
      MySQL 5.7 csak a CI-ban fut.
- [ ] Ismert korlátok javítása: keresési találat → tábla a sorra szűrve; időgép-sor kiírja a
      kapcsolatot; DML előnézet allekérdezés-mellékhatás szűrése.
B. Üzemeltetés telefonról (csak olvasó, §2-vel összefér):
- [x] Replikáció állapota (csatornánként kártya, késés, szálak, hiba; „Nyers” kapcsoló).
- [x] Leglassabb lekérdezések („Lassú” panel; koppintás → vágólap, sosem futtat).
- [x] Tábla- és indexméretek, AUTO_INCREMENT-tartalék, nem használt/redundáns indexek (Tárhely).
- [ ] A „Lassú” lekérdezés megnyitása a szerkesztőben (a QUERY útvonal ma nem kap SQL-t).
- [ ] Döntésre vár: a keresés garantáljon-e ékezetfüggetlen egyezést („arviz” → „Árvíz”) a
      szerver collation-jétől függetlenül.
- [ ] Riasztás a Pulzusból (pl. replikációs késés, futó lekérdezés > N mp) — csak amíg az alagút él.
C. Biztonság, elszámolhatóság:
- [ ] Helyi, titkosított írási napló: ki/mikor/melyik kapcsolaton/mit írt (főleg éles), exportálható.
D. Kényelem:
- [ ] Gyors összesítés a kijelölt oszlopra (összeg, átlag, min/max, darab).
- [ ] Android parancsikonok (kedvenc kapcsolat/lekérdezés a launcherről, zárolás után).
- [ ] Terv-eltérések: kiegészítés felugró listaként, CSV kézi oszloppárosítás, export „teljes találat”.
- [ ] Séma-összehasonlítás mélyítése (nézet, trigger, CHECK, FK-szabály) — Room-vándorlás (v10) kell.

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
- [ ] Terv és kód maradék eltérései: a kiegészítés chip-sor (a tervben felugró lista), az
      export lap nem kínál „teljes találat” (újrafuttatás) opciót és a CSV import nem párosít kézzel
      (a tervben igen — ehhez új funkció kell); a táblagépes nézet a meglévő kéthasábos elrendezés;
      az olvasás-megerősítés/paraméter dialógusok és a kulcs-import lap nincs képernyőképpel összevetve.
- [ ] Készüléken megnézni: betűk, ikon a különböző launcherekben, világos téma kontrasztja.
- [ ] (Megfigyelés) `ui/query/QueryEditorScreen.kt` (~1500 sor) és `QueryEditorViewModel.kt`
      (~1200 sor) nagyok — esetleges szétbontás jelölt, ha hozzányúlunk.
