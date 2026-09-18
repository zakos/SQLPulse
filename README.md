# SQLPulse

Internal Android MySQL client. A connection reaches the database through an SSH tunnel or
directly, and the MySQL connection itself can be plain or TLS-protected with a verified
certificate. The SSH host is entered with an OpenSSH key or, where the host allows nothing else,
a password.

The functional and UX specification lives in [`docs/specification.md`](docs/specification.md);
section references in the code (`§5`, `§8`, …) point at it. What exists and what is still missing
is in [`docs/roadmap.md`](docs/roadmap.md).

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
| TLS for the MySQL connection (four modes, CA import), whole-table write guard | done |
| Server errors classified into what to do about them, with the server's own wording kept | done |
| Views, routines, triggers and events in the browser; engine and size per table | done |
| SSH password authentication beside key authentication | done |

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

## CI and installing the app

Two workflows:

- `.github/workflows/check.yml` — every commit on a branch and every pull request: unit tests and
  Android Lint, no APK. This is the quick feedback loop.
- `.github/workflows/build.yml` — pushes to `main`, which is what merging a pull request does, plus
  manual runs from the Actions tab. Same checks, and it uploads the installable debug APK as the
  artifact `sqlpulse-debug-<run number>`.

**Installing an update.** Download the artifact, unzip it, open the APK on the phone. It installs
over the existing app and keeps its data, because every build is signed with the same key and the
version code is the CI run number, so a newer artifact is never a downgrade.

**Signing setup (once).** The key lives in repository secrets, never in the repository — read
access to the code must not be enough to build an update for an installed app. Create a key and
upload it:

On macOS or Linux:

```sh
keytool -genkeypair -v -keystore sqlpulse.keystore -alias sqlpulse \
  -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=SQLPulse, O=<your org>, C=HU"

gh secret set SIGNING_KEYSTORE_BASE64 --repo <owner>/SQLPulse < <(base64 -w0 sqlpulse.keystore)
gh secret set SIGNING_KEYSTORE_PASSWORD --repo <owner>/SQLPulse   # the store password you chose
gh secret set SIGNING_KEY_ALIAS --repo <owner>/SQLPulse           # sqlpulse
gh secret set SIGNING_KEY_PASSWORD --repo <owner>/SQLPulse        # the key password you chose
```

On Windows, in **PowerShell** (not `cmd`, which has neither `\` continuations nor `<(...)`).
`keytool` comes with the JDK; Android Studio ships one, so give the full path if it is not on
`PATH`:

```powershell
$keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"

& $keytool -genkeypair -v -keystore sqlpulse.keystore -alias sqlpulse `
  -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=SQLPulse, O=<your org>, C=HU"

# One line of base64, no wrapping and no trailing newline.
[Convert]::ToBase64String([IO.File]::ReadAllBytes("sqlpulse.keystore")) |
  Out-File -Encoding ascii -NoNewline keystore.b64

gh secret set SIGNING_KEYSTORE_BASE64 --repo <owner>/SQLPulse < keystore.b64
gh secret set SIGNING_KEYSTORE_PASSWORD --repo <owner>/SQLPulse   # the store password you chose
gh secret set SIGNING_KEY_ALIAS --repo <owner>/SQLPulse           # sqlpulse
gh secret set SIGNING_KEY_PASSWORD --repo <owner>/SQLPulse        # the key password you chose

Remove-Item keystore.b64
```

`--repo` is what lets these run from any directory; without it `gh` looks for a git checkout in the
current one. The three password and alias secrets are typed at the prompt — do not put them on the
command line, where they would land in the shell history.

Keep `sqlpulse.keystore` somewhere safe and out of the repository (`*.keystore` is gitignored).
Losing it means the next build cannot update installed copies — they would have to be uninstalled
first. The build fails with a clear message if the secrets are missing, rather than producing an
APK signed with a throwaway key.

To build locally with the same key, export the same four values:
`SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Without them the build falls back to the Android SDK's per-machine debug key, which is fine for
your own device and useless for distributing.

> One-off: an app installed from an earlier build carries a different signature, and Android cannot
> replace it. Uninstall that one first. Every build from here on updates in place.

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
