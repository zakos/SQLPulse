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
| Optional SSH: per-connection switch, direct connections allowed | done |
| Column sorting, quick column filter, EXPLAIN, running queries with KILL QUERY | done |

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

The build is green in CI: unit tests, `assembleDebug` and Android Lint all pass, and the debug
APK is produced as an artifact. This development environment has no Android SDK and no network
access to `dl.google.com`, so CI is where the app is actually compiled.

Nothing has been run on a device or against a real MySQL server yet, so the tunnel, the key
handling and the JDBC layer are compiled and unit-tested but not yet exercised end to end.

## CI

`.github/workflows/build.yml` runs unit tests, `assembleDebug` and Android Lint on a runner that
has the Android SDK. It starts by itself only for pushes to `main`; on any other branch, start it
from the Actions tab with "Run workflow". The debug APK and the test and lint
reports are uploaded as artifacts, the reports even when the build is red.

## Notes on two design decisions

**How a connection reaches the database.** Two shapes, chosen per connection with a switch in the
editor:

- *Through an SSH host.* The app opens an SSH connection to a machine that can reach the database
  and forwards a local port through it; the JDBC driver talks to `127.0.0.1`. This is the shape the
  specification assumes (§4), where the MySQL port is not reachable from the internet at all. The
  SSH host can be a dedicated jump host or the database server itself, as long as it runs sshd.
- *Directly.* The phone dials MySQL itself. §2 originally ruled this out; the app's owner asked for
  it, so it is a switch rather than an argument. It only works where the database port is reachable
  from the phone's current network, and the editor says so next to the switch.

The interface names the field for what it is: an SSH host.

**Importing a private key shows a public key.** Nothing is generated: the public half is
mathematically contained in the private key, so the app derives it and shows it for comparison
with the entry in `authorized_keys`. Only "Generate on device" creates a new pair.

## Security notes

- Private keys are sealed with an Android Keystore key that requires user authentication for
  every single use, and are never exported or displayed.
- The SQLCipher passphrase is 32 random bytes, sealed with a separate device-bound keystore key.
- `FLAG_SECURE` is set on the whole app: no screenshots, no recents preview.
- A changed SSH host fingerprint blocks the connection; unblocking is explicit, in the editor.
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
