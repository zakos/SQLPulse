# A t8y2/dbx átvehető részei — elemzés

Forrás: https://github.com/t8y2/dbx (megnézve: 2026-10-02, `b4ce023` commit), Apache-2.0 licenc.

## Mi a dbx?

Asztali (macOS/Windows/Linux) + Docker/web adatbázis-kliens: Rust háttér (Tauri), webes felület,
100+ adatbázis, AI SQL-asszisztens, MCP szerver, plugin-rendszer, üzenetsor-konzolok.
Kb. 6300 fájl; a lényegi logika a `crates/` alatt van (`dbx-sql-schema`, `dbx-sql-core`,
`dbx-sql-data`, `dbx-core`).

## Átvehető-e a kód?

**Közvetlenül nem.** A dbx Rust + TypeScript, a SQLPulse Kotlin + Compose Android-app. Rust
crate-eket JNI-n át be lehetne fordítani Androidra, de a modulok a `sqlparser`, `tokio` és saját
típusrendszerükre épülnek, a ráfordítás és az APK-méret nem érné meg.

**Amit érdemes:** az algoritmusokat és a döntéseket Kotlinban újraírni. Az Apache-2.0 ezt
megengedi; ahol egy függvény logikáját szorosan követjük, a fájl fejében hivatkozzunk a forrásra.

## Állapot (2026-10-02)

Az 1–6. tétel beépült (ld. `docs/roadmap.md`, „A dbx-ből átvett ötletek”). A 7. (AST-alapú
írás-osztályozás) szándékosan kimaradt: csak akkor éri meg, ha a mostani őrök hibáznak.

## Javasolt átvételek, fontossági sorrendben

| # | dbx megoldás | Hol van a dbx-ben | Mit adna a SQLPulse-nak | Ráfordítás |
| --- | --- | --- | --- | --- |
| 1 | **Séma-összehasonlítás** két kapcsolat között | `crates/dbx-sql-schema/src/schema_diff.rs` | A roadmap következő tétele: dev vs. éles eltérései (táblák, oszlopok típus/NULL/alapérték/sorrend, indexek, idegen kulcsok, triggerek, nézetek). Átvehető finomságok: MySQL nézet-DDL normalizálás (DEFINER levágása), önhivatkozó FK sémanevének semlegesítése, kis-/nagybetű figyelmen kívül hagyása, karakterkészlet-összevetés kapcsolója. | nagy (új képernyő) |
| 2 | **DML előnézet** | `crates/dbx-sql-core/src/dml_preview_sql.rs` | Írás előtt nemcsak a sorszám (`WriteImpact`), hanem maguk az érintett sorok: `UPDATE t SET a = 1 WHERE …` → `SELECT *, 1 AS "a (új)" FROM t WHERE …`. A megerősítő ablakban régi és új érték egymás mellett. | közepes |
| 3 | **Lekérdezés-eredmény szerkeszthetősége** | `crates/dbx-sql-core/src/sql_editability.rs` | Ma csak a Tábla képernyőn lehet sort szerkeszteni. Egytáblás, aggregálás nélküli `SELECT`-nél, ha a kulcs benne van, a lekérdező képernyő eredménye is szerkeszthető lenne a meglévő `RowEditor`-ral. Az elutasítás okai (JOIN, GROUP BY, CTE, számolt oszlop, nincs kulcs) kiírhatók. | közepes |
| 4 | **Keresés az egész adatbázisban** | `crates/dbx-sql-data/src/database_search_sql.rs` | „Melyik táblában van ez az ügyfélkód?” — érték keresése minden szöveges oszlopban (`LOWER(CAST(col AS CHAR)) LIKE …`, táblánként korlátozva). Telefonon különösen hasznos. | közepes |
| 5 | **Adat-összehasonlítás két kapcsolat között** | `crates/dbx-core/src/data/data_compare.rs` | Ugyanaz a lekérdezés dev és éles ellen, eltérések soronként. A SQLPulse időgépének (`ResultDiffs`) motorja ehhez újrahasznosítható; a dbx sorlimitje és a duplikált kulcs kezelése átvehető. | közepes |
| 6 | **Export Markdown-táblázatként** | `crates/dbx-formats/src/text_export.rs` | Eredmény bemásolása chatbe, jegybe. | kicsi |
| 7 | **AST-alapú írás-osztályozás** | `crates/dbx-sql-core/src/sql_risk.rs` | A mai kulcsszavas `SqlGuards` helyett elemzőfa (Kotlinban JSqlParser). Biztosabb, de +1–2 MB függőség. Csak akkor, ha a mostani őrök hibáznak. | közepes |

## Amit nem érdemes átvenni

- **AI SQL-asszisztens** — a specifikáció 9. pontja szerint az app nem küld adatot sehova; egy
  külső modell a sémát és a lekérdezéseket kapná meg. Csak külön döntéssel, alapból kikapcsolva.
- **MCP szerver, CLI, Docker/web változat, plugin-rendszer** — asztali/szerver-oldali funkciók.
- **100+ adatbázis-driver, üzenetsor- és Redis/MongoDB-konzolok** — a SQLPulse MySQL-kliens.
- **Táblaszerkezet-szerkesztő, szinkron-SQL generálás és futtatás, adatmigráció, teljes
  adatbázis-export** — a specifikáció 2. pontja kizárja (DDL, tömeges műveletek).
- **Kapcsolat-import DBeaverből/Navicatból** — telefonon ritkán kell; a SQLPulse saját mentése
  ezt lefedi.

## Ami a SQLPulse-ban már megvan (nem kell átvenni)

SSH alagút kulccsal/jelszóval, automatikus újracsatlakozás, éles kapcsolat jelölése és
írás-feloldás, megerősítés írás előtt, titkosított mentés, EXPLAIN fa, ER/séma-térkép, előzmények,
kedvencek, fülek, CSV import, export (CSV/TSV/JSON/INSERT), sötét/világos téma.
