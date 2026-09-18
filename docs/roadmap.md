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
| Adatbázis váltás menet közben, `USE` paranccsal is | `data/sql/QueryExecutor.kt` |
| Adatbázis megadása nélküli kapcsolat (több adatbázisra kérdező lekérdezésekhez) | ugyanott |
| SQL szerkesztő kiemeléssel, előzményekkel, mentett lekérdezésekkel | `ui/query/` |
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
| Export CSV és JSON formátumban | `data/export/` |

## Ami hiányzik

A sorrend a kutatási összefoglaló prioritásait követi.

### 1.1 — a következő kör

1. **SSH jelszavas belépés és ugrógép.** Ma csak kulcsos belépés van, egyetlen SSH
   géppel. Sok helyen a belépés jelszavas, és a szerver egy második gépen át érhető el.
2. **Nézetek, tárolt eljárások, triggerek, események böngészése.** Ma csak táblák
   látszanak. Ide tartozik a tábla motorja, karakterkészlete és mérete is.
3. **Hibaüzenetek osztályozása.** Ma a driver saját szövege jelenik meg. Külön kell
   szólni arról, ha a név nem oldható fel, ha időtúllépés van, ha a TLS vagy az SSH
   hiúsult meg, ha a belépés rossz, és ha a jogosultság hiányzik — mert a teendő
   mindegyiknél más.
4. **Több utasítás egy futtatásban, kijelölés futtatása, és több eredményhalmaz.**
5. **Keresés és csere a szerkesztőben, SQL formázás.**

### 1.2

6. **Optimista zárolás a soroknál.** Ma az eredeti kulcs alapján ír vissza; ha közben
   más módosította a sort, az ütközés észrevétlen marad. A mentés előtt össze kell
   vetni a kiolvasott értékekkel, és több sor esetén megmutatni a különbséget.
7. **Kézi COMMIT és ROLLBACK mód.** Írás előtt a tranzakció kézben tartása.
8. **BLOB előnézet és típus szerinti szerkesztők** (dátum, felsorolás, JSON).
9. **`EXPLAIN FORMAT=JSON` megjelenítése** olvasható formában.
10. **CSV import, és export SQL INSERT meg TSV formátumban**, elválasztó és fejléc
    beállításával.

### 2.0

11. **Zárolások, hosszan futó tranzakciók, replikáció állapota.**
12. **Felhasználók és jogosultságok megtekintése.**
13. **Kapcsolatok csoportosítása** (fejlesztés, teszt, éles) és külön időkorlátok
    kapcsolatonként. Az éles kapcsolat megjelölése önmagában is véd.
14. **Táblagépes elrendezés** két hasábbal, és billentyűparancsok külső billentyűzethez.

## Amit szándékosan nem tartalmaz

- **Sémamódosítás (DDL).** A specifikáció második pontja zárja ki: telefonról egy
  elgépelt `ALTER TABLE` következményeit nehéz visszacsinálni.
- **TLS „preferált" mód.** A MariaDB driverben nincs megfelelője, és az a mód, amelyik
  csendben visszaesik titkosítatlanra, pont akkor nem véd, amikor kellene. A választás
  a kötelező és a kikapcsolt között így látható marad.
- **Jelszó nélküli megjegyzés a kulcsokhoz.** Minden titok az Android Keystore mögött
  marad, eszközfeloldáshoz kötve.
