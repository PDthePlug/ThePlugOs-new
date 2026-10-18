# Milestones, evidence and known limitations

Updated 18 October 2026. Status language: **implemented** = source exists, **simulated** = executed in a software test, **physically verified** = observed on named separate physical devices, **blocked** = required environment/evidence absent. A build does not confer release approval.

| Milestone | Implemented | Simulated | Physically verified | Remaining / blocked |
|---|---|---|---|---|
| 1 Local order loop | Native Android hub, cashier/kitchen workspaces, encrypted SQLite, TLS enrollment, separate delivery/human ack, Ready/Collected | SQLite and loopback HTTPS tests passed; see verification record | No | Separate-device WAN-off cycle and latency measurements |
| 2 Durability | Atomic command/receipt/event/outbox, input cache, exact retries, versions | Test suite covers DB restart, receipt-write failure, concurrent duplicate requests and stale commands | No | Process-kill/power-loss, network cut during commit, lost socket reply on actual Android |
| 3 Cloud sync | Durable local outbox only; cloud contract documented | No | No | Supabase receiver, transactional duplicate checks, checkpoints, RLS, owner reporting |
| 4 Shop operations | Immutable integer-cent order snapshots; local manager enrollment/cancel only | No payment/stock effects to test yet | No | Real payments, refunds, shifts, stock, recipes, manager dashboard, menu management |
| 5 Installation/hardening | Pilot setup, PINs, enrollment, certificate pinning and operating guide | Limited security/unit boundaries | No | Owner auth/recovery, signed offline grants, revocation UX, portable backup/restore, replacement, migrations, upgrades, hardware acceptance |

## Verification record
See [the executed verification record](VERIFICATION.md) and `docs/evidence/` for test results, lint findings and APK digest. Source-only test definitions do not count as passing tests. Physical Android Keystore/TLS instrumentation is separate from Robolectric, whose tests inject a JCE-backed vault while exercising the same SQLite ledger.

## Known limitations
- All orders are training/unpaid; catalog is a fixed explicitly labelled three-product training fixture. There is no sale/payment ledger or stock effect. No demo values masquerade as real reporting.
- Cloud state always says not configured and events remain pending locally. No deployed owner portal or cloud schema, no service-role secrets in clients.
- All accepted orders are sent in a bounded snapshot (2 MiB client limit); history pagination and delivery batch pagination are needed before large histories. This may block polling at scale; no silent data loss is claimed.
- Foreground client polling target interval 750 ms is not measured p95 delivery latency. No physical throughput or latency claim.
- Android OEM background killing, Doze, reboot, storage exhaustion and TLS/Keystore providers require hardware checks. Hub reopen after full reboot is manual. Locked kitchen does not receive orders in background.
- M1 permits local offline access without cloud expiry/refresh. This is unsuitable for production revocation requirements. Absolute sessions are 15 minutes and backgrounding locks the UI.
- Device secret acts as enrollment credential, not hardware attestation. No terminal public-key possession protocol or hardware-bound cloud admission yet. Full certificate fingerprint comparison is manual; typo is rejected, bypass unavailable.
- Manager enrollment actions are audited; received cancellation requires actor/reason. PIN reset, staff CRUD, remote changes and revocation control UI are deferred.
- No portable encrypted backup/restore, cloud recovery, replacement authority transfer, printer support or card-provider integration. No guarantee of recovery after device loss/reset.
- No commercial production data used or migrated. Original V9/V9 Studio repositories and Supabase projects untouched.
