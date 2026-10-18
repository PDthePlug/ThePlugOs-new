# Local contracts and permissions — v1

All requests: POST HTTPS :8443, application/json, maximum 32 KiB; exact pinned hub certificate, no redirects. Device secret in X-Device-Token; session in X-Staff-Session. No browser CORS or cleartext server. Credentials never logged. HTTP 200 means result committed (or exact prior result returned); a timeout means unknown, retain and retry the same command bytes. 400 invalid input, 401 enrollment/session failure, 403 permission failure, 404 missing object, 409 conflict, 429 PIN lock; 500 must retain pending command. Server errors never imply rollback at the terminal.

## Endpoints
| Path | Body | Result / effect |
|---|---|---|
| /v1/pair | code | Single-use role-bound device admission; 5 minute code expiry |
| /v1/login | staffId, pin | Random session bound to staff and device, expires after 15 minutes |
| /v1/logout | {} | Invalidates this session |
| /v1/pair-code | role | Hub manager only, cashier/kitchen code |
| /v1/commands | commandId, type, orderId, payload below | Order snapshot after atomic commit |
| /v1/snapshot | {} | All order snapshots, current event sequence, menu, branch, time, kitchen last-seen status, pending cloud count |
| /v1/delivered | orderIds[] | Idempotent durable device delivery receipt; kitchen sends only after storing snapshot |

Submit fields: items[{productId,quantity,modifier}], note. Hub ignores supplied prices/names and resolves a catalog snapshot. Allowed training modifiers are Standard, No salt, Extra crispy; all free. Order quantity 1–99 per line, maximum 50 lines, notes <=500 chars. Integer cents only. Accepted order contains stable UUID, monotonic display number, version, status, immutable item/price/description/modifier snapshots, total, unpaid_training, timestamps, staff acknowledgement fields. There is no payment transaction in M1.

Transition fields: expectedVersion; cancellation also reason. Flow:
Received with no human ack → acknowledge (still Received, version increments) → prepare (Preparing) → ready (Ready) → collect (Collected).
Only received orders may be cancelled, by manager with reason. Prepared cancellation/correction is rejected pending waste/refund workflows. Corrections to a received order require manager cancellation plus a new order, retaining the original. Collected and Cancelled are terminal. Acknowledgement requires a delivery receipt from the same kitchen device.

The command receipt key is global within this hub database. Exact bytes, device ID and staff ID must match to replay. Re-authentication by the same staff/device can retry after restart. Retrying via a different device is rejected; no automatic transfer of pending commands. A second command ID for an already-used order ID is rejected. Technical device delivery is independent of staff acknowledgement and current connectivity. Lost delivery responses are safe to repeat.

## Permission matrix (implemented local pilot)
| Action | Cashier | Kitchen | Manager |
|---|---|---|---|
| Read same-branch orders | Yes | Yes | Yes |
| Submit / collect | Yes | No | Yes |
| Persist device delivery | No | Kitchen device only | No |
| Acknowledge / prepare / ready | No | Yes | No |
| Cancel Received with reason | No | No | Yes |
| Issue enrollment code | No | No | Hub manager device only |
| Payments / refunds / stock / shifts | Deferred | Deferred | Deferred |

Hub installation uses a manager-capability device. Its default cashier login has only cashier permissions; manager PIN grants manager controls. A kitchen PIN works only on a kitchen-enrolled device. Manager PIN is not accepted on ordinary cashier/kitchen devices. Every operational request revalidates device revocation, session, staff and expected version on the hub. M1 technical enrollment/delivery endpoints are narrowly separate from operational commands.

## Storage / synchronization contract
SQLite schema source: app/src/main/assets/001_initial.sql, SQLiteOpenHelper schema version 1. Destructive upgrade is forbidden; unknown migrations fail closed. A command transaction updates orders, appends one audit event and outbox row, then saves an encrypted receipt; all commit or all roll back. Events contain actor, device, order snapshot, command and timestamp; cancellation includes reason. Branch ID/business ID/authority epoch live in meta. Local DB is for one isolated training business and branch. Events and receipts have no pruning in M1.

Outbox is deliberately not drained until the M3 authenticated ingestion receiver exists. Pending means retained locally, not scheduled cloud delivery. Planned event envelope: event_id, business_id, branch_id, authority_epoch, sequence, occurred_at, event_type, actor_id, device_id, payload, payload_hash, schema_version. Authority epoch handover, RLS tenant isolation and transactional ingestion remain unimplemented. Future payment and stock effects must share the command transaction and unique origin IDs; no claims about financial retry tests are made for M1.
