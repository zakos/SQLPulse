# Android MySQL kliens – Specifikáció

2026-09-17 · Belsős használatra szánt Android MySQL kliens funkcionális és UX specifikációja. Kötelező SSH tunnel, OpenSSH privát kulcs alapú hitelesítés.

## 1. Áttekintés

Belsős Android alkalmazás, amellyel a csapat mobilról éri el a cég MySQL adatbázisait: sémát böngész, lekérdezéseket futtat, az eredményt megnézi vagy exportálja.

A mai állapotban egy gyors ellenőrzéshez laptopot kell nyitni, VPN-re csatlakozni és desktop klienst indítani. Ez 5–10 perc olyan helyzetekben, ahol a válasz fél perc alatt megvan. Az app ezt az egy súrlódást szünteti meg.

**Alapelvek**

- **Biztonság alapból, nem opcióként.** Nyers MySQL kapcsolat nem létezik az appban. Minden forgalom SSH tunnelen megy, kizárólag OpenSSH privát kulcsos hitelesítéssel. Jelszavas SSH login nincs implementálva.
- **Olvasás-első.** A tipikus használat a megnézés, nem az átírás. Az írás támogatott, de külön, tudatos lépéseket igényel.
- **A telefon nem munkaállomás.** Nem cél a desktop kliensek lemásolása. Cél, hogy a leggyakoribb feladatok kiválóan menjenek kézben, egy hüvelykujjal.

## 2. Scope

Az MVP a következőket tartalmazza:

| Terület | MVP-ben | Későbbi fázis |
| --- | --- | --- |
| Kapcsolat | SSH tunnel kulccsal, mentett profilok | Jump host (több ugrás), IPv6-only |
| Séma | Adatbázis, tábla, oszlop, index, nézet | Tárolt eljárások, triggerek, eventek |
| Lekérdezés | SELECT, lapozott eredmény, előzmény, kedvencek | Több egyidejű tab, EXPLAIN vizualizáció |
| Írás | Cellaszintű UPDATE, sor INSERT/DELETE megerősítéssel | DDL (ALTER, CREATE TABLE), tömeges műveletek |
| Export | CSV, JSON, vágólap | Excel, megosztás e-mailben, ütemezett riport |
| Biztonság | Keystore, biometria, host key pinning | MDM integráció, központi kulcsprovisioning |

**Explicit nem-célok**

- Nincs jelszavas SSH hitelesítés, még opcionálisan sem.
- Nincs közvetlen MySQL kapcsolat tunnel nélkül, még fejlesztői kapcsolóval sem.
- Nincs adatbázis-adminisztráció: user kezelés, replikáció, konfigurációs változók írása.
- Nincs offline adat-szinkronizáció; az app csak élő kapcsolaton működik.
- Nincs iOS verzió és nincs Play Store publikáció; a terjesztés belsős (APK vagy Play Private App).

## 3. Szerepek és használati esetek

Három felhasználói profilra tervezünk, mindhárom ugyanazt az appot használja, csak más MySQL joggal.

| Szerep | Tipikus feladat | MySQL jog |
| --- | --- | --- |
| Elemző, support | Egy rekord megkeresése, státusz ellenőrzése | SELECT |
| Fejlesztő | Ad-hoc lekérdezés, teszt adat javítása | SELECT, UPDATE, INSERT, DELETE |
| DBA | Séma ellenőrzés, futó lekérdezések, indexek | SELECT + PROCESS |

A jogosultságot a MySQL oldalon kell szabályozni, nem az appban. Az app nem tartalmaz saját szerepkör-logikát: ha a DB visszautasít egy műveletet, az app a hibát mutatja meg.

**Fő folyamatok**

1. **Gyors keresés.** Kapcsolat megnyitása → tábla kiválasztása → szűrés egy oszlopra → sor megnyitása részletes nézetben.
2. **Mentett lekérdezés futtatása.** Kedvencek → lekérdezés paraméterrel → eredmény → CSV export.
3. **Adatjavítás.** Sor megkeresése → cella szerkesztése → generált UPDATE megtekintése → megerősítés → futtatás.
4. **Séma ellenőrzés.** Tábla kiválasztása → DDL megtekintése → indexek és idegen kulcsok listája.

## 4. Architektúra

Az app minden MySQL forgalmat egy helyi SSH port-forwardon keresztül küld: a JDBC driver `127.0.0.1` egy efemer portjára csatlakozik, amit az SSH kliens továbbít a bastion hoston keresztül az adatbázisig.

```
Android app (JDBC kliens)
   ↓
Helyi port 127.0.0.1:automatikus
   ↓
SSH kliens (kulcsos auth)
   ↓
Bastion host (SSH szerver)
   ↓
MySQL szerver :3306
```

A MySQL szerver 3306-os portja soha nem érhető el közvetlenül az internetről; csak a bastion hostról.

**Technológiai stack**

| Réteg | Választás | Indok |
| --- | --- | --- |
| UI | Kotlin, Jetpack Compose, Material 3 | Deklaratív UI, gyors iteráció a design rendszeren |
| Architektúra | MVVM, Kotlin Coroutines és Flow | Aszinkron DB hívások strukturált kezelése |
| SSH | Apache MINA SSHD vagy sshj | OpenSSH kulcsformátumok és modern cipherek támogatása |
| MySQL | MariaDB Connector/J | Kisebb és megengedőbb licencű, mint a MySQL Connector/J |
| Helyi tárolás | Room + SQLCipher | Titkosított kapcsolatprofilok és előzmények |
| Kulcstitkosítás | Android Keystore, AES-GCM | Hardveres kulcstároló, biometriához köthető |
| DI | Hilt | Standard, jól tesztelhető |

A JDBC hívások mindig háttérszálon futnak, dedikált `Dispatcher`-en, kapcsolatonként egy connection poollal (maximum 3 kapcsolat), hogy a tunnel ne terhelődjön túl.

## 5. SSH tunnel és kulcskezelés

Ez a spec legszigorúbb része. Az app nem tartalmaz jelszavas SSH hitelesítést, és nem tartalmaz kapcsolót a tunnel megkerülésére. Kulcs nélkül nem lehet kapcsolatprofilt menteni.

**Támogatott kulcsformátumok**

| Formátum | Támogatás | Megjegyzés |
| --- | --- | --- |
| OpenSSH (`-----BEGIN OPENSSH PRIVATE KEY-----`) | Kötelező | Ed25519 és RSA, ez az elsődleges cél |
| PKCS#8 | Támogatott | `ssh-keygen -m PKCS8` kimenet |
| PEM (régi OpenSSH) | Támogatott | RSA, olvasáskor konvertálva |
| PuTTY `.ppk` | Nem támogatott | Az importnál egyértelmű hibaüzenet és a konvertáló parancs |
| DSA kulcsok | Elutasítva | Elavult, a hibaüzenet ezt közli |

Algoritmusok: Ed25519 (ajánlott), ECDSA P-256/384, RSA legalább 2048 bit. Ennél gyengébb RSA kulcsot az app visszautasít.

**Kulcs importálása**

Három út vezet be egy kulcsot, mindhárom ugyanabba az importáló képernyőbe fut:

1. **Fájlból** – Storage Access Framework picker. Az app a fájl tartalmát beolvassa a memóriába, majd titkosítva elmenti; az eredeti fájlt nem másolja és nem mozgatja.
2. **Beillesztés** – szövegmező vágólapról. Beillesztés után az app azonnal törli a vágólapot, és ezt egy rövid üzenetben jelzi.
3. **Generálás az eszközön** – Ed25519 kulcspár készítése az appban. A publikus kulcsot a felhasználó megosztja vagy másolja, hogy felvegyék a bastion `authorized_keys` fájljába.

Importkor az app felismeri a kulcs típusát, kiírja a fingerprintet (SHA256), és nevet kér hozzá. Ha a kulcs passphrase-zel védett, a passphrase-t azonnal bekéri és ellenőrzi, hogy a kulcs valóban megnyílik-e.

**Tárolás**

A privát kulcs sosem kerül a fájlrendszerre nyers formában. Az Android Keystore generál egy eszközhöz kötött AES-256-GCM kulcsot (`setUserAuthenticationRequired(true)`), és ezzel titkosítva tároljuk a privát kulcsot a Room adatbázisban. A titkosított kulcs kiolvasása biometrikus vagy PIN-es feloldást kér. A dekódolt kulcs csak a tunnel felépítésének idejére él a memóriában, és `CharArray` / `ByteArray` formában tároljuk, hogy használat után nullázni lehessen.

**Host key ellenőrzés**

Első csatlakozáskor az app megmutatja a szerver host key SHA256 fingerprintjét, és megerősítést kér (trust on first use). Az elfogadott kulcsot elmenti. Ha a fingerprint később megváltozik, az app **blokkolja** a kapcsolatot, és csak a kapcsolatszerkesztőben, explicit felülírással lehet feloldani. Automatikus elfogadás nincs.

**A tunnel életciklusa**

```
Bontva → Feloldás (kapcsolat megnyitása)
Feloldás → Kapcsolódás (kulcs dekódolva)
Kapcsolódás → Aktív (tunnel + MySQL él)
Aktív → Szünetel (app háttérbe kerül)
Szünetel → Aktív (5 percen belül visszatér)
Szünetel → Bontva (időtúllépés)
Kapcsolódás → Hiba (auth vagy hálózati hiba)
Hiba → Bontva (újrapróbálás)
```

A tunnel egy foreground service-ben fut, állandó értesítéssel, amiről egy koppintással bontható. Háttérben legfeljebb 5 percig marad életben, utána automatikusan bomlik. Hálózatváltáskor (WiFi ↔ mobil) az app észleli a kapcsolat megszakadását, és felajánlja az újracsatlakozást; a dekódolt kulcs ilyenkor már nem él a memóriában, ezért újra feloldás kell.

## 6. Biztonsági követelmények

| Követelmény | Megvalósítás |
| --- | --- |
| Privát kulcs titkosítva | Android Keystore AES-256-GCM, eszközhöz kötve, exportálhatatlan |
| Biometrikus védelem | Kulcs feloldása biometriával vagy eszköz-PIN-nel, appindításkor és minden új tunnelnél |
| Nincs kulcs-export | Az app nem ad módot a privát kulcs kimentésére, sem megjelenítésére |
| Titkosított helyi DB | SQLCipher, kulcs a Keystore-ban |
| Host key pinning | Első elfogadás után változás esetén blokkolás |
| Képernyőtartalom védelme | `FLAG_SECURE` minden képernyőn: nincs screenshot, nincs recent apps előnézet |
| Vágólap higiénia | Beillesztett kulcs után azonnali törlés; másolt cellaérték 60 mp után törlődik |
| Automatikus zárolás | 5 perc inaktivitás után az app zárolódik, tunnel bomlik |
| Naplózás | Az app nem küld telemetriát; a hibanaplókban nincs lekérdezés-szöveg és nincs adat |
| Root-észlelés | Rootolt eszközön figyelmeztetés indításkor, a használat nem tiltott |

**MySQL oldali javaslatok**

Ezek nem az app részei, de a bevezetés feltételei:

- Külön MySQL felhasználó a mobil hozzáféréshez, `'user'@'bastion-host'` formában korlátozva.
- Induláskor csak `SELECT` jog; írásjog csak igény szerint, külön felhasználóval.
- `max_execution_time` beállítása szerver oldalon, hogy egy elszabadult lekérdezés ne terhelje a DB-t.
- A bastion `authorized_keys` bejegyzései `restrict,permitopen="db-host:3306"` opciókkal, hogy a kulcs csak port-forwardra legyen jó, shellre ne.
- Kulcsrotáció: a bastionon lejárati dátum vezetése, kilépő kollégánál a kulcs azonnali visszavonása.

## 7. Funkcionális specifikáció képernyőnként

### 7.1 Kapcsolatlista (indító képernyő)

Kártyák listája, kártyánként: kapcsolat neve, bastion host, cél adatbázis, állapotjelző pont (szürke bontva, sárga csatlakozik, zöld aktív) és az utolsó használat ideje. Alul lebegő gomb új kapcsolathoz. Hosszú nyomásra: szerkesztés, duplikálás, törlés.

Egy koppintás a kártyán elindítja a feloldást és a tunnelt, közben a kártya lépésenként mutatja, hol tart: kulcs feloldása → SSH kapcsolat → MySQL kézfogás. Ez azért fontos, mert négy különböző dolog romolhat el, és a felhasználónak tudnia kell, melyik.

### 7.2 Kapcsolatszerkesztő

Három szekció, felülről lefelé:

1. **Kapcsolat** – név, szín (a kártya és a fejléc jelölésére; éles adatbázisnál piros).
2. **SSH** – bastion host, port (alapértelmezés 22), SSH felhasználó, kulcs választása a kulcstárból. Kulcs nélkül a mentés gomb inaktív.
3. **MySQL** – DB host a bastion felől nézve (gyakran `localhost` vagy belső név), port, adatbázis neve, MySQL felhasználó és jelszó, `read-only` kapcsoló.

A szerkesztő alján **Kapcsolat tesztelése** gomb: felépíti a tunnelt, lefuttat egy `SELECT VERSION()`-t, és lépésenként visszajelez, majd bontja.

### 7.3 Séma-böngésző

Bal oldali panel (telefonon alsó lap): adatbázisok → táblák fa nézetben, kereső mezővel a tetején. Táblára koppintva megnyílik a tábla lapja három füllel:

- **Adatok** – az első 100 sor az eredményrácsban.
- **Szerkezet** – oszlopok listája: név, típus, nullable, alapértelmezés, kulcs jelölés. Idegen kulcsok érintésre a hivatkozott táblára ugranak.
- **DDL** – a `SHOW CREATE TABLE` kimenete, syntax highlighttal, másolás gombbal.

### 7.4 Query editor

Monospace szövegmező, syntax highlighttal. Felette a kapcsolat neve és a kiválasztott adatbázis. A billentyűzet felett egy vízszintesen görgethető gombsor a mobilon nehezen elérhető karakterekhez: `SELECT`, `FROM`, `WHERE`, `*`, `=`, `<`, `>`, `,`, `'`, `%`, `(`, `)`.

Automatikus kiegészítés tábla- és oszlopnevekre a betöltött sémából. Futtatás gomb, mellette a becsült sorlimit.

Minden lefuttatott lekérdezés bekerül az előzményekbe (időbélyeg, futásidő, sorok száma). Az előzményből egy koppintással visszatölthető vagy kedvencbe tehető. Kedvenceknél támogatott a `:paraméter` szintaxis: futtatáskor az app bekéri az értékeket egy kis űrlapon.

**Biztonsági viselkedés:** ha a lekérdezés nem tartalmaz `LIMIT`-et, az app automatikusan hozzáad egy `LIMIT 500`-at, és ezt egy halk felirat jelzi az eredmény fölött. Ez felülírható a lekérdezésben explicit `LIMIT` megadásával.

### 7.5 Eredményrács

Kétirányban görgethető táblázat, ragadós fejléccel és ragadós első oszloppal. Oszlopszélesség húzással állítható, dupla koppintásra tartalomhoz igazodik.

Cellák megjelenítése típus szerint: a `NULL` dőlt, halvány jelölés; a `BLOB` méretjelzés, nem tartalom; a hosszú szöveg egy sorban, levágva; a dátum a helyi formátumban. Számok jobbra igazítva, tabuláris számjegyekkel.

Egy cellára koppintva alul felugró panel mutatja a teljes értéket, másolás és (írásjog esetén) szerkesztés gombbal. Egy sorra hosszan nyomva megnyílik a **sor részletei** nézet: az összes oszlop egymás alatt, címke-érték párokban, ami széles tábláknál kezelhetőbb kézben.

Lapozás: görgetésnél automatikus utántöltés 200 soronként, az alján a betöltött és összes sorszám jelzésével.

### 7.6 Írási műveletek

Cella szerkesztésekor az app megkeresi a sor elsődleges kulcsát. Ha nincs elsődleges kulcs, a szerkesztés nem engedélyezett, és ezt egyértelműen közli.

Mentés előtt megjelenik a generált `UPDATE` utasítás teljes szövege, a `WHERE` feltétellel együtt. A megerősítés után az app tranzakcióban futtatja, és a művelet 10 másodpercig visszavonható egy alsó sávból (a visszavonás egy fordított `UPDATE`-et futtat a régi értékkel).

Sor törlése ugyanígy működik, de kétlépcsős megerősítéssel, és éles (pirosra állított) kapcsolaton a felhasználónak be kell gépelnie a tábla nevét.

### 7.7 Export és beállítások

Export az eredményrács menüjéből: CSV vagy JSON, a teljes találat vagy csak a betöltött sorok. A fájl a rendszer share sheet-jén megy tovább; az app nem tárol export fájlokat.

Beállítások: kulcstár kezelése (kulcsok listája fingerprinttel, hozzáadás, törlés), alapértelmezett sorlimit, automatikus zárolás ideje, téma (rendszer / világos / sötét), betűméret a rácsban.

## 8. UX/UI design rendszer

**Vizuális irány:** nyugodt, sötét alapú felület, ahol a színt kizárólag az információ hordozza — az állapot, a típus és a veszély. A háttér és a keretek visszafogottak, az adat a hangsúlyos elem. A referencia nem a tipikus adatbázis-kliens, hanem a modern fejlesztői eszközök letisztult felülete: sok levegő, kevés vonal, erős tipográfiai hierarchia.

Az alapértelmezett téma a **sötét**, mert a tipikus használat rossz fényviszonyok között, mozgás közben történik. A világos téma teljes értékű, nem utógondolat.

### Színpaletta

| Szerep | Sötét téma | Világos téma |
| --- | --- | --- |
| Háttér | `#0F1115` | `#FAFAFA` |
| Felület (kártya, panel) | `#181B21` | `#FFFFFF` |
| Emelt felület (bottom sheet) | `#20242C` | `#F2F3F5` |
| Elsődleges szöveg | `#E8EAED` | `#1A1C1F` |
| Másodlagos szöveg | `#9BA1AC` | `#5F6670` |
| Akcentus (elsődleges akció) | `#4D9FFF` | `#0A6ED1` |
| Siker, aktív kapcsolat | `#3DD68C` | `#1A9E5F` |
| Figyelmeztetés, csatlakozás | `#F5B14C` | `#B87400` |
| Hiba, destruktív | `#FF6B6B` | `#D32F2F` |
| Éles adatbázis jelölés | `#E5484D` | `#E5484D` |

A cellák típusszínezése szándékosan halvány: szám `#7FD1E8`, szöveg alapszín, `NULL` `#6B7280` dőlten, dátum `#C4A7F0`. Cél, hogy egy pillantásra olvasható legyen a típus, de a rács ne legyen tarka.

### Tipográfia

| Elem | Betű | Méret / vastagság |
| --- | --- | --- |
| Képernyőcím | Inter | 22sp / SemiBold |
| Szekciócím | Inter | 16sp / Medium |
| Törzsszöveg | Inter | 15sp / Regular |
| Másodlagos, felirat | Inter | 13sp / Regular |
| Rács cellák | JetBrains Mono | 13sp / Regular, tabuláris számjegyek |
| SQL editor | JetBrains Mono | 15sp / Regular |

A monospace betű minden adat- és kódfelületen kötelező: a rácsban így igazodnak egymás alá a számok, és így olvashatók az azonosítók.

### Térköz és forma

4pt-es alaprács. Szabványos térközök: 4, 8, 12, 16, 24, 32. Kártyák sarokkerekítése 16dp, gomboké 12dp, chipeké teljes. Árnyék helyett finom, 1dp-es keret `#FFFFFF10` színnel — sötét témán az árnyék nem olvasható, a keret igen.

Érintési célpont minimum 48×48dp. A rács celláinál ez alól kivétel a cella maga, de a sor teljes magassága eléri a 44dp-t.

### Kulcs komponensek

- **Kapcsolatkártya** – bal szélén 3dp-es színsáv a kapcsolat színével; éles adatbázisnál ez piros, és a fejléc is piros csíkot kap, amíg a kapcsolat él. Ez az egyetlen helyen sem elrejthető vizuális jelzés.
- **Állapotpont** – 8dp-es kör, finom pulzáló animációval csatlakozás közben. Szín nem elég jelzésnek: mellette mindig szöveg is áll (Aktív, Csatlakozik, Bontva), színvak felhasználók miatt.
- **Lépcsős csatlakozás-jelző** – négy pötty vízszintesen (kulcs, SSH, MySQL, séma), amelyik kész, kitöltött. Hibánál a hibás lépés pirosra vált, és alatta egy sorban a konkrét hibaüzenet.
- **Eredményrács** – zebracsíkozás nélkül; sorelválasztó egy 1dp-es, alig látható vonal. A fejléc `#20242C` háttérrel ragad a tetejére. Görgetéskor az első oszlop enyhe árnyékot kap a jobb szélén, hogy elváljon.
- **Bottom sheet cellához** – a teljes érték monospace-ben, felette az oszlop neve és típusa, alul Másolás és Szerkesztés gomb.
- **Megerősítő párbeszéd írásnál** – a generált SQL kódblokkban, a `WHERE` rész kiemelve. A megerősítő gomb az első 1 másodpercben inaktív, hogy ne lehessen véletlenül végignyomni.

### Mikrointerakciók és motion

Rövid, funkcionális animációk: 150–200 ms, `FastOutSlowIn` görbe. A lista- és lapváltások megosztott elemes átmenettel dolgoznak (a kapcsolatkártya színsávja átúszik a fejlécbe). Haptikus visszajelzés három helyen: sikeres tunnel, sikeres írási művelet, hiba.

Töltési állapotok skeleton-nal, nem pörgő körrel: a rács helyén szürke sávok jelennek meg, így a felület nem ugrik meg, amikor megérkeznek az adatok.

### Üres és hibaállapotok

Minden üres állapotnak van rövid magyarázó szövege és egy akciója: üres kapcsolatlistánál *Első kapcsolat létrehozása*, nulla találatnál *Szűrő törlése*, hiányzó kulcsnál *Kulcs importálása*. Nincs puszta „Nincs adat" felirat sehol.

### Akadálymentesség

Minden szöveg eléri a WCAG AA kontrasztot (4.5:1). A rendszer betűméret-beállítását a felület követi 200%-ig, a rács kivételével, ahol saját méretezés van. Minden ikonos gombnak van `contentDescription`-je, és a TalkBack a rácsban cellánként „oszlopnév, érték" formában olvas.

## 9. Helyi adatmodell

Minden az eszközön marad; az app nem küld adatot sehova.

| Entitás | Mezők | Titkosítás |
| --- | --- | --- |
| `SshKey` | id, név, típus, fingerprint, titkosított privát kulcs, publikus kulcs | Privát kulcs: Keystore AES-GCM |
| `Connection` | id, név, szín, bastion host/port/user, kulcs id, DB host/port/név, DB user, read-only flag | Teljes rekord SQLCipher alatt |
| `DbCredential` | kapcsolat id, titkosított MySQL jelszó | Keystore AES-GCM |
| `KnownHost` | bastion host, port, host key típus, fingerprint, első elfogadás ideje | SQLCipher |
| `QueryHistory` | id, kapcsolat id, SQL szöveg, időbélyeg, futásidő, sorok száma | SQLCipher, 30 nap után automatikus törlés |
| `SavedQuery` | id, név, SQL szöveg, paraméterek, kapcsolat id | SQLCipher |

**Amit az app soha nem tárol:** lekérdezési eredményt (csak memóriában, a session idejére), export fájlt, dekódolt privát kulcsot, passphrase-t.

Az app kilépésekor vagy zárolásakor az eredmény-cache ürül. Kapcsolat törlésekor a hozzá tartozó credential, előzmény és known host bejegyzés is törlődik.

## 10. Nem-funkcionális követelmények

| Terület | Követelmény |
| --- | --- |
| Android verzió | minSdk 28 (Android 9), targetSdk a mindenkori legfrissebb |
| Eszközök | Telefon elsődlegesen, tablet és álló/fekvő elrendezés támogatott |
| Tunnel felépítés | Átlagos hálózaton 3 másodperc alatt, a kulcs feloldásától számítva |
| Lekérdezés indítása | A futtatás gomb megnyomásától 100 ms-en belül vizuális visszajelzés |
| Eredmény renderelés | 500 sor × 20 oszlop akadásmentesen görgethető (60 fps) |
| Memória | 10 000 sor betöltése után sem lépi túl a 200 MB-ot; efelett lapozásra kényszerít |
| Hálózatváltás | WiFi ↔ mobil váltás észlelése 5 másodpercen belül, felajánlott újracsatlakozás |
| Akkumulátor | A foreground service a tunnel élettartamára korlátozódik, nincs ébresztés háttérben |
| Lokalizáció | Magyar és angol nyelv, a rendszer beállítása szerint |
| Offline | Kapcsolat nélkül a mentett lekérdezések és a séma-cache megtekinthető, futtatás nem |

## 11. Hibakezelés és edge case-ek

Minden hibaüzenet megnevezi, **melyik rétegben** történt a baj, és javasol egy konkrét lépést. A nyers stack trace sosem kerül a felhasználó elé, de egy „Részletek" gomb alatt megtekinthető és másolható.

| Helyzet | Viselkedés |
| --- | --- |
| Rossz vagy visszavont SSH kulcs | „SSH hitelesítés elutasítva. Ellenőrizd, hogy a kulcs benne van-e a bastion authorized_keys fájljában." |
| Megváltozott host key | Kapcsolat blokkolva, mindkét fingerprint kiírva, feloldás csak a szerkesztőben |
| Passphrase hibás | Újrapróbálás azonnal, 5 sikertelen kísérlet után 30 mp várakozás |
| Biometria nem elérhető | Visszaesés eszköz-PIN-re; ha az sincs beállítva, az app nem indul, és megmondja, miért |
| Bastion elérhetetlen | Timeout 10 mp után, „A bastion host nem válaszol" + újrapróbálás gomb |
| Tunnel él, de MySQL visszautasít | A MySQL hibakód és üzenet szó szerint, mellette a valószínű ok (jogosultság, rossz jelszó, nem létező DB) |
| Lekérdezés túl sokáig fut | 30 mp után megszakítás felajánlva; a megszakítás valódi `KILL QUERY`-t küld |
| Óriási eredmény | Automatikus `LIMIT`, és figyelmeztetés, ha a tábla több mint 100 000 soros |
| Elsődleges kulcs nélküli tábla | Olvasás működik, szerkesztés tiltva, magyarázattal |
| BLOB oszlop | A rácsban méret jelenik meg; a részletes nézetben szöveges előnézet az első 1 KB-ból |
| Hálózat megszakad lekérdezés közben | A művelet megszakad, az app nem próbálja automatikusan újra (írásnál ez veszélyes lenne) |
| Írási művelet félbeszakad | Tranzakció rollback, a felhasználó egyértelmű üzenetet kap, hogy a változás nem történt meg |
| Eszköz zárolása tunnel alatt | A tunnel él 5 percig, utána bomlik; visszatéréskor biometria |
| Karakterkészlet-eltérés | Az app `utf8mb4`-gyel csatlakozik; eltérő kollációjú oszlopnál nem konvertál, a nyers értéket mutatja |

## 12. Ütemterv és becslés

Egy tapasztalt Android fejlesztővel, teljes munkaidőben. A becslés tartalmazza a fejlesztést, az öntesztelést és az alap dokumentációt, de nem tartalmazza a külső biztonsági auditot.

| Fázis | Tartalom | Ember-nap |
| --- | --- | --- |
| 0. Előkészítés | Projekt váz, DI, design tokenek, navigáció | 2 |
| 1. Kulcskezelés | Import, generálás, Keystore titkosítás, biometria, kulcstár UI | 6 |
| 2. SSH tunnel | Port forward, host key kezelés, foreground service, életciklus | 5 |
| 3. Kapcsolatok | Lista, szerkesztő, teszt gomb, csatlakozás-jelző | 4 |
| 4. Séma-böngésző | Fa nézet, tábla lapok, DDL, idegen kulcs navigáció | 4 |
| 5. Query editor | Szerkesztő, highlight, billentyűsor, előzmény, kedvencek | 5 |
| 6. Eredményrács | Kétirányú görgetés, ragadós fejléc, típusformázás, lapozás, sor nézet | 7 |
| 7. Írási műveletek | PK felismerés, generált SQL, megerősítés, visszavonás | 5 |
| 8. Export és beállítások | CSV/JSON, share sheet, beállítás képernyő | 2 |
| 9. Csiszolás | Hibaállapotok, animációk, akadálymentesség, lokalizáció | 4 |
| 10. Tesztelés | Belsős béta, javítások | 3 |
| **Összesen** |  | **47 ember-nap** |

Ez naptárban körülbelül **9–10 hét** egy fejlesztővel, vagy **5–6 hét**, ha az 1–2. és a 4–6. fázis részben párhuzamosítható két fejlesztő között.

**Csökkentett változat:** ha a 7. fázis (írás) kimarad és az app read-only, a becslés **37 ember-napra**, körülbelül 7–8 hétre csökken. Ez jó első kiadás lehet, mert a kockázat is jelentősen kisebb.

A korábbi beszélgetésben említett 3–5 hét egy szűkebb scope-ra vonatkozott, kulcsgenerálás, host key kezelés, írási műveletek és teljes design rendszer nélkül. A kötelező SSH kulcskezelés és a kidolgozott UX önmagában körülbelül két hetet ad hozzá.

## 13. Kockázatok és nyitott kérdések

**Kockázatok**

| Kockázat | Hatás | Kezelés |
| --- | --- | --- |
| Kulcs kompromittálódik ellopott eszközzel | Magas | Keystore + biometria + `permitopen` a bastionon + gyors kulcsvisszavonási folyamat |
| Az eredményrács teljesítménye nagy táblákon | Közepes | Korai prototípus a 6. fázis elején, valós adaton mérve |
| SSH könyvtár és Android kompatibilitás | Közepes | Az 1. fázis első napján kulcsformátum-teszt mind a négy formátummal |
| Véletlen adatmódosítás éles DB-n | Magas | Read-only alapértelmezés, éles kapcsolat piros jelölése, tábla nevének begépelése törléskor |
| Scope csúszás desktop funkciók felé | Közepes | A 2. fejezet nem-céljai kötelezőek, minden bővítés külön döntés |

**Nyitott kérdések**

- Hány adatbázisra és hány felhasználóra kell készülni? Ez befolyásolja a séma-cache stratégiát.
- Van már bastion host, vagy azt is fel kell állítani? Ha nincs, az plusz 2–3 nap infra munka.
- A kulcsokat a felhasználók maguk generálják, vagy központilag kapják? Ha központilag, kell egy biztonságos átadási folyamat.
- Kell-e audit napló arról, ki mit futtatott? Ha igen, azt szerver oldalon érdemes megoldani, nem az appban.
- Terjesztés: kézi APK, MDM vagy Play Private App? Ez a frissítési folyamatot határozza meg.
- Az első kiadás legyen-e read-only? Ez 10 ember-napot és jelentős kockázatot spórol.
