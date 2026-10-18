# ThePlugOS clean rebuild — architecture decision, 18 October 2026

Status: Milestone 1 training pilot; no authorization to use historical production data.

## Product and scope
One branch authority on an Android cashier hub coordinates enrolled Android cashier and kitchen terminals on a private LAN. The first complete slice is order submission, device delivery, human acknowledgement, preparation, readiness and collection. Kitchen preparation and financial settlement are independent. Manager and remote owner workspaces follow in the specified milestone order. The branch continues without WAN; a disconnected terminal cannot promise acceptance or kitchen delivery.

## One coherent architecture
- Native Java Android application, three installation modes: hub/cashier, additional cashier, kitchen. Native touch UI avoids a web/native authority boundary for this first slice.
- Android SQLite with FULL synchronous durability and a single serialized command transaction. Sensitive payloads, credentials, drafts and command receipts encrypted with AES-256-GCM; key held in Android Keystore. Structural IDs, statuses, timestamps, role names and sequence positions are clear metadata. This is field encryption, not full database encryption. Device OS file encryption and lock screen remain required.
- HTTPS on port 8443, certificate public-key trust established by comparing the full SHA-256 certificate fingerprint on both devices. No trust-on-first-use. Pairing is explicitly initiated on the hub with a short-lived, single-use, role-bound random code. Device bearer secrets are 256 random bits and transmitted only through pinned TLS. No internet service in the local request path.
- A 750 ms authenticated snapshot poll reconciles authoritative state and sequence after gaps. A device explicitly confirms delivery only after durably writing the snapshot. Human acknowledgement is a separate command. Snapshots include collected orders in this small pilot; pagination/checkpoint delta compaction is required before large branch histories.
- Hub transactions atomically persist order state, immutable event/outbox and command receipt. UUID command identity is bound to device, staff and exact request bytes; same ID/different payload is rejected. Per-order versions prevent stale transitions. HTTP responses are returned only after commit.
- Supabase is reserved for Milestone 3: authenticated idempotent ingestion of branch-sequenced events and owner-authenticated RLS-scoped reporting. Owner web UI never accepts competing live transaction writes. No cloud connection or migration is created in Milestone 1.

## Local operating conditions
| Condition | Behaviour |
|---|---|
| LAN + internet | Local loop works. M1 reports cloud sync as not configured, never synchronized. |
| LAN only | Identical local loop; event outbox retained. |
| Terminal loses hub | Last snapshot labelled stale; saved input and one stable pending command retained; explicit retry required. No automatic new command ID after timeout. |
| Kitchen unreachable | Hub still saves orders; cashier sees no current kitchen connection and delivery pending. Device delivery does not mean a person read it. |
| Hub down | All confirmations blocked, input remains unconfirmed. Open the original hub and resume. Never initialize a replacement against that same live branch while old hub may return. |

## Reversible defaults and open business decisions
Defaults for M1 only: isolated training branch, ZAR integer cents, illustrative menu explicitly labelled training data, no payment recording and no stock effect, no real sales reports. Orders can enter the kitchen unpaid in this training pilot. Customer numbers are monotonic branch-local six-digit numbers; immutable UUID is the integration identity.

Before M4 real trading: confirm pay-before-preparation versus permitted unpaid orders, VAT registration/inclusive pricing, business-day cutoff (proposal 04:00 Africa/Johannesburg), who can approve refunds/discounts and limits, recipe units/yields, and insufficient-stock policy. None is silently enacted in the pilot.
Before physical rollout: confirm actual Android models/versions, router/hotspot and printer models. Proposed baseline Android 8/API 26+, Android 11+ recommended, 2 GB RAM minimum, 4 GB preferred, stable charger and dedicated Wi-Fi router without client isolation. Android background behaviour must be measured per model.
No data migration by default. Historic business/staff/menu import requires a reviewed mapping and fresh backup.

## First milestone acceptance
On hub/cashier plus separate kitchen, disable router WAN while retaining Wi-Fi. Submit two-item order with modifier and note; observe saved status, device delivery, then explicit kitchen acknowledgement. Start preparation, mark ready, observe persistent cashier Ready indicator, collect. Restart both apps and confirm unfinished work. Disconnect kitchen and submit; confirm delivery failure/pending is visible. Retry exact submit after a dropped response and confirm one order, one number and one creation event. Run stale and unauthorized transitions and expect rejection.
Target p95 <=2 seconds for submission-to-kitchen-durable-snapshot and ready-commit-to-cashier-display: >=100 samples each, two physical devices, dedicated same-subnet Wi-Fi, RSSI >=-65 dBm, <=10 clients, hub plugged in, foreground apps, WAN disabled. Record models, OS, timestamps and raw sample data. Report delivery and human acknowledgement separately. Simulator timing is not hardware evidence.

## Historical evidence inspected (read-only)
- PDthePlug/ThePlugOs-v9studio README.md, blob 242ee32ded3e12e55f707ce44b9819145fc7c3c7.
- PDthePlug/ThePlugOs-v9 README.md, blob 8259790df517bb3be3c0ca84ce5e251d91a7100e.
- V9 Studio docs/implementation/ANDROID_CASHIER_HUB_BUILD_AND_SECURITY.md, blob 29003de190a5ec86ec2bf953e4068f3073a5ae2e.
Their explicit HOLD, native authority and physical verification gaps justify proving a smaller native loop first. SQLCipher, signed cloud leases and broad transport discovery are valuable prior designs, not copied dependencies. This is a scoped reference inspection, not a forensic audit of either repository. Historical release instructions apply to those repositories; no mutation is made to them.

## Later contracts and ownership
Branch hub exclusively sequences orders, payment/refund ledger, stock movements and shift cash entries. Cloud ingestion keys (business_id, branch_id, authority_epoch, event_id) and (branch_id, authority_epoch, sequence) must be unique within a transactional receiver. Same identifier/different content is quarantined. Checkpoints advance only after durable cloud acknowledgement; exponential jittered retry capped at 5 minutes; extended outage reconciliation compares sequence ranges and content hashes. Never use last-write-wins for these records.
Owner changes to menu, price, staff and configuration become versioned proposals with base_version, proposal_id and actor. Hub validates expected version and permissions, atomically accepts/rejects, and reports application status. Sales snapshot immutable descriptions, modifiers and unit cents. Currency/tax snapshots versioned. Remote reporting displays last confirmed sequence/time and stale condition.
M4 default proposal for review: reserve ingredients at accepted order, consume on Preparing; release only unprepared reservations on cancellation; prepared items become explicit waste or recovered-stock adjustment with reason. Direct drinks consume on fulfilment. Payments append, refunds append linked compensating entries, never mutate settled totals. Each payment_method=external_card confirmation requires staff attestation of external terminal success. Shift expected cash = opening float + cash payments - cash refunds + approved cash movements; count and variance retained, not overwritten. Sales, cash receipts and estimated gross margin are distinct measures; margin requires recipe/cost snapshots and does not claim profit.
M5 production authority replacement requires physically fencing old hub plus cloud epoch/lease handover, encrypted portable backup and recovery rehearsal. Unsynced/unbacked transactions on a lost hub cannot be recovered. No automatic hub failover.

## Platform references consulted
- Android foreground service type requirements: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android cryptography recommendations: https://developer.android.com/privacy-and-security/cryptography
- Android Keystore boundary: https://developer.android.com/privacy-and-security/keystore
