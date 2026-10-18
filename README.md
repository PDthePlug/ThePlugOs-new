# ThePlugOS — clean Android rebuild

**Local training pilot. Not ready for real trading.**

One Android branch hub coordinates cashier and kitchen devices on local Wi-Fi without internet. This first slice implements durable order submission, kitchen device delivery receipts, human acknowledgement, preparation, persistent Ready state, and customer collection. Cashier drafts and pending commands survive app restarts. No historical repository or production database was modified.

Start with [architecture and milestone acceptance](docs/ARCHITECTURE.md), then [installation / operating instructions](docs/OPERATIONS.md). [Contracts and permissions](docs/CONTRACTS.md) specify the API and authority boundaries. [Milestones and evidence](docs/STATUS.md) distinguish source, simulation and physical verification.

## Structure
- `app/src/main/java/com/theplugos/local/`: native touch workspaces, hub service/TLS transport, encrypted storage, command validation and ledger.
- `app/src/main/assets/001_initial.sql`: SQLite v1 migration/schema.
- `app/src/test/`: recovery, concurrency, authorization, retry and SQLite transaction tests.
- `app/src/androidTest/`: Android Keystore and pinned TLS boundary checks, requiring Android execution.
- `docs/`: architecture, contracts, permissions, setup, recovery limits, evidence and milestone tracker.
- `.github/workflows/android.yml`: repeatable build, lint and simulated unit checks.

## Build
JDK 17 and Android SDK 35. Set `ANDROID_HOME` or ignored `local.properties`.

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Install debug APK on two separate Android API 26+ devices, initialize **one** training hub, enroll kitchen through its full certificate fingerprint and one-time code, and follow the operating guide. Milestone 1 is not accepted until physical devices pass the internet-disconnected order loop. Supabase sync, owner portal, payment ledger, stock, recipes, shift cash reconciliation and portable backup are later milestones. No real financial totals or fake live owner metrics are shown.
