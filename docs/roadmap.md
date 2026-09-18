# Ütemterv

Ez a dokumentum a kutatási összefoglalóban leírt elvárásokat veti össze azzal, ami az
alkalmazásban ténylegesen megvan, és sorrendbe teszi, ami hiányzik. Az eredeti
specifikáció a `specification.md`, ez nem helyettesíti, hanem továbbviszi.

A „Kész" azt jelenti, hogy a kód megvan és a CI lefordítja; azt nem, hogy éles MySQL
kiszolgálón ki lett próbálva. Az ilyen tételek mellett ott a fájl, ahol a működés
megnézhető.

## Ami kész

### Kapcsolat

| Funkció | Hol |
| --- | --- |
| SSH alagút kulcsos belépéssel, gépkulcs rögzítéssel | `ssh/SshTunnel.kt`, `ssh/PinningHostKeyVerifier.kt` |
| Alagút nélküli, közvetlen kapcsolat (az SSH kapcsolható) | `ui/connections/ConnectionEditorScreen.kt` |
| SSH belépés kulccsal vagy jelszóval (password és keyboard-interactive) | `ssh/SshAuthMethod.kt`, `ssh/SshTunnel.kt` |
| TLS négy módban: kikapcsolva, kötelező, CA ellenőrzés, teljes ellenőrzés | `data/sql/SslMode.kt` |
| Saját CA tanúsítvány importálása és tárolása | `data/connection/CertificateStore.kt` |
| A kiépült kapcsolat TLS verziója és titkosítója látszik | `data/schema/ServerRepository.kt` |
| Kulcstár: import, jelszavas kulcs, ujjlenyomatos feloldás | `ui/keys/KeyStoreScreen.kt` |
| Jelszavak és kulcsok Android Keystore mögött, SQLCipher adatbázisban | `data/crypto/` |
| Automatikus zárolás tétlenség után, az alagút bontásával | `security/LockManager.kt` |

### Böngészés és lekérdezés

| Funkció | Hol |
| --- | --- |
| Adatbázisok és táblák listája, tábla szerkezete és indexei | `ui/schema/` |
| Nézetek, eljárások, triggerek, események külön füleken | `data/schema/SchemaRepository.kt` |
| Tábla motorja, karakterkészlete és mérete | `data/schema/SchemaModels.kt` |
| Adatbázis váltás menet közben, `USE` paranccsal is | `data/sql/QueryExecutor.kt` |
| Adatbázis megadása nélküli kapcsolat (több adatbázisra kérdező lekérdezésekhez) | ugyanott |
| SQL szerkesztő kiemeléssel, előzményekkel, mentett lekérdezésekkel | `ui/query/` |
| Több utasítás futtatása, kijelölés futtatása, eredmény utasításonként | `data/sql/SqlScript.kt` |
| SQL formázás, keresés és csere a szerkesztőben | `data/sql/SqlFormatter.kt` |
| Nevesített paraméterek a lekérdezésben | `data/sql/SqlGuards.kt` |
| Automatikus LIMIT olvasó utasításokra | ugyanott |
| Csak olvasható kapcsolat, írás megtagadása | `data/sql/QueryExecutor.kt` |
| WHERE nélküli UPDATE és DELETE megtagadása | ugyanott |
| Futó lekérdezések listája, lekérdezés megszakítása | `ui/server/ServerScreen.kt` |

### Eredmény és szerkesztés

| Funkció | Hol |
| --- | --- |
| Táblázat rögzített első oszloppal, átméretezhető oszlopokkal, lapozással | `ui/grid/ResultGrid.kt` |
| Rendezés oszlopfejlécből növekvő, csökkenő és alapállapot között | ugyanott |
| Sor szerkesztése, beszúrása, törlése kulcs alapján | `data/sql/RowEditor.kt` |
| A végrehajtandó SQL megmutatása mentés előtt | `data/sql/RowSqlBuilder.kt` |
| Optimista zárolás: ütközés esetén szól, nem ír felül csendben | `data/sql/RowEditor.kt` |
| Kézi tranzakció: COMMIT és ROLLBACK a lekérdező képernyőn | `data/sql/SqlSessionManager.kt` |
| Export CSV és JSON formátumban | `data/export/` |
| Hibák osztályozása (jogosultság, TLS, időtúllépés, zárolás, …) | `data/sql/SqlFailure.kt` |

## Ami hiányzik

A sorrend a kutatási összefoglaló prioritásait követi.

### 1.1 — a következő kör

1. **Ugrógép.** A belépés már kulccsal és jelszóval is megy, de csak egyetlen SSH
   géppel. Sok helyen a szerver egy második gépen át érhető el.

### 1.2

2. **BLOB előnézet és típus szerinti szerkesztők** (dátum, felsorolás, JSON).
3. **`EXPLAIN FORMAT=JSON` megjelenítése** olvasható formában.
4. **CSV import, és export SQL INSERT meg TSV formátumban**, elválasztó és fejléc
   beállításával.

### 2.0

5. **Zárolások, hosszan futó tranzakciók, replikáció állapota.**
6. **Felhasználók és jogosultságok megtekintése.**
7. **Kapcsolatok csoportosítása** (fejlesztés, teszt, éles) és külön időkorlátok
   kapcsolatonként. Az éles kapcsolat megjelölése önmagában is véd.
8. **Táblagépes elrendezés** két hasábbal, és billentyűparancsok külső billentyűzethez.

## Amit szándékosan nem tartalmaz

- **Sémamódosítás (DDL).** A specifikáció második pontja zárja ki: telefonról egy
  elgépelt `ALTER TABLE` következményeit nehéz visszacsinálni.
- **TLS „preferált" mód.** A MariaDB driverben nincs megfelelője, és az a mód, amelyik
  csendben visszaesik titkosítatlanra, pont akkor nem véd, amikor kellene. A választás
  a kötelező és a kikapcsolt között így látható marad.
- **Jelszó nélküli megjegyzés a kulcsokhoz.** Minden titok az Android Keystore mögött
  marad, eszközfeloldáshoz kötve.
