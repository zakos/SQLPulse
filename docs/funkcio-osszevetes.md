# Egy freemium mobil SQL-kliens funkciói vs. SQLPulse — mit vegyünk át?

Forrás: a felhasználó által 2026-10-03-án bemásolt termékleírás (Android, freemium, több
adatbázismotor, AI-asszisztens). Összevetés a SQLPulse akkori állapotával (`claude/repo-mapping-bjwm40`).

## Már megvan a SQLPulse-ban

| Leírt funkció | SQLPulse |
| --- | --- |
| MySQL, MariaDB | igen (MariaDB Connector/J, régi szerverre legacy driver) |
| TLS, SSH alagút kulccsal/jelszóval | igen (+ jump host, host key pinning) |
| Titkosított jelszótár, biometrikus zár | igen (Keystore AES-GCM, BiometricPrompt, tétlenségi zár) |
| Objektumfa, nézetek, szűrés név szerint | igen (sémaböngésző, + keresés az egész adatbázisban) |
| Cellaszerkesztés UPDATE nélkül, INSERT/DELETE a felületről | igen (Tábla oldal és szerkeszthető lekérdezés-eredmény) |
| Kézi COMMIT / ROLLBACK | igen (manuális tranzakció a szerkesztőben) |
| Szintaxiskiemelés, kedvencek, több utasítás egy blokkban | igen (`SqlHighlighter`, kedvencek `:param`-mal, `SqlScript`) |
| FK-összefüggések | igen (séma-térkép, FK-bejárás, `LinkGuesser`) |
| Diagram az eredményből | igen (`ResultChart`) |
| CSV/JSON export, megosztás | igen (+ TSV, INSERT, Markdown; Android megosztás) |
| Világos/sötét téma, Material | igen |
| Rögzített fejléc és első oszlop | igen |
| Extra gombsor a billentyűzet felett | igen (SELECT, FROM, WHERE, `*`, `=`, `<`, `>`, `,`, `'`, `%`, `(`, `)`) |

## Javasolt átvételek

| # | Funkció | Miért | Ráfordítás |
| --- | --- | --- | --- |
| 1 | **Korlátlan visszavonás / újra a SQL szerkesztőben** | Telefonon könnyű elrontani egy hosszú lekérdezést; ma csak a sorszerkesztés vonható vissza. | kicsi–közepes |
| 2 | **Kódminták (snippetek)** a gombsorról: SELECT, JOIN, UPDATE … WHERE, INSERT, + saját minták | Gyorsabb gépelés telefonon. DDL-minta (ALTER, CREATE) nem, a §2 miatt. | kicsi |
| 3 | **Szkript megosztása** (.sql szövegként, Android megosztás) | Ma az eredmény osztható meg, a lekérdezés szövege nem. | kicsi |
| 4 | **Kiugró értékek jelölése** az oszlop-összesítésben és a diagramon (IQR / z-érték, helyben számolva) | A leírt „adatelemzés” hasznos része AI nélkül, adat nem hagyja el a telefont. | kicsi |
| 5 | **Bővebb gombsor**: `;`, `[` `]`, `!`, `<>`, `>=`, `<=`, `` ` ``, `_`, `AND`, `OR`, `JOIN`, `LIMIT`, testreszabható sorrend | A leírás kifejezetten kéri; a meglévő sor rövidebb. | kicsi |
| 6 | **SQLite fájl megnyitása a telefonról** (csak olvasás alapból) | Terepen hasznos (pl. egy eszköz exportált adatbázisa). Új motor, de a JVM-en ott az Android SQLite. | közepes |

## Döntést igényel (nagy hatású)

- **PostgreSQL, SQL Server / Azure SQL, Oracle támogatás.** Minden motor új driver, dialektus
  (idézőjelek, LIMIT/TOP/FETCH, information_schema eltérések), őrök (`SqlGuards`, `WriteImpact`,
  `ResultEditability`), integrációs teszt-mátrix. Egyenként nagy munka; a PostgreSQL a
  legolcsóbb és leggyakrabban kért. Csak akkor, ha a felhasználói kör ténylegesen használja.
- **Redis.** Teljesen más modell (kulcs-érték), külön képernyők kellenének; nem javasolt.

## Nem javasolt

- **Táblalétrehozó varázsló, tábla/nézet törlése** — a specifikáció §2 kizárja (DDL).
- **AI: természetes nyelvből SQL, optimalizálás** — a séma és a lekérdezések egy külső szolgáltatáshoz
  kerülnének; éles adatbázisokat kezelő belső appnál ez adatkiáramlás. Csak külön döntéssel,
  alapból kikapcsolva, és akkor is csak sémát küldve, adatot nem.
- **Freemium, hirdetések, előfizetés** — belső app (§2: belsős terjesztés); egy hirdetés-SDK
  harmadik fél kódját hozná egy olyan appba, amely éles adatbázis-jelszavakat tárol.
