# Wearable Project — Handoff

Living doc. Update at the end of every working session so the next session can pick up cold.

---

## 1. Goal

Build a working pipeline that collects health data from a **Samsung Galaxy Watch**, relays it through a paired **Samsung phone**, and forwards it to a **lab-hosted SQLite server** for storage. Used by the UBC Data + AI Lab (Sauder School of Business) in collaboration with SRCA.

The deployment target is a research study: ~60–80 Galaxy Watches handed to participants. (Kiosk/lock-task mode was removed 2026-08-27 — see §8.)

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
| 5 | Production hardening, reliability | 🟡 Partial hardening; kiosk mode built then removed (2026-08-27); distribution-partner submission TODO |

---

## 2. Architecture

Two Gradle modules in one project. Both share `applicationId = com.example.testwatch` — this is what bridges them via Wear Data Layer.

```
┌──────────── Galaxy Watch 8 ────────────┐    ┌──────────── Galaxy A35 ────────────┐    ┌────── Server ──────┐
│ Samsung SDK: HR 1Hz + SensorEngine     │    │ HrBatchListenerService            │    │ SFTP inbox dir     │
│  (ppg 25Hz, accel 25Hz, skin_temp,     │    │   (WearableListenerService)       │    │   /data1/wearables │
│   on-demand rounds every 5 min)        │    │   │                               │    │                    │
│   │                                    │ BT │   ▼                               │    │ (lab ingestion     │
│ HrTrackingService (foreground)         │───▶│ phone Room (hr_phone.db)         │    │  reads files into  │
│   │ writes each sample/batch           │    │   │                               │    │  SQLite)           │
│   ▼                                    │    │   ▼                               │    │                    │
│ watch Room (hr.db) — STORE-AND-FORWARD │    │ UploadWorker (WorkManager)        │───▶│                    │
│   buffers locally; drains ONLY at      │    │   SFTP via sshj, retries on fail  │SFTP│                    │
│   12 GB high-water / 2 GB free floor / │    │                                   │    │                    │
│   manual DRAIN_NOW broadcast           │    │                                   │    │                    │
│   ▼                                    │    │                                   │    │                    │
│ Wearable.MessageClient                 │    │                                   │    │                    │
│   "/hr_batch" + "/sensor_batch"        │    │                                   │    │                    │
└────────────────────────────────────────┘    └───────────────────────────────────┘    └────────────────────┘
```

### Module layout
```
:watch  (Wear OS) — applicationId com.example.testwatch
:mobile          — applicationId com.example.testwatch (same; required for Wear Data Layer pairing)
```
(Structured around three things: the watch app, the sensors, the mobile app. Renamed `:app` → `:watch` 2026-08-27.)

### Watch module (`:watch`)
```
java/com/example/testwatch/
├── BootReceiver.kt              Restarts HrTrackingService after reboot
├── admin/
│   └── SetParticipantIdReceiver.kt   ADB-broadcast → SharedPrefs
├── sensors/                     ← everything that touches the Samsung SDK
│   ├── SensorSpec.kt            SENSORS registry (continuous + on-demand)
│   ├── SensorEngine.kt          Runs the registry: continuous sessions + on-demand rounds
│   ├── TrackerSession.kt        Generic per-sensor tracker wrapper
│   ├── ConnectionManager.java   Samsung SDK service connection
│   ├── ConnectionObserver.java
│   ├── HeartRateListener.java   Legacy HR lane (HEART_RATE_CONTINUOUS subscriber)
│   ├── TrackerDataSubject.java / TrackerObserver.java
│   └── OffBodyStatus.java
├── config/
│   ├── StorageConfig.kt         Storage budget: thresholds + the data-rate math (2026-08-26)
│   └── SensorConfig.kt          On-demand round timings + BIA user profile
├── data/
│   ├── HrSample.kt              Room entity (id, ts, bpm, status, synced)
│   ├── HrDao.kt                 unsynced / markSynced / pruneSynced / count
│   ├── HrDatabase.kt            hr.db
│   └── ParticipantStore.kt      SharedPrefs wrapper for participant_id
├── tracking/
│   ├── HrTrackingService.kt     Foreground service: SDK + Room writes + store-and-forward loop
│   ├── MeasureAlarmReceiver.kt  Doze-proof alarm tick for on-demand rounds
│   └── TrackingState.kt         MutableStateFlow snapshot for the UI (+ buffer telemetry)
├── sync/
│   └── BatchSerializer.kt       kotlinx-serialization JSON wire format
└── presentation/
    ├── MainActivity.kt          Live BPM display
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
├── config/
│   └── ServerConfig.kt          HOST, PORT, USER, REMOTE_DIR (gitignored; copy the .example)
└── upload/
    └── UploadWorker.kt          sshj SFTP upload, retries with exponential backoff
```

### Store-and-forward buffering (architecture change, 2026-08-26)

The watch no longer syncs on a fixed cadence. It **stores everything locally in
`hr.db` and pushes to the phone only when a storage threshold is reached** (or on
a manual trigger). All numbers live in `app/.../tracking/StorageConfig.kt`.

**Watch storage (the budget this is sized against)**

| Fact | Value |
|---|---|
| Galaxy Watch 8 flash | 32 GB advertised, **~18 GB actually usable** after Wear OS + preloads |
| RAM | 2 GB — irrelevant to buffering; every sample lands in SQLite via Room |
| Write rate, current sensor set | **~320 MB/day** (ppg 25 Hz ≈ 194 MB + accel 25 Hz ≈ 93 MB + HR 1 Hz ≈ 3.5 MB + skin_temp ≈ 0.1 MB + on-demand rounds ≈ 2 MB, ×1.15 SQLite overhead) |
| Time to fill usable storage | **~56 days** |
| One 2–4-week study window | **4.5–9 GB** — fits comfortably below the high-water mark |

**Continuous sensors buffered and uploaded via the phone** (the `SENSORS`
registry in `SensorSpec.kt`): `ppg` (green/IR/red + statuses, 25 Hz), `accel`
(x/y/z, 25 Hz), `skin_temp` (~1/min), plus the legacy 1 Hz `hr` path
(`hr_samples` table). On-demand rounds (`spo2`, `bia`, `mf_bia`, `ecg`) every
5 min ride the same buffer.

**Timing / trigger logic** (`HrTrackingService.syncLoop`, checked every 5 min):

| Trigger | Threshold | Behavior |
|---|---|---|
| High water | unsynced bytes ≥ **12 GB** (~37 days of data) | Drain the full backlog to the phone, then resume storing |
| Free-space floor | device free < **2 GB** | Same drain, regardless of logical budget |
| Emergency | device free < **1 GB** | Drop oldest unsynced rows (20 k/table per pass), counted in `TrackingState.droppedRows` — **nonzero means a data gap; never silent** |
| Manual | `DRAIN_NOW` broadcast (see §5) | Full drain with a 60-min wakelock — **the intended end-of-study offload; do it on the charger** |

A drain calls `syncOnce()` in a loop (500 HR samples + up to 300 sensor batches
per round, chunked under the 100 KB MessageClient cap, 200 ms between rounds)
until the buffer is empty or the phone drops off; an interrupted drain simply
resumes at the next 5-min check. Since a 2–4-week study never crosses 12 GB,
**in normal operation the watch is silent for the whole study and uploads once,
at the end.**

**Known consequences to plan around:**
- **Drain duration is untested and could be long.** Multi-GB over
  MessageClient/BT could be many hours (BT classic realistically tens of KB/s;
  Data Layer may route over WiFi when available — measure before the fleet).
- **The `hr.db` file never shrinks** after a drain (SQLite reuses pages but
  doesn't return them without VACUUM). Expected and fine: the file plateaus at
  the high-water mark.
- **The phone gets the whole backlog at once** — `UploadWorker` handles it
  (KEEP policy), but the phone-side periodic upload floor (B7) is now more
  important, and phone storage takes the same multi-GB burst.
- **No liveness signal while storing.** A watch that dies on day 2 looks
  identical to a healthy one until offload. A tiny daily heartbeat is an open
  question (§11).

---

## 3. Current state

- [x] App installs on Galaxy Watch 8 over ADB-WiFi.
- [x] Runtime permissions accepted (`BODY_SENSORS`, `health.READ_HEART_RATE`, on API 33+ `POST_NOTIFICATIONS`).
- [x] Samsung Health Tracking Service connects (status 1 = valid HR).
- [x] **Continuous background tracking** via `HrTrackingService` (foreground service, type `health`). No Start/Stop UI — opens straight to live BPM display.
- [x] HR samples written to Room (`hr.db`) on every callback.
- [x] **Store-and-forward loop** (2026-08-26): buffers locally, drains to the phone via `Wearable.MessageClient` (`/hr_batch`, `/sensor_batch`) only at 12 GB high-water / 2 GB free floor / manual `DRAIN_NOW`. Replaced the old fixed 5-min (30 s testing) sync loop.
- [x] **Phone companion** receives batches, mirrors to Room (`hr_phone.db`), enqueues SFTP upload.
- [x] Kiosk mode removed (2026-08-27); watch `BootReceiver` now restarts the tracking service after reboot instead.

### Pending verification on device
- [x] Phone build installs cleanly.
- [x] Watch → phone Data Layer round-trip (verified 2026-07-30: 500-sample batches every 30 s).
- [x] SFTP upload reaches the server (verified 2026-07-30: `SFTP put OK` to `/data1/wearables`).
- [ ] Overnight doze soak test: confirm the sensor's hardware buffer survives hours of doze, not just minutes.
- [x] ~~`SYNC_INTERVAL_MS` still on 30 s testing override~~ — constant removed 2026-08-26; the store-and-forward loop checks storage every 5 min (`StorageConfig.CHECK_INTERVAL_MS`).
- [ ] **Drain throughput unmeasured**: time a multi-GB `DRAIN_NOW` end-to-end (watch → phone → SFTP) before committing the fleet to end-of-study offloads.
- [ ] Verify a real `DRAIN_NOW` on device: buffer a few hours of data, fire the broadcast, confirm `drain stopped after N rounds` in logcat and rows landing server-side.
- [ ] Server dir has junk test files (`hr_*.json` with June-11 timestamps + zero-BPM data) — archive with:
      `mkdir -p /data1/wearables/old_test_data && mv /data1/wearables/hr_*.json /data1/wearables/old_test_data/`
      (Both device DBs were wiped clean on 2026-07-30 after the timestamp fix; local backups in session scratchpad.)

---

## 4. Setup the user must do before the pipeline lands data on the server

1. **Fill in `mobile/src/main/java/com/example/testwatch/mobile/config/ServerConfig.kt`:**
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
./gradlew :watch:installDebug

# Phone APK
./gradlew :mobile:installDebug

# Or build APKs without installing
./gradlew :watch:assembleDebug :mobile:assembleDebug
# Watch APK   → watch/build/outputs/apk/debug/watch-debug.apk
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

# End-of-study offload: drain the whole watch buffer to the phone now
# (watch on charger; takes a 60-min wakelock, resumes on its own if interrupted):
adb -s <watch> shell am start-foreground-service \
    -n com.example.testwatch/.tracking.HrTrackingService \
    -a com.example.testwatch.DRAIN_NOW

# Watch buffer level / drain progress:
adb -s <watch> logcat -s HrTrackingService:V | grep -E "drain|buffer|EMERGENCY"
```

---

## 6. Tuning constants

| Where | Constant | Default | Why |
|---|---|---|---|
| `StorageConfig.kt` | `CHECK_INTERVAL_MS` | 5 min | How often the loop re-checks buffer size and free space. |
| `StorageConfig.kt` | `HIGH_WATER_BYTES` | 12 GB | Auto-drain threshold ≈ 37 days at ~320 MB/day; a 2–4-week study never hits it. |
| `StorageConfig.kt` | `MIN_FREE_BYTES` | 2 GB | Physical free-space floor — drain regardless of logical budget. |
| `StorageConfig.kt` | `EMERGENCY_FREE_BYTES` | 1 GB | Below this, drop oldest unsynced rows (counted in `TrackingState.droppedRows`). |
| `StorageConfig.kt` | `DRAIN_PAUSE_MS` | 200 ms | Pacing between drain rounds so BT/sensors aren't starved. |
| `HrTrackingService.kt` | `BATCH_LIMIT` | 500 samples | ~30 KB JSON; well under MessageClient 100 KB cap. |
| `HrTrackingService.kt` | `SENSOR_BATCH_LIMIT` / `MAX_SENSOR_MSG_BYTES` | 300 / 60 KB | Sensor rows per drain round, chunked under the message cap. |
| `HrTrackingService.kt` | `KEEP_RECENT_SYNCED` | 1000 rows | Keep the last N synced rows for debugging before pruning. |
| `UploadWorker.kt` | `MAX_RETRIES` | 10 | Retries with exponential backoff (WorkManager default). |

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

## 8. Provisioning (kiosk mode removed 2026-08-27)

Kiosk mode (Device Owner, lock task, HOME intent filter, PIN unlock, boot telemetry
to a placeholder URL) was deleted in the 2026-08-27 cleanup — the code lives in git
history (`455a5e1`…`dd427f3`) if fleet lockdown is ever needed again. What replaced
the parts that mattered:

- **Relaunch after reboot** (was: app-as-HOME): the watch `BootReceiver` now calls
  `startForegroundService(HrTrackingService)` on `BOOT_COMPLETED`. The app UI no
  longer auto-opens; verify on-device that a boot-started health service gets sensor
  access before fleet provisioning.
- **Watches previously provisioned as Device Owner**: remove it once with
  `adb shell dpm remove-active-admin com.example.testwatch/.admin.HrDeviceAdminReceiver`
  (or factory reset), since the receiver no longer exists in the APK.

### Per-watch provisioning steps

```bash
adb connect <watch-ip>:<port>
adb -s <watch> install -r watch/build/outputs/apk/debug/watch-debug.apk
adb -s <watch> shell pm grant com.example.testwatch android.permission.BODY_SENSORS
adb -s <watch> shell pm grant com.example.testwatch android.permission.health.READ_HEART_RATE
adb -s <watch> shell am broadcast -a com.example.testwatch.SET_PARTICIPANT_ID \
    -n com.example.testwatch/.admin.SetParticipantIdReceiver \
    --es participant_id "P042"
adb -s <watch> shell am start -n com.example.testwatch/.presentation.MainActivity
# Enable Samsung Health dev mode (manual step on watch — see §7).
```

---

## 9. Pairing watch to laptop for development

- Use the **phone's mobile hotspot**, not public/guest WiFi. Public networks (UBCvisitor, eduroam) enable client isolation, which breaks ADB peer connectivity.
- Watch sleeps WiFi aggressively → keep it on the charger with **Developer options → "Stay awake"** on, and **Settings → Connections → Wi-Fi → Always**.
- Once `adb pair` + `adb connect` succeed, an active ADB session keeps the radio alive even with the screen off.

---

## 10. Deliberate design decisions

- **No kiosk / lock-task (removed 2026-08-27).** The lockdown machinery (Device Owner, PIN, HOME hijack) added provisioning friction and code surface without protecting data; threat model = curious participant, not adversary. The one thing it provided that mattered — relaunch after reboot — moved to `BootReceiver`.
- **Continuous tracking, no Start/Stop UI.** Foreground service runs from app launch onward; participants only see live BPM. Off-wrist samples (status -1) still get recorded — downstream filtering decides what to drop.
- **Store-and-forward instead of fixed-cadence sync (2026-08-26).** The watch holds data locally (12 GB high-water ≈ 37 days) and uploads once — at threshold or on the end-of-study `DRAIN_NOW`. Rationale: a 2–4-week study window is only 4.5–9 GB of the ~18 GB usable, BT wake-ups drop to ~zero during the study (battery), and the participant's phone data plan is untouched until offload. Trade-off accepted: worst-case data loss is now the whole buffer if a watch is destroyed/lost, and there's no daily liveness signal (§11).
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
- **Daily heartbeat while storing?** With store-and-forward, a watch is silent for the whole study — a dead watch on day 2 looks identical to a healthy one until offload. A ~1-message/day status ping (buffered bytes, battery, droppedRows) needs a new Data Layer path + phone-side handling; decide with Angela whether fleet observability justifies it.
- **Drain throughput over BT vs WiFi** — measure a real multi-GB `DRAIN_NOW`; if BT-only speeds make end-of-study offload take a day, offload procedure may need the watch on lab WiFi.
- Phone-side periodic upload floor (B7) — now more important since arrivals come as one huge burst.
- Should `DRAIN_NOW` (or the high-water drain) also gzip/columnar-encode batches first (proposed in the retired sensor-arch.md §7, in git history)? ~7× fewer bytes over BT would cut drain time proportionally.

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
