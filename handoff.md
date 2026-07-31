# Wearable Project — Handoff

Living doc. Update at the end of every working session so the next session can pick up cold.

---

## 1. Goal

Build a working pipeline that collects health data from a **Samsung Galaxy Watch**, relays it through a paired **Samsung phone**, and forwards it to a **lab-hosted SQLite server** for storage. Used by the UBC Data + AI Lab (Sauder School of Business) in collaboration with SRCA.

The deployment target is a research study: ~60–80 Galaxy Watches running in **kiosk mode** so participants cannot leave the app.

### Hardware (as of current session)
- **Watch:** Galaxy Watch 8 (SHJE), model `SM-L320`. Wear OS, Samsung Health Sensor SDK 1.4.1, dev-mode toggle on, paired to phone via Galaxy Wearable.
- **Phone:** Galaxy A35 5G, model `SM-A356W`. Already paired to the watch.
- **Carrier:** Rogers (data throttled after fast-data cap, still fine for batched JSON uploads).

### Prototype phases
| # | Phase | Status |
|---|---|---|
| 1 | Basic Wear OS app skeleton | ✅ Done |
| 2 | SDK connection on watch (Samsung Health Sensor SDK → live HR) | ✅ Done |
| 3 | On-watch data collection → phone → backend relay | 🟡 In progress (code in this session) |
| 4 | Add more sensors (PPG, ECG, SpO2, skin temp, accel, BIA) | ⏳ Not started |
| 5 | Production hardening, kiosk/study mode, reliability | 🟡 Kiosk built, partial hardening, distribution-partner submission TODO |

---

## 2. Architecture

Two Gradle modules in one project. Both share `applicationId = com.example.testwatch` — this is what bridges them via Wear Data Layer.

```
┌──────────── Galaxy Watch 8 ────────────┐    ┌──────────── Galaxy A35 ────────────┐    ┌────── Server ──────┐
│ Samsung SDK (HR_CONTINUOUS, ~1Hz)      │    │ HrBatchListenerService            │    │ SFTP inbox dir     │
│   │                                    │    │   (WearableListenerService)       │    │   /var/data/hr_in… │
│   ▼                                    │ BT │   │                               │    │                    │
│ HrTrackingService (foreground)         │───▶│   ▼                               │    │ (lab ingestion     │
│   │ writes each sample                 │    │ phone Room (hr_phone.db)         │    │  reads files into  │
│   ▼                                    │    │   │                               │    │  SQLite)           │
│ watch Room (hr.db)                     │    │   ▼                               │    │                    │
│   │ every 5 min                        │    │ UploadWorker (WorkManager)        │───▶│                    │
│   ▼                                    │    │   SFTP via sshj, retries on fail  │SFTP│                    │
│ Wearable.MessageClient                 │    │                                   │    │                    │
│   path "/hr_batch" (JSON, ≤500/batch)  │    │                                   │    │                    │
└────────────────────────────────────────┘    └───────────────────────────────────┘    └────────────────────┘
```

### Module layout
```
:app   (Wear OS) — applicationId com.example.testwatch
:mobile         — applicationId com.example.testwatch (same; required for Wear Data Layer pairing)
```

### Watch module (`:app`)
```
java/com/example/testwatch/
├── ConnectionManager.java       Samsung SDK service connection (null-safe re activity)
├── ConnectionObserver.java
├── HeartRateListener.java       HEART_RATE_CONTINUOUS subscriber
├── TrackerDataSubject.java
├── TrackerObserver.java
├── OffBodyStatus.java
├── KioskConfig.kt               PIN = "2365", batch constants, telemetry URL
├── BootReceiver.kt              Telemetry on boot
├── admin/
│   ├── HrDeviceAdminReceiver.kt Device Admin / DPC receiver
│   └── SetParticipantIdReceiver.kt   ADB-broadcast → SharedPrefs
├── kiosk/PinManager.kt          Fixed-PIN check (== KioskConfig.PIN)
├── data/
│   ├── HrSample.kt              Room entity (id, ts, bpm, status, synced)
│   ├── HrDao.kt                 unsynced / markSynced / pruneSynced / count
│   ├── HrDatabase.kt            hr.db
│   └── ParticipantStore.kt      SharedPrefs wrapper for participant_id
├── tracking/
│   ├── HrTrackingService.kt     Foreground service: SDK + Room writes + sync loop
│   └── TrackingState.kt         MutableStateFlow snapshot for the UI
├── sync/
│   └── BatchSerializer.kt       kotlinx-serialization JSON wire format
└── presentation/
    ├── MainActivity.kt          Live BPM display + kiosk + Log out
    ├── PinScreens.kt            Unlock keypad
    └── theme/Theme.kt
```

### Mobile module (`:mobile`)
```
java/com/example/testwatch/mobile/
├── StatusActivity.kt            Single TextView "Companion installed. Running in background."
├── boot/BootReceiver.kt         Schedules a one-shot upload on phone boot
├── data/
│   ├── PhoneHrSample.kt         Mirror entity (participant_id, ts, bpm, status, uploaded)
│   ├── PhoneHrDao.kt
│   └── PhoneHrDatabase.kt       hr_phone.db
├── wear/
│   └── HrBatchListenerService.kt   Decodes /hr_batch → Room → enqueues UploadWorker
└── upload/
    ├── ServerConfig.kt          HOST, PORT, USER, REMOTE_DIR — TODOs to fill in
    └── UploadWorker.kt          sshj SFTP upload, retries with exponential backoff
```

---

## 3. Current state

- [x] App installs on Galaxy Watch 8 over ADB-WiFi.
- [x] Runtime permissions accepted (`BODY_SENSORS`, `health.READ_HEART_RATE`, on API 33+ `POST_NOTIFICATIONS`).
- [x] Samsung Health Tracking Service connects (status 1 = valid HR).
- [x] **Continuous background tracking** via `HrTrackingService` (foreground service, type `health`). No Start/Stop UI — opens straight to live BPM display.
- [x] HR samples written to Room (`hr.db`) on every callback.
- [x] **5-min sync loop** pushes unsynced samples to phone via `Wearable.MessageClient` (path `/hr_batch`).
- [x] **Phone companion** receives batches, mirrors to Room (`hr_phone.db`), enqueues SFTP upload.
- [x] Kiosk + 2365 PIN unlock.

### Pending verification on device
- [x] Phone build installs cleanly.
- [x] Watch → phone Data Layer round-trip (verified 2026-07-30: 500-sample batches every 30 s).
- [x] SFTP upload reaches the server (verified 2026-07-30: `SFTP put OK` to `/data1/wearables`).
- [ ] Overnight doze soak test: confirm the sensor's hardware buffer survives hours of doze, not just minutes.
- [ ] `SYNC_INTERVAL_MS` still on 30 s testing override — restore to 5 min before deployment.
- [ ] Server dir has junk test files (`hr_*.json` with June-11 timestamps + zero-BPM data) — archive with:
      `mkdir -p /data1/wearables/old_test_data && mv /data1/wearables/hr_*.json /data1/wearables/old_test_data/`
      (Both device DBs were wiped clean on 2026-07-30 after the timestamp fix; local backups in session scratchpad.)

---

## 4. Setup the user must do before the pipeline lands data on the server

1. **Fill in `mobile/src/main/java/com/example/testwatch/mobile/upload/ServerConfig.kt`:**
   - Currently set: `HOST = "misr.sauder.ubc.ca"`, `PORT = 16800`, `USER = "edward"`, `REMOTE_DIR = "hr_inbox"`.
   - **PASSWORD** is the only TODO — fill in the temp password for the lab box.
   - **Auth is password-based for now** (temp setup). Once an SSH key is provisioned, switch `UploadWorker.uploadOverSftp()` from `authPassword(...)` back to the `loadKeys()` / `authPublickey()` flow and drop `PASSWORD`.
2. **Create the upload directory on the server** (once, after first SSH login):
   ```bash
   ssh -p 16800 edward@misr.sauder.ubc.ca
   mkdir -p hr_inbox
   ```
3. **Server-side ingestion** — write a small script that scans `hr_inbox/` for `hr_*_*.json` files and inserts into SQLite. Suggested schema:
   ```sql
   CREATE TABLE hr_samples (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       participant_id TEXT NOT NULL,
       timestamp_ms INTEGER NOT NULL,
       bpm INTEGER NOT NULL,
       status INTEGER NOT NULL,
       received_at INTEGER NOT NULL,
       ingested_at INTEGER NOT NULL
   );
   CREATE INDEX idx_pid_ts ON hr_samples (participant_id, timestamp_ms);
   ```
   File format (each upload is a single JSON file):
   ```json
   {
     "watch_serial": "RXYZ123",
     "uploaded_at": 1717980000000,
     "samples": [
       { "participant_id": "P042", "timestamp_ms": 1717979700123, "bpm": 72, "status": 1, "received_at": 1717979750000 }
     ]
   }
   ```
4. **Participant ID** — auto-generated on first launch as `P-<8-char UUID slice>` and persisted in SharedPreferences. To override per watch (e.g. assign a study code):
   ```bash
   adb shell am broadcast -a com.example.testwatch.SET_PARTICIPANT_ID \
       -n com.example.testwatch/.admin.SetParticipantIdReceiver \
       --es participant_id "P042"
   ```

---

## 5. Build / install commands

```bash
# Watch APK
./gradlew :app:installDebug

# Phone APK
./gradlew :mobile:installDebug

# Or build APKs without installing
./gradlew :app:assembleDebug :mobile:assembleDebug
# Watch APK   → app/build/outputs/apk/debug/app-debug.apk
# Phone APK   → mobile/build/outputs/apk/debug/mobile-debug.apk
```

### Useful adb commands
```bash
# Watch logcat for the tracking service + sync loop:
adb -s <watch>     logcat -s HrTrackingService:V HeartRateListener:V ConnectionManager:V

# Phone logcat for the listener + upload worker:
adb -s <phone>     logcat -s HrBatchListener:V UploadWorker:V

# Force a one-time upload from phone:
adb -s <phone> shell am broadcast -a android.intent.action.BOOT_COMPLETED \
    -n com.example.testwatch/.boot.BootReceiver
```

---

## 6. Tuning constants

| Where | Constant | Default | Why |
|---|---|---|---|
| `HrTrackingService.kt` | `SYNC_INTERVAL_MS` | 5 min | Trade-off between data-loss window and Bluetooth wake-ups. |
| `HrTrackingService.kt` | `BATCH_LIMIT` | 500 samples | ~30 KB JSON; well under MessageClient 100 KB cap. |
| `HrTrackingService.kt` | `KEEP_RECENT_SYNCED` | 1000 rows | Keep the last N synced rows for debugging before pruning. |
| `UploadWorker.kt` | `MAX_RETRIES` | 10 | Retries with exponential backoff (WorkManager default). |
| `KioskConfig.kt` | `PIN` | `"2365"` | Hardcoded kiosk unlock. |

---

## 7. Samsung SDK — important context

### Partnership program
Per email from **Praveen Timmashetty (Samsung NA Health Partnerships)** on May 6, 2026:
> "You don't need any additional permission at this point from Samsung. ... If you are ready to distribute the app, please follow the process to submit the partnership request."

**No partnership approval needed for development.** Distribution-partner submission still pending for public release.

### `SDK_POLICY_ERROR` (resolved by watch-side toggle)
Samsung Health on the watch refuses unregistered apps unless dev mode is on:
1. On the watch, open **Samsung Health**.
2. **Settings → General → About Samsung Health**.
3. Rapid-tap the version number ~10 times until "Developer mode enabled" toast.
4. Force-close + relaunch our app.

Per-watch step during provisioning until distribution partner approval lands.

### `HEART_RATE_STATUS` values
| Value | Meaning |
|---|---|
| 1 | Valid measurement |
| 0 | No data this tick |
| -1 | Off-wrist |
| -2 | Too much motion |
| -3 | Poor signal |
| -10 | Warming up (seen briefly right after strapping on) |

**Observed on Galaxy Watch 8 (2026-07-30):** off-wrist/on-charger reports `-3`, not `-1`. Downstream filtering should keep `status == 1` rather than exclude specific negative codes.

### Doze / ambient behavior (verified 2026-07-30)
The sensor stack stops *delivering* datapoints ~30–90 s after the screen sleeps whenever our app is not the top activity (SysUI ambient takes over on doze). **No data is lost** — the sensor keeps sampling at ~1 Hz into a hardware buffer and flushes the whole backlog in one burst when the app returns to the foreground (verified: 226-sample burst after a 227 s doze, 182 after 182 s). Because bursts arrive minutes late, samples are stamped with the SDK `DataPoint.getTimestamp()` (fix in `HeartRateListener`/`HrTrackingService`, 2026-07-30), **not** arrival time. Buffer depth beyond ~4 min doze is unverified — soak-test overnight before the study.

### Watch clock drift (root cause of "all zeros + June timestamps", 2026-07-30)
With Bluetooth off, the watch never syncs time and drifted 7 weeks stale, mislabeling every sample. `auto_time=1` is now set; provisioning must verify BT is on and the clock is correct. Server ingestion should sanity-check `timestamp_ms` against the upload time and flag watches whose clock has drifted.

### Dev-session buzzing
The watch buzzes on the system "Wireless debugging" notification every time the flaky ADB-WiFi link reconnects. Harmless, dev-only; toggle Wireless debugging off after each session.

### First-launch permission race (latent bug, mitigated)
`MainActivity.onCreate` previously raced the SDK connect against the runtime permission dialog. Continuous mode now starts the service only after `onRequestPermissionsResult` confirms grants (or if already granted at create time). Pre-grant via ADB during provisioning is also still in the playbook:
```bash
adb shell pm grant com.example.testwatch android.permission.BODY_SENSORS
adb shell pm grant com.example.testwatch android.permission.health.READ_HEART_RATE
```

---

## 8. Kiosk mode

Five pieces:
1. `HrDeviceAdminReceiver` registers as Device Administrator.
2. `dpm set-device-owner` promotes to Device Owner (one app per device, lifetime; requires factory-reset watch).
3. `startLockTask()` in `MainActivity.onResume` enters non-dismissible lock-task. Receiver sets `DISALLOW_FACTORY_RESET`, `DISALLOW_ADD_USER`, `DISALLOW_SAFE_BOOT`.
4. `category.HOME` intent filter reroutes home press to our app.
5. PIN: hardcoded `2365`, unlock via "Log out" → keypad → `stopLockTask()`.

### Per-watch provisioning steps (production)

```bash
# Prereq: watch factory-reset, no Samsung/Google account added, ADB-WiFi enabled.
adb connect <watch-ip>:<port>
adb -s <watch> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <watch> shell dpm set-device-owner \
    com.example.testwatch/.admin.HrDeviceAdminReceiver
adb -s <watch> shell pm grant com.example.testwatch android.permission.BODY_SENSORS
adb -s <watch> shell pm grant com.example.testwatch android.permission.health.READ_HEART_RATE
adb -s <watch> shell am broadcast -a com.example.testwatch.SET_PARTICIPANT_ID \
    -n com.example.testwatch/.admin.SetParticipantIdReceiver \
    --es participant_id "P042"
adb -s <watch> shell am start -n com.example.testwatch/.presentation.MainActivity
# Enable Samsung Health dev mode (manual step on watch — see §7).
```

### Removing kiosk during development
```bash
adb shell dpm remove-active-admin com.example.testwatch/.admin.HrDeviceAdminReceiver
```
Universal escape: factory reset.

---

## 9. Pairing watch to laptop for development

- Use the **phone's mobile hotspot**, not public/guest WiFi. Public networks (UBCvisitor, eduroam) enable client isolation, which breaks ADB peer connectivity.
- Watch sleeps WiFi aggressively → keep it on the charger with **Developer options → "Stay awake"** on, and **Settings → Connections → Wi-Fi → Always**.
- Once `adb pair` + `adb connect` succeed, an active ADB session keeps the radio alive even with the screen off.

---

## 10. Deliberate design decisions

- **4-digit PIN, fixed at 2365.** Same on every watch. Threat model = curious participant, not adversary.
- **Continuous tracking, no Start/Stop UI.** Foreground service runs from app launch onward; participants only see live BPM. Off-wrist samples (status -1) still get recorded — downstream filtering decides what to drop.
- **5-min batches.** Worst-case data loss = 5 min; well under Wear MessageClient 100 KB cap; 12 BT wake-ups per hour instead of 3600.
- **Watch Room + phone Room.** Both sides have durable buffers — survives BT drops, server outages, phone reboots.
- **SFTP via sshj on phone, not direct from watch.** Watch radio sleeps; phone is the natural relay; matches the "use the phone's data plan" intent.
- **Promiscuous host-key verifier.** Research-acceptable but not production-grade — pin the host key fingerprint before public study deployment.
- **No `EncryptedSharedPreferences` for participant ID.** It's a non-secret tag; salted hash adds nothing.

---

## 11. Open questions (still pending)

- Pin SSH host key fingerprint (replace `PromiscuousVerifier`)?
- Per-watch SSH keys vs shared key?
- Should the phone reupload **all** samples on a manual user trigger (debug screen), or only via WorkManager?
- Start Samsung distribution-partnership submission now in parallel?
- Which additional sensors for Prototype 4 (PPG raw, ECG, SpO2, skin temp, accel, BIA, MF-BIA)?

---

## 12. External references

- Samsung Health Sensor SDK guide: https://developer.samsung.com/health/sensor/guide/
- HR Tracker sample: https://developer.samsung.com/health/sensor/sample/hr-tracker.html
- Wear OS Data Layer guide: https://developer.android.com/training/wearables/data/data-layer
- sshj on GitHub: https://github.com/hierynomus/sshj
- UBC Data + AI Lab: https://blogs.ubc.ca/analyticsailab/

### Samsung contacts (May 2026 email thread)
- **Praveen Timmashetty** — SDK technical lead.
- **Jennifer Li** — NA Health Partnerships intake.
- **Zijing (Susie) Shao** — SRCA Vancouver, internal liaison.

### UBC contacts
- **Prof. Gene Moo Lee** — supervisor (Sauder ISA).
- **Angela Kwon** — PhD student, primary partner contact.
