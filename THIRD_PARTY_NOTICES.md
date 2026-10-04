# Third-party notices

SQLPulse is licensed under the [Apache License 2.0](LICENSE). The release APK bundles the
components below, each under its own license. Test-only libraries (JUnit, MockK, Paparazzi,
Espresso, coroutines-test) are not shipped and are not listed.

## Runtime libraries

| Component | Version | License |
|---|---|---|
| AndroidX (Core, Lifecycle, Activity, Compose, Material 3, Navigation, Room, SQLite, Biometric, DataStore, Hilt Navigation) | see `gradle/libs.versions.toml` | Apache-2.0 |
| Dagger Hilt | 2.52 | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | 2.0.21 / 1.9.0 | Apache-2.0 |
| SQLCipher for Android (`net.zetetic:sqlcipher-android`) | 4.6.1 | BSD-style (Zetetic LLC) |
| sshj (`com.hierynomus:sshj`) | 0.38.0 | Apache-2.0 |
| Bouncy Castle (`bcprov`, `bcpkix`) | 1.78.1 | MIT-style (Bouncy Castle License) |
| EdDSA-Java (`net.i2p.crypto:eddsa`) | 0.3.0 | CC0-1.0 |
| SLF4J NOP binding | 2.0.16 | MIT |
| MariaDB Connector/J | 3.4.1 | LGPL-2.1 |
| MySQL Connector/J | 5.1.49 | GPL-2.0 with the Universal FOSS Exception |
| PostgreSQL JDBC Driver (pgjdbc) | 42.7.13 | BSD-2-Clause |
| Microsoft JDBC Driver for SQL Server (`mssql-jdbc`) | 13.6.0.jre8 | MIT |
| SQLite JDBC (`org.xerial:sqlite-jdbc`), including SQLite | 3.53.4.0 | Apache-2.0 (SQLite itself: public domain) |

## Fonts

| Font | License | Text |
|---|---|---|
| Inter | SIL Open Font License 1.1 | [`app/src/main/assets/licenses/Inter-OFL.txt`](app/src/main/assets/licenses/Inter-OFL.txt) |
| JetBrains Mono | SIL Open Font License 1.1 | [`app/src/main/assets/licenses/JetBrainsMono-OFL.txt`](app/src/main/assets/licenses/JetBrainsMono-OFL.txt) |

## Notes

- **MariaDB Connector/J (LGPL-2.1)** is included unmodified as a separate library; its source is
  available from https://github.com/mariadb-corporation/mariadb-connector-j.
- **MySQL Connector/J 5.1 (GPL-2.0)** is used only for servers older than MySQL 5.5.3. Oracle's
  Universal FOSS Exception permits distributing it together with this Apache-2.0 licensed
  application. Its source is available from https://github.com/mysql/mysql-connector-j (5.1 branch).
- Ideas and algorithms were ported from **t8y2/dbx** (Apache-2.0); no code was copied verbatim.
  See [`docs/dbx-elemzes.md`](docs/dbx-elemzes.md).
