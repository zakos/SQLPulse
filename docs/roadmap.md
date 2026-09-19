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
| Második SSH gép: a szerver egy köztes gépen át is elérhető | `ssh/SshTunnel.kt` |
| TLS négy módban: kikapcsolva, kötelező, CA ellenőrzés, teljes ellenőrzés | `data/sql/SslMode.kt` |
| Saját CA tanúsítvány importálása és tárolása | `data/connection/CertificateStore.kt` |
| A kiépült kapcsolat TLS verziója és titkosítója látszik | `data/schema/ServerRepository.kt` |
| Kulcstár: import, jelszavas kulcs, ujjlenyomatos feloldás | `ui/keys/KeyStoreScreen.kt` |
| Kapcsolatok csoportosítása: fejlesztés, teszt, éles | `data/connection/ConnectionEnvironment.kt` |
| Éles kapcsolat megjelölése, és rákérdezés megnyitás előtt, ha írni is lehet rajta | `ui/connections/ConnectionListScreen.kt` |
| Kapcsolatonkénti időkorlát: kapcsolódásra és lekérdezésre külön | `data/connection/ConnectionTimeouts.kt` |
| Jelszavak és kulcsok Android Keystore mögött, SQLCipher adatbázisban | `data/crypto/` |
| Automatikus zárolás tétlenség után, az alagút bontásával | `security/LockManager.kt` |
| Hálózatváltás észlelése: a megszakadt alagút újraépítése, nem néma hiba | `net/NetworkWatcher.kt` |
| Mentés és visszatöltés: kapcsolatok, tanúsítványok és kulcsok egy jelszavas fájlban | `data/backup/` |

### Böngészés és lekérdezés

| Funkció | Hol |
| --- | --- |
| Adatbázisok és táblák listája, tábla szerkezete és indexei | `ui/schema/` |
| Nézetek, eljárások, triggerek, események külön füleken | `data/schema/SchemaRepository.kt` |
| Tábla motorja, karakterkészlete és mérete | `data/schema/SchemaModels.kt` |
| Adatbázis váltás menet közben, `USE` paranccsal is | `data/sql/QueryExecutor.kt` |
| Adatbázis megadása nélküli kapcsolat (több adatbázisra kérdező lekérdezésekhez) | ugyanott |
| SQL szerkesztő kiemeléssel, előzményekkel, mentett lekérdezésekkel | `ui/query/` |
| Több lekérdezés egyszerre, fülenként saját szöveggel, paraméterekkel és eredménnyel | `ui/query/QueryTabs.kt` |
| A begépelt szöveg túléli a folyamat leállítását | `data/query/QueryDraftStore.kt` |
| Kiegészítés: tábla, oszlop vagy kulcsszó — csak az adott utasításban szereplő táblák oszlopai | `ui/query/SqlCompletion.kt` |
| Több utasítás futtatása, kijelölés futtatása, eredmény utasításonként | `data/sql/SqlScript.kt` |
| SQL formázás, keresés és csere a szerkesztőben | `data/sql/SqlFormatter.kt` |
| Nevesített paraméterek a lekérdezésben | `data/sql/SqlGuards.kt` |
| Automatikus LIMIT olvasó utasításokra | ugyanott |
| Csak olvasható kapcsolat, írás megtagadása | `data/sql/QueryExecutor.kt` |
| WHERE nélküli UPDATE és DELETE megtagadása | ugyanott |
| Futó lekérdezések listája, lekérdezés megszakítása | `ui/server/ServerScreen.kt` |
| Nyitott tranzakciók, zárolásra várók, replikáció állapota | `data/schema/ServerRepository.kt` |
| Felhasználók listája és jogosultságaik (csak olvasás) | ugyanott |

### Eredmény és szerkesztés

| Funkció | Hol |
| --- | --- |
| Táblázat rögzített első oszloppal, átméretezhető oszlopokkal, lapozással | `ui/grid/ResultGrid.kt` |
| Rendezés oszlopfejlécből növekvő, csökkenő és alapállapot között | ugyanott |
| Sor szerkesztése, beszúrása, törlése kulcs alapján | `data/sql/RowEditor.kt` |
| Típus szerinti cellaszerkesztő: enum lista, igen/nem, mai dátum | `data/sql/ColumnEditor.kt` |
| A végrehajtandó SQL megmutatása mentés előtt | `data/sql/RowSqlBuilder.kt` |
| Optimista zárolás: ütközés esetén szól, nem ír felül csendben | `data/sql/RowEditor.kt` |
| Kézi tranzakció: COMMIT és ROLLBACK a lekérdező képernyőn | `data/sql/SqlSessionManager.kt` |
| Export CSV, TSV, JSON és SQL INSERT formátumban | `data/export/` |
| CSV import: oszlopok név szerinti illesztése, egy tranzakcióban | `data/csv/` |
| JSON cella formázva, BLOB belenézés (szöveg vagy hex) | `data/sql/JsonFormatter.kt`, `BlobPreview.kt` |
| Sorok bejárása idegen kulcson át: a hivatkozott sor, és ami a sorra hivatkozik | `data/schema/RowLinks.kt`, `ui/grid/Sheets.kt` |
| EXPLAIN: mi szúr szemet a tervben | `data/sql/ExplainAdvice.kt` |
| Hibák osztályozása (jogosultság, TLS, időtúllépés, zárolás, …) | `data/sql/SqlFailure.kt` |

### Pulzus — élő kiszolgálófigyelés

| Funkció | Hol |
| --- | --- |
| Élő mérőóra: lekérdezés/mp, futó szálak, kapcsolatok, sorzár-várakozás, memóriából olvasás, lassú lekérdezés, kimenő forgalom, replikációs késés | `ui/pulse/PulseScreen.kt` |
| Mintavétel 1, 5 vagy 15 másodpercenként, csak amíg a képernyő elöl van | `ui/pulse/PulseViewModel.kt` |
| Számlálókból ráta két mintából; újraindult kiszolgáló nem ad hamis tüskét | `data/schema/ServerMetrics.kt` |
| Küszöbök szerinti színezés, és „—" ott, ahol nem volt mit mérni | ugyanott |

### Séma-térkép

| Funkció | Hol |
| --- | --- |
| Idegen kulcsokból rajzolt térkép: szülő tábla felül, gyerek alatta, nyíl a kapcsolat | `ui/map/SchemaMapScreen.kt` |
| Csippentés, húzás, koppintás egy táblára: mire hivatkozik, mi hivatkozik rá, és megnyitás | ugyanott |
| Elrendezés determinisztikusan, körhivatkozással és önhivatkozással együtt | `data/schema/SchemaGraph.kt` |
| Egy lekérdezés az egész sémára, nem táblánként egy | `data/schema/SchemaRepository.kt` |

### Táblagép és billentyűzet

| Funkció | Hol |
| --- | --- |
| Két hasáb 720dp felett: a lekérdező szerkesztő és az eredmény egymás mellett | `ui/query/QueryEditorScreen.kt` |
| Két hasáb a sémanézőben: az adatbázisok külön oszlopban maradnak | `ui/schema/SchemaBrowserScreen.kt` |
| Billentyűparancsok: Ctrl+Enter futtat, Ctrl+Shift+Enter az aktuális utasítást, Ctrl+F keresés, Ctrl+Shift+F formázás, Ctrl+S mentés, Esc bezárás | `ui/query/QueryShortcuts.kt` |

### Amin a hibák megfognak

| Funkció | Hol |
| --- | --- |
| Éles MySQL 8.0, 5.7 és MariaDB 11 ellen futó integrációs tesztek a CI-ban | `.github/workflows/integration.yml` |
| A vándorlások valódi SQLite-on végigfuttatva, 1-től a mai verzióig | `app/src/test/.../MigrationSqlTest.kt` |
| Kiadási build R8-cal, a driverek és a natív hívások megtartva | `app/proguard-rules.pro` |

## Ami hiányzik

A kutatási összefoglalóban 2.0-ig felsorolt tételek megvannak; ami alább marad, az
szándékos, nem elmaradás.

Ami a táblagépes elrendezésből egy hasáb maradt: a tábla részletei külön képernyő,
mert a sorszerkesztés lapjai keskeny hasábban olvashatatlanok lennének.

És ami minden tételre igaz: a „kész" itt fordítást és egységteszteket jelent. Éles
MySQL kiszolgálón a kapcsolat, a régi driver ága és az SSH ág van kipróbálva, a többi
képernyő nincs minden kiszolgálóverzióval végigmérve.

## Ami ezután jön

1. **Időgép.** Egy lekérdezés eredményének pillanatfelvétele, majd összehasonlítás egy
   későbbi futással: mi jött, mi tűnt el, mi változott.
2. **Bővebb séma-adatok.** CHECK megszorítások, az idegen kulcsok ON DELETE és ON UPDATE
   szabálya, generált oszlopok, egybevetés, particionálás.
3. **Séma a hálózat nélkül.** A letöltött szerkezet megmarad, így a táblák böngészhetők
   akkor is, amikor nincs kapcsolat.

## Amit szándékosan nem tartalmaz

- **Sémamódosítás (DDL).** A specifikáció második pontja zárja ki: telefonról egy
  elgépelt `ALTER TABLE` következményeit nehéz visszacsinálni.
- **TLS „preferált" mód.** A MariaDB driverben nincs megfelelője, és az a mód, amelyik
  csendben visszaesik titkosítatlanra, pont akkor nem véd, amikor kellene. A választás
  a kötelező és a kikapcsolt között így látható marad.
- **Jelszó nélküli megjegyzés a kulcsokhoz.** Minden titok az Android Keystore mögött
  marad, eszközfeloldáshoz kötve.
