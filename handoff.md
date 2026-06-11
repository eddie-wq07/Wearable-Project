# Wearable Project — Handoff

Living doc. Update at the end of every working session so the next session can pick up cold.

---

## 1. Goal

Build a working pipeline that collects health data from a **Samsung Galaxy Watch**, optionally relays it through a paired **Android phone**, and forwards it to a **backend** for storage. Used by the UBC Data + AI Lab (Sauder School of Business) in collaboration with SRCA.

The deployment target is a research study: ~60–80 Galaxy Watches running in **kiosk mode** so participants cannot leave the app.

### Prototype phases (overall plan)
| # | Phase | Status |
|---|---|---|
| 1 | Basic Wear OS app skeleton | ✅ Done |
| 2 | SDK connection on watch (Samsung Health Sensor SDK → live HR) | ✅ Done — live HR streaming verified |
| 3 | On-watch data collection → phone → backend relay | ⏳ Not started |
| 4 | Add more sensors (PPG, ECG, SpO2, skin temp, accelerometer, BIA) | ⏳ Not started |
| 5 | Production hardening, kiosk/study mode, reliability | 🟡 Kiosk built, partial hardening, distribution-partner submission TODO |

---

## 2. Current state of the codebase

Single Gradle module `:app`, namespace `com.example.testwatch`. Wear OS standalone app. Compose UI. Samsung Health Sensor SDK 1.4.1 (`app/libs/samsung-health-sensor-api-1.4.1.aar`).

```
app/src/main/java/com/example/testwatch/
├── ConnectionManager.java       Samsung SDK service connection
├── ConnectionObserver.java
├── HeartRateListener.java       HEART_RATE_CONTINUOUS subscriber
├── TrackerDataSubject.java
├── TrackerObserver.java
├── OffBodyStatus.java
├── KioskConfig.kt               PIN length, boot-notify constants
├── BootReceiver.kt              Auto-relaunch on boot (telemetry only)
├── admin/HrDeviceAdminReceiver.kt  Device Admin / DPC receiver
├── kiosk/PinManager.kt          Salted PBKDF2-SHA256 PIN in SharedPrefs
└── presentation/
    ├── MainActivity.kt          HR UI + lock-task + off-body sensor
    ├── PinScreens.kt            Setup + Unlock keypads
    └── theme/Theme.kt
```

**Build targets:** minSdk 30, targetSdk/compileSdk 36, Java 11, Compose enabled, `useLibrary("wear-sdk")`.

---

## 3. What's working as of last session

- [x] App installs on Galaxy Watch over ADB-WiFi.
- [x] Runtime permissions accepted (`BODY_SENSORS` + `health.READ_HEART_RATE`).
- [x] Samsung Health Tracking Service connects (`Status: 1` = valid HR reading).
- [x] Live BPM streams when Start is pressed while watch is worn.
- [x] Off-body sensor hooked up (verify-this still pending).
- [x] Kiosk framework in place (Device Admin receiver, lock-task code path, HOME intent filter).

## 4. What's NOT working / known issues

- [ ] **PIN setup UI is broken.** Letters clip on round watch (no `ScreenScaffold` safe-area padding). Buttons render light-on-light due to `surfaceContainer` + `onSurface` color combo. `PinScreens.kt:46-52` and `:152-156`.
- [ ] **PIN is configurable rather than fixed at 2365.** Product requirement is a hardcoded 2365 kiosk PIN. Setup flow should be removed and `PinManager.verify` should compare against `"2365"`. Drop the PBKDF2 + SharedPrefs storage path for now.
- [ ] **Kiosk lock-task is no-op on dev installs.** `enterKioskMode()` checks `isLockTaskPermitted` — false until the watch is provisioned as Device Owner (see §6). Expected and fine for dev; needs provisioning for study.
- [ ] **No data persistence.** HR values only flow to `mutableStateOf` in `MainActivity`. Nothing logged, stored, or sent.
- [ ] **No phone-relay or backend upload.** `KioskConfig.BOOT_NOTIFY_URL` is still `https://example.com/api/watch/boot`.

## 5. Immediate next priorities (next session)

1. **Hardcode PIN 2365 + fix PIN screens UI** — drop the setup flow, fix round-watch padding via `AppScaffold` + `ScreenScaffold`, fix contrast via `ButtonDefaults.filledTonalButtonColors()`.
2. **Add Room database for HR samples** — table `(timestamp_ms, bpm, status)`. Buffer survives screen-offs, app kills, and the phone being out of range.
3. **Pick + implement upload transport** — choose one:
   - **(a) Wear Data Layer → phone → cloud** (battery-friendly; uses phone's data; needs companion phone module).
   - **(b) Direct HTTPS from watch** (simpler; needs watch on WiFi or LTE).
4. **Stand up the backend endpoint** — decide: Firebase / lab REST API / Google Sheets / etc.

## 6. Kiosk mode — how it works

Five pieces:
1. `HrDeviceAdminReceiver` registers as a Device Administrator.
2. `dpm set-device-owner` promotes to Device Owner (one app per device, lifetime; requires factory-reset watch).
3. `startLockTask()` in `MainActivity.onResume` enters non-dismissible lock-task. Receiver also sets `DISALLOW_FACTORY_RESET`, `DISALLOW_ADD_USER`, `DISALLOW_SAFE_BOOT`.
4. `category.HOME` intent filter reroutes the watch's home press to our app.
5. PIN flow: setup on first launch, unlock via "Log out" → keypad → `stopLockTask()`.

### Per-watch provisioning steps (production)

```bash
# Prereq: watch factory-reset, no Samsung/Google account added, ADB-WiFi enabled.
adb connect <watch-ip>:<port>
adb -s <watch-ip>:<port> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <watch-ip>:<port> shell dpm set-device-owner \
    com.example.testwatch/.admin.HrDeviceAdminReceiver
adb -s <watch-ip>:<port> shell am start \
    -n com.example.testwatch/.presentation.MainActivity
# Pre-grant runtime perms to skip the first-run dialog race:
adb -s <watch-ip>:<port> shell pm grant com.example.testwatch android.permission.BODY_SENSORS
adb -s <watch-ip>:<port> shell pm grant com.example.testwatch android.permission.health.READ_HEART_RATE
```

If `dpm set-device-owner` fails with *"Not allowed to set the device owner because there are already several users on the device"* → factory reset again and skip every account prompt during setup.

### Removing kiosk during development

```bash
adb shell dpm remove-active-admin com.example.testwatch/.admin.HrDeviceAdminReceiver
```

Universal escape if that fails: factory reset the watch.

---

## 7. Samsung SDK — important context

### Partnership program
Per email from **Praveen Timmashetty (Samsung NA Health Partnerships)** on May 6, 2026:
> "You don't need any additional permission at this point from Samsung. ... If you are ready to distribute the app, please follow the process to submit the partnership request."

Translation: **no partnership approval needed for development**. The submission Angela filed earlier was for distribution. Production-distribute submission is still pending and will be needed before public release.

### `SDK_POLICY_ERROR` (the two-week blocker, resolved)
Samsung Health on the watch refuses unregistered apps. Fixable **on the watch**, not in code:

1. On the watch, open **Samsung Health**.
2. **Settings → General → About Samsung Health**.
3. Rapid-tap the version number ~10 times until a "Developer mode enabled" toast appears.
4. Force-close + relaunch our app.

This needs to be done on every watch in the fleet during provisioning until distribution-partner approval lands.

### `HEART_RATE_STATUS` values
| Value | Meaning |
|---|---|
| 1 | Valid measurement |
| 0 | No data this tick |
| -1 | Off-wrist |
| -2 | Too much motion |
| -3 | Poor signal (loose fit / sensor occluded) |

### First-launch permission race (latent bug, not yet hit in current session)
`MainActivity.onCreate` calls `requestPermissions(...)` then immediately calls `createConnectionManager()`. The SDK can attempt to connect while the permission dialog is still up → `PERMISSION_ERROR` swallowed → app sits on "Connecting..." forever.

**Demo workaround:** the `pm grant` lines in §6 above (pre-grant via ADB).
**Proper fix (~1h):** defer `createConnectionManager()` into `onRequestPermissionsResult`, then reconnect after the user taps Allow.

---

## 8. Pairing watch to laptop for development

- Use the **phone's mobile hotspot**, not public/guest WiFi. Public networks (UBCvisitor, eduroam, café WiFi) enable client isolation, which breaks ADB peer connectivity.
- Watch sleeps its WiFi radio aggressively → keep watch on the charger with **Developer options → "Stay awake"** on, and set **Settings → Connections → Wi-Fi → Always** so the radio doesn't suspend mid-pair.
- Once `adb pair` + `adb connect` succeed, an active ADB session keeps the radio alive even with the screen off.

---

## 9. Deliberate design decisions (so we don't relitigate)

- **4-digit PIN, not 6.** Faster entry on small touchscreen for 50+ cohort. Threat model = "curious participant", not hostile attacker.
- **No lockout on wrong PIN.** Older participants miss-tapping shouldn't get locked out mid-study.
- **No `BOOT_COMPLETED` auto-relaunch into app.** `HOME` intent filter is enough.
- **Salted PBKDF2-SHA256 in plain SharedPreferences** (not `EncryptedSharedPreferences`). Adequate for kiosk PIN; avoids `androidx.security:security-crypto` dependency issues. *Note: about to be replaced by a hardcoded 2365 check anyway.*
- **Same PIN across all watches** is the working assumption (vs per-watch in a binder).
- **Standalone Wear app** (`com.google.android.wearable.standalone = true`) — survives unpairing from phone.

---

## 10. Open questions (to confirm with supervisor)

- Upload transport: Wear Data Layer relay through phone, or direct HTTPS from watch?
- Backend choice (Firebase / lab REST API / Google Sheets / other)?
- Same PIN across all 60–80 watches, or per-watch?
- Begin Samsung distribution-partnership submission now in parallel with code work?
- Which additional sensors are highest priority for Prototype 4 (PPG raw, ECG, SpO2, skin temp, accel, BIA, MF-BIA)?

---

## 11. External references

- Samsung Health Sensor SDK guide: https://developer.samsung.com/health/sensor/guide/
- Programming guide (continuous vs on-demand trackers): https://developer.samsung.com/health/sensor/guide/data-specifications.html
- HR Tracker sample: https://developer.samsung.com/health/sensor/sample/hr-tracker.html
- UBC Data + AI Lab: https://blogs.ubc.ca/analyticsailab/

### Samsung contacts (from May 2026 email thread)
- **Praveen Timmashetty** — SDK technical lead. Confirmed dev-time approval not required.
- **Jennifer Li** — NA Health Partnerships intake.
- **Zijing (Susie) Shao** — SRCA Vancouver, internal liaison.

### UBC contacts
- **Prof. Gene Moo Lee** — supervisor (Sauder ISA).
- **Angela Kwon** — PhD student, primary partner contact.
