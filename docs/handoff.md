# Wearable Project — Handoff

Living doc. Update at the end of every working session so the next session can pick up cold.

---

## 1. Goal

Build a working pipeline that collects health data from a **Samsung Galaxy Watch** and uploads it **directly to a lab-hosted server over SFTP** — the phone relay was removed 2026-08-29; the watch is standalone. Used by the UBC Data + AI Lab (Sauder School of Business) in collaboration with SRCA.

The deployment target is a research study: ~60–80 Galaxy Watches handed to participants. (Kiosk/lock-task mode was removed 2026-08-27 — see §8.)

### Hardware (as of current session)
- **Watch:** Galaxy Watch 8 (SHJE), model `SM-L320`. Wear OS, Samsung Health Sensor SDK 1.4.1, dev-mode toggle on, paired to phone via Galaxy Wearable.
- **Phone:** Galaxy A35 5G, model `SM-A356W`. No longer part of the data path (relay removed 2026-08-29); still useful as the dev hotspot (§9).

### Prototype phases
| # | Phase | Status |
|---|---|---|
| 1 | Basic Wear OS app skeleton | ✅ Done |
| 2 | SDK connection on watch (Samsung Health Sensor SDK → live HR) | ✅ Done |
| 3 | On-watch data collection → server upload | ✅ Direct SFTP from the watch (phone relay removed 2026-08-29) |
| 4 | Add more sensors (PPG, ECG, SpO2, skin temp, accel, BIA) | ⏳ Not started |
| 5 | Production hardening, reliability | 🟡 Partial hardening; kiosk mode built then removed (2026-08-27); distribution-partner submission TODO |

---

## 2. Architecture

One Gradle module. The watch is standalone: it collects, buffers locally, and uploads
straight to the server whenever it is charging on WiFi (2026-08-29 — replaced the
phone relay; rationale in §10).

```
┌──────────────── Galaxy Watch 8 ────────────────┐         ┌────────── MISR server ──────────┐
│ Samsung SDK: HR 1Hz + SensorEngine             │         │ /data1/wearables/<participant>/ │
│  (ppg 25Hz, accel 25Hz, skin_temp,             │         │   hr_*.json                     │
│   on-demand rounds every 5 min)                │         │   sensors_*.json                │
│   │                                            │         │                                 │
│ HrTrackingService (foreground)                 │         │ (lab ingestion reads files      │
│   │ writes each sample/batch                   │  SFTP   │  into SQLite)                   │
│   ▼                                            │ (sshj,  │                                 │
│ watch Room (hr.db) — STORE-AND-FORWARD         │ per-    │                                 │
│   │                                            │ watch   │                                 │
│   ▼                                            │ key,    │                                 │
│ UploadWorker (WorkManager)                     │ pinned  │                                 │
│   runs ONLY while charging on unmetered WiFi;  │ host    │                                 │
│   asked for an immediate pass at 12 GB high-   │ key)    │                                 │
│   water / 2 GB free floor / manual DRAIN_NOW   │────────▶│                                 │
└────────────────────────────────────────────────┘         └─────────────────────────────────┘
```

### Module layout
```
:watch  (Wear OS) — applicationId com.example.testwatch
```
(Renamed `:app` → `:watch` 2026-08-27; the `:mobile` companion app was deleted 2026-08-29
along with the Wear Data Layer path — in git history if ever needed.)

### Watch module (`:watch`)
```
java/com/example/testwatch/
├── BootReceiver.kt              Restarts HrTrackingService after reboot
├── admin/
│   ├── SetParticipantIdReceiver.kt   ADB-broadcast → SharedPrefs
│   └── GetPublicKeyReceiver.kt       ADB-broadcast → this watch's SSH pubkey (provisioning)
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
│   ├── SensorConfig.kt          On-demand round timings + BIA user profile
│   └── ServerConfig.kt          Host, shared account, pinned host keys, Keystore alias (no secrets)
├── data/
│   ├── HrSample.kt              Room entity (id, ts, bpm, status, synced)
│   ├── HrDao.kt                 unsynced / markSynced / pruneSynced / count
│   ├── HrDatabase.kt            hr.db
│   └── ParticipantStore.kt      SharedPrefs wrapper for participant_id
├── tracking/
│   ├── HrTrackingService.kt     Foreground service: SDK + Room writes + storage monitor
│   ├── MeasureAlarmReceiver.kt  Doze-proof alarm tick for on-demand rounds
│   └── TrackingState.kt         MutableStateFlow snapshot for the UI (+ buffer telemetry)
├── sync/
│   └── BatchSerializer.kt       Upload JSON payload building (same shape the phone relay used)
├── upload/
│   ├── UploadWorker.kt          sshj SFTP straight to the server; charge+WiFi constrained
│   └── WatchKeys.kt             Keystore EC P-256 keypair + sshj bridge + authorized_keys export
└── presentation/
    ├── MainActivity.kt          Live BPM display
    └── theme/Theme.kt
```

### Store-and-forward buffering (2026-08-26; upload leg direct-to-server since 2026-08-29)

The watch does not sync on a fixed cadence. It **stores everything locally in
`hr.db` and uploads whenever it is charging on unmetered WiFi** — plus immediate
passes on storage thresholds or a manual trigger. Thresholds live in
`watch/.../config/StorageConfig.kt`; upload slicing in `upload/UploadWorker.kt`.

**Watch storage (the budget this is sized against)**

| Fact | Value |
|---|---|
| Galaxy Watch 8 flash | 32 GB advertised, **~18 GB actually usable** after Wear OS + preloads |
| RAM | 2 GB — irrelevant to buffering; every sample lands in SQLite via Room |
| Write rate, current sensor set | **~320 MB/day** (ppg 25 Hz ≈ 194 MB + accel 25 Hz ≈ 93 MB + HR 1 Hz ≈ 3.5 MB + skin_temp ≈ 0.1 MB + on-demand rounds ≈ 2 MB, ×1.15 SQLite overhead) |
| Time to fill usable storage | **~56 days** |
| One 2–4-week study window | **4.5–9 GB** — fits comfortably below the high-water mark |

**Continuous sensors buffered and uploaded** (the `SENSORS` registry in
`SensorSpec.kt`): `ppg` (green/IR/red + statuses, 25 Hz), `accel` (x/y/z,
25 Hz), `skin_temp` (~1/min), plus the legacy 1 Hz `hr` path (`hr_samples`
table). On-demand rounds (`spo2`, `bia`, `mf_bia`, `ecg`) every 5 min ride the
same buffer.

**Timing / trigger logic:**

| Trigger | Condition | Behavior |
|---|---|---|
| Standing schedule | **charging + unmetered WiFi** (daily WorkManager periodic; at most one pass per 24 h window, fired when constraints are met) | Upload whatever is buffered, then resume storing |
| High water | unsynced bytes ≥ **12 GB** (~37 days of data; `syncLoop` checks every 5 min) | Ask for an immediate upload pass (still gated on charger+WiFi) |
| Free-space floor | device free < **2 GB** | Same |
| Emergency | device free < **1 GB** | Drop oldest unsynced rows (20 k/table per pass), counted in `TrackingState.droppedRows` — **nonzero means a data gap; never silent** |
| Manual | `DRAIN_NOW` broadcast (see §5) | Immediate constrained pass — **the intended end-of-study offload; put the watch on its charger with lab WiFi** |

An upload pass drains in bounded slices (5 000 HR rows or ≤4 MB of sensor JSON
per file), SFTP-putting one file per slice into
`/data1/wearables/<participantId>/` and marking rows synced after each file, so
an interrupted pass resumes where it stopped. JobScheduler stops long-running
workers around the 10-minute mark — a multi-GB backlog therefore drains across
several charge-window runs (at WiFi SFTP speeds each 10-min window moves
roughly 1–3 GB; an overnight charge covers a full study backlog). If the watch
never sees charger+WiFi, it keeps buffering — a 2–4-week study window (4.5–9 GB)
fits the budget with a wide margin either way.

**Known consequences to plan around:**
- **The `hr.db` file never shrinks** after a drain (SQLite reuses pages but
  doesn't return them without VACUUM). Expected and fine: the file plateaus at
  the high-water mark.
- **Participants' home WiFi uploads nightly** (watch charges on a charger with
  WiFi in range). A watch with no WiFi all study simply behaves like the old
  design: silent until the end-of-study `DRAIN_NOW` on lab WiFi.
- **No liveness signal without WiFi.** With nightly charging on WiFi the upload
  itself is the heartbeat; a watch that never uploads mid-study is either dead
  or WiFi-less — indistinguishable from the server side (§11).

---

## 3. Current state

- [x] App installs on Galaxy Watch 8 over ADB-WiFi.
- [x] Runtime permissions accepted (`BODY_SENSORS`, `health.READ_HEART_RATE`, on API 33+ `POST_NOTIFICATIONS`).
- [x] Samsung Health Tracking Service connects (status 1 = valid HR).
- [x] **Continuous background tracking** via `HrTrackingService` (foreground service, type `health`). No Start/Stop UI — opens straight to live BPM display.
- [x] HR samples written to Room (`hr.db`) on every callback.
- [x] **Store-and-forward** (2026-08-26): buffers locally; uploads at 12 GB high-water / 2 GB free floor / manual `DRAIN_NOW` — and, since 2026-08-29, on the standing daily charge+WiFi schedule.
- [x] **Direct SFTP upload** (2026-08-29, compiles; on-device verification below): watch uploads to `/data1/wearables/<participantId>/` with its own Keystore key and pinned host keys. Phone companion deleted the same day (Data Layer round-trip and phone-relay SFTP had been verified 2026-07-30 before removal).
- [x] Kiosk mode removed (2026-08-27); watch `BootReceiver` now restarts the tracking service after reboot instead.

### Pending verification on device
- [ ] **sshj ↔ Android Keystore signing end-to-end**: first `UploadWorker` run on real hardware — watch logcat for auth failure vs `SFTP put`. This is the one genuinely novel integration (see §10); if the TEE-backed key won't sign through sshj, the flagged fallback is generating the key non-Keystore and storing it app-private.
- [ ] Provision this watch's public key: `GET_PUBKEY` broadcast → append to `~edward/.ssh/authorized_keys` on MISR (§4).
- [ ] Overnight doze soak test: confirm the sensor's hardware buffer survives hours of doze, not just minutes.
- [ ] **Upload throughput unmeasured**: time a multi-GB `DRAIN_NOW` over WiFi SFTP, including how many 10-min WorkManager windows it takes.
- [ ] Verify a real `DRAIN_NOW` on device: buffer a few hours of data, fire the broadcast with the watch on charger+WiFi, confirm per-file `SFTP put` lines in logcat and rows landing server-side.
- [ ] Server dir has junk test files (`hr_*.json` with June-11 timestamps + zero-BPM data) — archive with:
      `mkdir -p /data1/wearables/old_test_data && mv /data1/wearables/hr_*.json /data1/wearables/old_test_data/`
      (Both device DBs were wiped clean on 2026-07-30 after the timestamp fix; local backups in session scratchpad.)

---

## 4. Setup the user must do before the pipeline lands data on the server

1. **Provision the watch's SSH key** (once per watch — no passwords anywhere; the watch
   authenticates as the shared `edward` account with its own keypair):
   ```bash
   # On the watch: prints the authorized_keys line (also in logcat, tag GetPublicKey)
   adb -s <watch> shell am broadcast -a com.example.testwatch.GET_PUBKEY \
       -n com.example.testwatch/.admin.GetPublicKeyReceiver
   # On the server: append that line
   ssh -p 16800 edward@misr.sauder.ubc.ca "echo '<the line>' >> ~/.ssh/authorized_keys"
   ```
   Host, port, account, pinned host-key fingerprints, and the Keystore alias all live in
   `watch/.../config/ServerConfig.kt` (committed — it contains no secrets). Revoke a lost
   watch by deleting its line from `authorized_keys`.
2. **Upload directory** — nothing to create: the watch `mkdir`s its own
   `/data1/wearables/<participantId>/` on first upload (the base dir is group-writable;
   shared-account model per docs/new-arch-build.md — no chroot, watches are not isolated
   from each other).
3. **Server-side ingestion** — write a small script that scans `/data1/wearables/*/` for `hr_*_*.json` / `sensors_*_*.json` files and inserts into SQLite. Every timestamp in the files is a readable string in the format `MMM d yyyy, h:mm:ss a zzz` (US locale, watch-local timezone — handle both `PDT` and `PST`); there are no epoch values and no `watch_serial` in the uploads. The script parses `time` / `received_at` / `uploaded_at` back into integer epoch-ms columns for indexing. Suggested schema:
   ```sql
   CREATE TABLE hr_samples (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       participant_id TEXT NOT NULL,
       time_ms INTEGER NOT NULL,        -- parsed from the readable `time` string
       bpm INTEGER NOT NULL,
       status INTEGER NOT NULL,
       received_at_ms INTEGER NOT NULL, -- parsed from `received_at`
       uploaded_at_ms INTEGER NOT NULL, -- parsed from the file-level `uploaded_at`
       ingested_at_ms INTEGER NOT NULL
   );
   CREATE INDEX idx_pid_ts ON hr_samples (participant_id, time_ms);
   ```
   HR file format (each upload is a single JSON file; full spec including the sensors file is in `docs/upload-format.md`):
   ```json
   {
     "uploaded_at": "Sep 1 2026, 11:20:00 PM PDT",
     "is_test": true,
     "samples": [
       { "participant_id": "1A", "time": "Sep 1 2026, 10:20:00 PM PDT", "bpm": 72, "status": 1, "received_at": "Sep 1 2026, 11:20:00 PM PDT" }
     ]
   }
   ```
4. **Participant ID** — auto-generated on first launch as `P-<8-char UUID slice>` and persisted in SharedPreferences. Override per watch with the study ID (`<watch number><cycle letter>`, e.g. `1A` = watch 1, first cycle — scheme in `docs/upload-format.md`):
   ```bash
   adb shell am broadcast -a com.example.testwatch.SET_PARTICIPANT_ID \
       -n com.example.testwatch/.admin.SetParticipantIdReceiver \
       --es participant_id "1A"
   ```

---

## 5. Build / install commands

```bash
# Watch APK
./gradlew :watch:installDebug

# Or build without installing
./gradlew :watch:assembleDebug
# → watch/build/outputs/apk/debug/watch-debug.apk
```

### Useful adb commands
```bash
# Watch logcat for the tracking service + uploader:
adb -s <watch>     logcat -s HrTrackingService:V HeartRateListener:V ConnectionManager:V UploadWorker:V

# This watch's SSH public key (for authorized_keys, §4):
adb -s <watch> shell am broadcast -a com.example.testwatch.GET_PUBKEY \
    -n com.example.testwatch/.admin.GetPublicKeyReceiver

# End-of-study offload: request an immediate upload pass
# (runs the moment the watch is charging on WiFi; resumes on its own if interrupted):
adb -s <watch> shell am start-foreground-service \
    -n com.example.testwatch/.tracking.HrTrackingService \
    -a com.example.testwatch.DRAIN_NOW

# Watch buffer level / upload progress:
adb -s <watch> logcat -s HrTrackingService:V UploadWorker:V | grep -E "SFTP|upload|buffer|EMERGENCY"
```

---

## 6. Tuning constants

| Where | Constant | Default | Why |
|---|---|---|---|
| `StorageConfig.kt` | `CHECK_INTERVAL_MS` | 5 min | How often the loop re-checks buffer size and free space. |
| `StorageConfig.kt` | `HIGH_WATER_BYTES` | 12 GB | Auto-drain threshold ≈ 37 days at ~320 MB/day; a 2–4-week study never hits it. |
| `StorageConfig.kt` | `MIN_FREE_BYTES` | 2 GB | Physical free-space floor — drain regardless of logical budget. |
| `StorageConfig.kt` | `EMERGENCY_FREE_BYTES` | 1 GB | Below this, drop oldest unsynced rows (counted in `TrackingState.droppedRows`). |
| `UploadWorker.kt` | `HR_SLICE_ROWS` | 5000 rows | HR rows per upload file (~500 KB JSON). |
| `UploadWorker.kt` | `MAX_FILE_BYTES` | 4 MB | Sensor-JSON cap per upload file — bounds memory and makes interrupted passes cheap to resume. |
| `UploadWorker.kt` | `KEEP_RECENT_SYNCED` | 1000 rows | Keep the last N synced rows for debugging before pruning. |
| `UploadWorker.kt` | `MAX_RETRIES` | 10 | Retries with exponential backoff (WorkManager default). |
| `UploadWorker.kt` | periodic interval | 24 h | Standing charge+WiFi schedule — at most one upload pass a day; constraints do the real gating. |

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
With Bluetooth off, the watch never synced time and drifted 7 weeks stale, mislabeling every sample. `auto_time=1` is now set; provisioning must verify the clock is correct. Now that the watch runs standalone (no paired phone as time source), the clock depends on network time over WiFi — a watch that hits its nightly charge+WiFi upload window also gets time sync there; a WiFi-less watch has **no time source all study**, so verify drift at the end-of-study offload. Server ingestion should sanity-check the parsed `time` against the upload time and flag watches whose clock has drifted.

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
    --es participant_id "1A"
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
- **Store-and-forward instead of fixed-cadence sync (2026-08-26).** The watch holds data locally (12 GB high-water ≈ 37 days) and uploads opportunistically — charge+WiFi windows, thresholds, or the end-of-study `DRAIN_NOW`. A 2–4-week study window is only 4.5–9 GB of the ~18 GB usable, so a watch that never sees WiFi still fits comfortably. Trade-off accepted: worst-case data loss is the whole buffer if a watch is destroyed/lost.
- **Direct watch→server SFTP; phone relay deleted (2026-08-29).** The watch is fully standalone: one codebase, one durable buffer, no Wear Data Layer, no participant phone in the loop. Upload is gated on charging + unmetered WiFi (`WorkManager` constraints) — the radio-sleep concern that originally justified the phone relay doesn't apply on the charger, and the phone-data-plan concern disappears entirely.
- **Per-watch SSH keys in the Android Keystore, shared server account.** Each watch generates a non-exportable EC P-256 keypair in the TEE (raw key unreadable even with debug access) and authenticates as `edward` with its own key line in `authorized_keys` — revocable per watch. Chrooted per-watch server accounts need root we don't have (docs/new-arch-build.md); accepted trade-off: watches are not isolated from each other server-side.
- **Pinned host keys (2026-08-29).** `PromiscuousVerifier` is gone; the three MISR host-key SHA256 fingerprints are hardcoded in `ServerConfig.kt`.
- **No `EncryptedSharedPreferences` for participant ID.** It's a non-secret tag; salted hash adds nothing.

---

## 11. Open questions (still pending)

- Start Samsung distribution-partnership submission now in parallel?
- **Do participants' watches get WiFi?** The charge+WiFi upload gate means a watch with saved home-WiFi credentials uploads nightly (which doubles as a liveness heartbeat); one without WiFi is silent until the end-of-study offload on lab WiFi. Decide with Angela whether provisioning should include asking participants to add their home network — it changes fleet observability from "nothing for a month" to "nightly".
- **Heartbeat for WiFi-less watches?** If some watches won't have WiFi, a dead watch on day 2 still looks identical to a healthy one until offload. A tiny daily status file over any available network would need its own path — decide if it's worth it.
- Should upload files also be gzipped (proposed in the retired sensor-arch.md §7, in git history)? ~7× fewer bytes would cut upload-window count proportionally; server ingestion would need to gunzip.
- **Participant ID reassignment on watch reuse.** The study cycles 20 watches across participants monthly (2 out at a time, ~1 month each before returning and reissuing). `ParticipantStore.kt` persists whatever ID it's given indefinitely — there's no reset/clear step. If a returned watch is reissued to a new participant without re-running the `SET_PARTICIPANT_ID` ADB broadcast first, the new participant's data will silently upload under the previous participant's ID with no error or warning anywhere in the pipeline. Needs either (a) a documented manual step in the return/reissue checklist confirming ID reassignment before a watch goes back out, or (b) a code-level safeguard — e.g. clearing the stored ID on some explicit "watch returned" trigger, or a UI/log check that surfaces the current participant ID when the watch boots, so a mismatch is visible before data starts flowing.

---

## 12. External references

- Samsung Health Sensor SDK guide: https://developer.samsung.com/health/sensor/guide/
- HR Tracker sample: https://developer.samsung.com/health/sensor/sample/hr-tracker.html
- sshj on GitHub: https://github.com/hierynomus/sshj
- UBC Data + AI Lab: https://blogs.ubc.ca/analyticsailab/

### Samsung contacts (May 2026 email thread)
- **Praveen Timmashetty** — SDK technical lead.
- **Jennifer Li** — NA Health Partnerships intake.
- **Zijing (Susie) Shao** — SRCA Vancouver, internal liaison.

### UBC contacts
- **Prof. Gene Moo Lee** — supervisor (Sauder ISA).
- **Angela Kwon** — PhD student, primary partner contact.
