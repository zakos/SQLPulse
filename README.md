# SQLPulse

Internal Android MySQL client. Every connection runs over an SSH tunnel authenticated with an
OpenSSH private key — there is no password SSH login and no direct MySQL mode, not even behind a
developer flag.

The functional and UX specification lives in [`docs/specification.md`](docs/specification.md);
section references in the code (`§5`, `§8`, …) point at it.

## What is implemented

All phases of the roadmap in §12 (0–9), with the caveat about compilation below:

| Area | State |
| --- | --- |
| Project skeleton, Hilt DI, navigation | done |
| Design tokens (colours, typography, spacing, motion) from §8 | done |
| Room + SQLCipher local model from §9 | done |
| Keystore wrapping (AES-256-GCM, per-use biometric unlock) | done |
| Key import (OpenSSH v1, PKCS#8, legacy PEM), .ppk and DSA refusal, RSA ≥ 2048 check | done |
| On-device Ed25519 key generation, public key export | done |
| SSH tunnel with local port forward, host key TOFU + pinning, foreground service | done |
| Connection list and editor, connection test | done |
| JDBC over the tunnel (MariaDB Connector/J, pool of 3, read-only enforcement) | done |
| Schema browser: databases, tables, columns, indexes, foreign keys, DDL | done |
| Query editor: highlighting, key row, completion, history, favourites, :parameters | done |
| Result grid: sticky header and first column, draggable widths, scroll paging, row detail | done |
| Row editing: primary key detection, generated SQL, confirmation, undo | done |
| Export to CSV/JSON through the share sheet, settings, automatic lock | done |

The connection test verifies the far side really is MySQL by reading the server's initial
handshake packet (`MysqlProbe`), which also drives the MySQL step of the connection indicator.

Not implemented, because §2 rules them out: DDL, bulk operations, user administration, offline
sync, and anything that would reach MySQL without the tunnel.

## Layout

```
app/src/main/java/hu/laurel/sqlpulse/
  data/crypto/     Keystore wrapping, sealed blobs, SQLCipher passphrase
  data/db/         Room entities and DAOs (§9)
  data/keys/       Key parsing, validation, fingerprints, key store repository
  data/connection/ Connection profiles and MySQL credentials
  data/sql/        JDBC pool over the tunnel, statement guards, highlighter, execution
  data/query/      Query history and favourites
  data/export/     CSV and JSON serialisation, share-sheet handoff
  data/settings/   Preferences (row limit, auto-lock, theme, grid font)
  data/schema/     information_schema reads for the browser
  security/        BiometricPrompt around keystore ciphers
  ssh/             Tunnel state machine, host key pinning, port forward, foreground service
  ui/              Compose screens, design system
```

## Building

Standard Android build: `./gradlew assembleDebug` with an Android SDK (compileSdk 35, minSdk 28).

> **Not yet compiled as an app.** The environment this was written in has no Android SDK and no
> network access to `dl.google.com`, so the Android build has never run. Expect to fix dependency
> versions and small API mismatches on the first real build.
>
> The parts that do not depend on Android — `SqlGuards`, `SqlHighlighter`, `RowSqlBuilder`,
> `ResultSerializer`, `ResultTable`, `Sealed` — were compiled with a standalone Kotlin compiler
> and their 59 unit tests pass. `ConnectionFormTest` needs the Android toolchain and has not run.

## Security notes

- Private keys are sealed with an Android Keystore key that requires user authentication for
  every single use, and are never exported or displayed.
- The SQLCipher passphrase is 32 random bytes, sealed with a separate device-bound keystore key.
- `FLAG_SECURE` is set on the whole app: no screenshots, no recents preview.
- A changed bastion host key blocks the connection; unblocking is explicit, in the editor.
- The tunnel lives in a foreground service and drops after five minutes in the background.
- JDBC connects with `allowLocalInfile=false`, so a hostile server cannot ask the client for
  local files, and with `autoReconnect=false`, so a dropped connection is never retried silently.
- Named `:parameters` are rewritten into JDBC placeholders and bound, never pasted into the SQL.
- MySQL passwords use a separate keystore key with a 30-second authentication window, so opening
  a connection prompts once rather than twice. Private keys stay bound per use.
- Row edits run in a transaction and roll back unless exactly one row changed; the confirmation
  shows the statement with its WHERE clause and cannot be tapped through for the first second.
- Deleting a row on a production connection requires typing the table name.
- Exports exist only as a cache file handed to the share sheet, wiped on the next start and on
  every lock. The app locks itself after the configured idle time, dropping the tunnel with it.
