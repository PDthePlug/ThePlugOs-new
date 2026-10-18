# Installation and local pilot operating instructions

## Build
JDK 17, Android SDK platform 35 / build tools 35, Gradle wrapper 8.9. No production secret, Node runtime, cloud project or backend required. With Android SDK installed, set ANDROID_HOME or create ignored local.properties containing sdk.dir=<absolute SDK path>.

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Application ID com.theplugos.local.pilot deliberately differs from historical apps. Minimum API 26 (Android 8); API 35 target. Development APK only; commercial distribution/signing, Play policy and hardware support matrix are M5 work. CI builds/test reports/APKs are workflow artifacts, not proof of physical acceptance.

## Training setup
1. Install same APK on hub and kitchen. Connect both to the same private Wi-Fi router. Disable guest isolation/AP isolation. Reserve the hub IPv4 address via DHCP. No port forwarding. Router must remain powered when its internet cable is removed. Phone hotspot operation is an option requiring its own physical rehearsal, not a verified substitute.
2. On the ONE chosen hub enter training branch name and different manager, cashier and kitchen PINs (6–12 digits). Record staff IDs manager / cashier / kitchen and their PINs securely. Do not use real staff/customer information yet.
3. Hub starts a persistent foreground service. Grant notifications. Sign in as manager to see hub address, full SHA-256 fingerprint and create a kitchen pairing code. It expires in five minutes and is consumed once. Compare full fingerprint directly with hub before entering it on kitchen. No automatic discovery or cloud enrollment in M1.
4. On kitchen enter IPv4, fingerprint, code and enroll. Sign in as kitchen with the assigned PIN. Device credential is encrypted with its own Keystore key. If a pairing reply is lost, get a new code; the unused previous credential cannot be recovered by guessing a device ID.
5. Lock hub and sign in as cashier. Build a chips/burger/drink order; repeat taps add quantity, Remove last item edits cart. Select modifier before adding a line. Notes saved durably while editing. Send. Confirm distinct saved/delivered/acknowledged statuses.
6. Kitchen acknowledges, starts preparation, marks ready. Cashier sees Ready until Confirm customer collected. Missed sound does not clear status. New-order and ready sounds work while the corresponding workspace is active; volume/device settings may suppress sound.
7. Additional cashiers enroll the same way with cashier codes and submit through original hub. Do not initialize another hub for the same branch.

## Connectivity and runtime
Hub uses a user-started connectedDevice foreground service, ongoing notification, CPU wake lock and Wi-Fi lock to serve local clients. Keep it plugged in with a secure lock screen; review OEM battery restrictions with the operator. These controls cannot guarantee survival of force-stop, power loss, OEM process termination, exhausted storage or router failure. Actual background/screen-lock behaviour is unverified until physical testing.
Client sessions lock immediately when Activity backgrounds or screen locks; hub service continues independently. Clients poll while workspace is foreground and signed in. They recover durable snapshots and unfinished work on sign-in. Background kitchen delivery/notification is not implemented in M1: a locked kitchen becomes disconnected within five seconds and cashier sees delivery pending for new orders. Do not run an unattended locked kitchen in this pilot.
Hub service asks Android to restart after ordinary process reclamation; the original database remains authoritative. After full reboot/unlock or force-stop, reopen original app explicitly. No boot receiver, auto-failover or silent replacement. The same application must have its Keystore keys; clearing data/uninstall loses access to local records.
If DHCP changes, edit hub address on enrolled terminal sign-in page; preserve certificate pin. If fingerprint changes, stop and verify hub identity. Do not bypass the pin. Full internet loss does not affect local route. Loss of router/LAN blocks delivery even if both devices have mobile data.
Network failure shows stale cached view and preserves an unconfirmed command. Press Retry saved command after reconnection using the original staff login. Do not re-key the order. Only hub-confirmed 400/403/404/409 rejection clears the pending command; original cart remains available for review. Pending commands on another terminal are not transferred.

## Security policy and limits
PIN salts random, PBKDF2-HMAC-SHA256 210,000 rounds, hashes encrypted. Five wrong attempts lock that staff ID for five minutes, persisted on hub restart. Session token expires after 15 minutes (absolute), and session is device-bound. Enrollment and access can work indefinitely offline in this training build; it does not yet implement cloud-signed staff leases or remote revocation. Production proposal: signed 7-day offline permission bundle, refreshed daily, explicit safe-stop on expiry; owner must confirm this outage/security tradeoff before M5.
A disconnected branch cannot receive immediate remote revocations. Planned cloud revocation refresh occurs before new sessions when connectivity returns; already accepted records remain valid history. No owner account, recovery email, remote device management or real business enrollment in M1. Do not mistake local manager setup for owner account authentication.
Only encrypted payloads/secrets go into files; IDs/statuses/times/role names remain clear structural metadata. Android Keystore wrapping is device-bound. Rooted/compromised devices are outside this pilot's trust model. Do not copy raw database files as a portable encrypted backup: keys cannot be transplanted.

## Recovery, upgrades and backup
M1 supports original-device process/app restart recovery. It does NOT provide portable backup/restore or hub replacement. Until M5, losing or resetting the hub loses records that were not synchronized or exported; cloud sync/export is not present either. Training use only.
M5 must add passphrase/recovery-key encrypted consistent backup via SQLite backup/checkpoint APIs, verify backup integrity/sequence and test restoration to a different physical Android. Record last backup and sync positions and quantify potential loss. Enforce physical fencing of old hub, signed branch authority epoch transfer and terminal re-enrollment before replacement accepts work. Local time alone cannot prevent split brain between isolated old and new hubs; operator fencing is mandatory. Never copy a live DB/WAL pair casually or let two restored authorities trade independently.
Upgrade only with retained application signing identity, versioned transactional migrations, pre-upgrade backup, sufficient disk, maintenance mode, rollback compatibility and migration failure rehearsal. Current schema only supports v1; unsupported upgrades fail closed rather than deleting data. Recovery of original hub credentials if setup commit succeeds but terminal config write fails is also M5 work; stop and retain files rather than reinitialize.

## Physical acceptance record (unfilled)
Record date/operator; APK SHA-256; hub/kitchen models/OS; router, RSSI, isolation settings, WAN state; each observed order ID, device-delivery time, human ack time, ready/display/collection times; app/background/reboot/reconnect evidence. At least 100 samples per direction for p95. Run low-storage/power-loss and hub replacement only after safe backup tooling exists. Mark each result PASS/FAIL/BLOCKED with evidence. Never record unperformed tests as PASS.

## Run Android boundary checks when hardware is available
With the training hub service stopped (instrumentation needs local port 8443):

```sh
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am force-stop com.theplugos.local.pilot
adb shell am instrument -w com.theplugos.local.pilot.test/android.test.InstrumentationTestRunner
```

These two tests cover actual Android Keystore reopen and pinned TLS rejection/acceptance boundaries. They do not replace the separate-device order loop, WAN-off rehearsal, wake/screen-lock tests or recovery acceptance sheet. Do not use production package IDs with these commands.
