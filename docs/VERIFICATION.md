# Executed verification — 18 October 2026

The local pilot has build and simulated test evidence. **Physical acceptance is blocked: `adb devices -l` returned no attached devices.** No emulator or physical APK execution is claimed.

## Executed checks
- JDK 17.0.20, Gradle 8.9, compile/target SDK 35, minimum SDK 26.
- `testDebugUnitTest`: PASS, 30 tests, zero failures/errors/skips, on final application source.
- `lintDebug`: PASS, zero errors, 11 warnings (reviewed below).
- `assembleDebug`: PASS, installable training APK produced.
- `assembleDebugAndroidTest`: PASS, two Android boundary test methods compiled and packaged; NOT EXECUTED.
- `apksigner verify --verbose`: PASS, APK signature scheme v2 verified, one debug signer.
- APK badging: `com.theplugos.local.pilot`, version `0.1.0-training`, min 26 / target 35.
- `git diff --check`: PASS.

| Suite | Tests | Failures | Runtime |
|---|---:|---:|---:|
| LedgerTest | 26 | 0 | 35.293 s |
| TransportTest | 4 | 0 | 5.083 s |

LedgerTest uses Robolectric Android 9/API 28, native SQLite and an injected JCE AES-GCM vault. It exercises the actual ledger, migration and terminal AtomicFile implementation, not an in-memory business model. TransportTest runs actual local TLS sockets and independent cashier/kitchen device credentials against that ledger. Its fixture private key/password in test resources are deliberately public test-only data and never shipped in the application APK. The real AndroidKeyStore provider and Android TLS key manager remain unverified until the instrumentation APK runs on Android.

Covered behaviours: complete order loop, separate device delivery/human acknowledgement, durable Ready state, exact command retry, concurrent duplicate submissions, duplicate delivery, lost response, DB close/reopen, failure during receipt insertion rolling back the whole command, stale/invalid transitions, role/device/session restrictions, persisted PIN lock, revoked/expired credentials, enrollment code consumption/expiry, separate branch rejection, hub-owned price snapshots, encrypted payloads/tag tampering, saved drafts/pending commands and interrupted AtomicFile backup-rename recovery. Network tests also reject a wrong certificate pin, chunked requests and oversized bodies. These tests do not exercise payments, stock or cloud ingestion because those modules are not yet implemented.

## Lint review
- `CustomX509TrustManager`: 1 warning(s).
- `SetTextI18n`: 9 warning(s).
- `WakelockTimeout`: 1 warning(s).

The custom trust manager enforces the exact pre-compared SHA-256 certificate fingerprint and certificate validity; it rejects a wrong pin in the executed TLS test. It intentionally uses private local certificates rather than public CA trust. Continuous wake-lock lifetime is tied to the visible hub foreground service and released on service destruction; battery/OEM behaviour still requires measurement. English UI text needs resource extraction for localization. Warnings remain visible; no lint baseline or suppressed security checks hide them. Explicit cloud/device-transfer exclusions prevent intentional use of Android automatic transfer as a branch backup/replacement mechanism.

## Artifact identity
Application APK SHA-256:

`48f308343c28467e7d3644b1058685342b2fd1659cc8a18ad7d68334a96fbb39`

This debug APK is for the isolated training branch only. CI can reproduce the build but may use a different debug signing key and APK digest. No production release is asserted.

## Unperformed acceptance
- Two separate physical devices; WAN-off real Wi-Fi order loop and p95 delivery/ready latency.
- Physical key storage, OEM screen lock/background/reboot, power loss, disk-full recovery.
- Actual payment/stock effects, cash reconciliation and multi-business cloud RLS.
- Extended offline cloud upload/reconciliation and accurate remote owner timestamps.
- Portable backup/restore and fenced hub replacement.

p95 target: <=2 seconds under the conditions in ARCHITECTURE.md. Measured physical p95: **not available**. No simulator result is substituted.
