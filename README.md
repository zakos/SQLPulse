# SQLPulse

Internal Android MySQL client. Every connection runs over an SSH tunnel authenticated with an
OpenSSH private key — there is no password SSH login and no direct MySQL mode, not even behind a
developer flag.

The functional and UX specification lives in [`docs/specification.md`](docs/specification.md);
section references in the code (`§5`, `§8`, …) point at it.

## What is implemented

Phases 0–2 of the roadmap in §12, plus enough of phase 3 to exercise a tunnel end to end:

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
| Schema browser, query editor, result grid, writes, export | not started (phases 4–8) |

The connection test verifies the far side really is MySQL by reading the server's initial
handshake packet (`MysqlProbe`); running `SELECT VERSION()` over JDBC arrives with the query phase.

## Layout

```
app/src/main/java/hu/laurel/sqlpulse/
  data/crypto/     Keystore wrapping, sealed blobs, SQLCipher passphrase
  data/db/         Room entities and DAOs (§9)
  data/keys/       Key parsing, validation, fingerprints, key store repository
  data/connection/ Connection profiles and MySQL credentials
  security/        BiometricPrompt around keystore ciphers
  ssh/             Tunnel state machine, host key pinning, port forward, foreground service
  ui/              Compose screens, design system
```

## Building

Standard Android build: `./gradlew assembleDebug` with an Android SDK (compileSdk 35, minSdk 28).

> **Not yet compiled.** The environment this scaffold was written in has no Android SDK and no
> network access to `dl.google.com`, so nothing here has been through a compiler. Expect to fix
> dependency versions and small API mismatches on the first real build.

## Security notes

- Private keys are sealed with an Android Keystore key that requires user authentication for
  every single use, and are never exported or displayed.
- The SQLCipher passphrase is 32 random bytes, sealed with a separate device-bound keystore key.
- `FLAG_SECURE` is set on the whole app: no screenshots, no recents preview.
- A changed bastion host key blocks the connection; unblocking is explicit, in the editor.
- The tunnel lives in a foreground service and drops after five minutes in the background.
