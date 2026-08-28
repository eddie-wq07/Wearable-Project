# Wearable Project — File Map

Repo: `Wearable-Project` · two Gradle modules: **`watch/`** (Wear OS, `com.example.testwatch`) and **`mobile/`** (phone companion, `com.example.testwatch.mobile`).
Structured around three things: **the watch app**, **the sensors**, **the mobile app**.
Regenerated 2026-08-27 after the kiosk removal + restructure.

---

## 1. The path one data point takes

```
Samsung sensor
  → sensors/ConnectionManager (connects to Samsung Health Tracking Service)
  → sensors/TrackerSession (one per sensor, uses SensorSpec to extract fields)
  → sensors/SensorEngine (owns which sensors run, and when)
  → tracking/HrTrackingService.store{} → Room: sensor_batches / hr_samples  [WATCH DISK]
  → store-and-forward: buffer until 12 GB high-water / 2 GB free floor / DRAIN_NOW
  → sync/BatchSerializer.encode*() → JSON bytes
  → MessageClient "/hr_batch" or "/sensor_batch"  (Wear Data Layer, ~100 KB cap)
  ────────── Bluetooth ──────────
  → wear/HrBatchListenerService (phone)
  → Room: hr_samples / sensor_batches                                       [PHONE DISK]
  → enqueues UploadWorker
  → upload/UploadWorker → SFTP → misr.sauder.ubc.ca:/data1/wearables/*.json
```

Rows are marked `synced` / `uploaded` at each hop and pruned later — nothing is deleted before the next hop confirms.

---

## 2. The sensors — `watch/src/main/java/com/example/testwatch/sensors/`

The part that decides how much data exists. Everything that touches the Samsung SDK lives here.

| File | Role |
|---|---|
| `SensorSpec.kt` | **The whole sensor catalogue.** `SENSORS` list = 3 continuous (`ppg` green/IR/red, `accel` x/y/z, `skin_temp`) + 4 on-demand (`ecg`, `spo2`, `bia`, `mf_bia`). Each spec holds a stable string `id`, the `HealthTrackerType`, the mode, and an `extract` lambda mapping one SDK `DataPoint` → `{field: value}`. |
| `TrackerSession.kt` | Generic wrapper around one `HealthTracker`. Picks the right `getHealthTracker()` overload (PPG types / user profile / plain), attaches a listener, converts each callback into `List<Map<String,Number>>` with a `ts` key. |
| `SensorEngine.kt` | Orchestrator. Starts every supported CONTINUOUS spec permanently; on each alarm tick runs the ON_DEMAND specs **one at a time** (they share hardware), pausing `ppg` for the round. Knows when a measurement is "final" (`spo2` status 2, BIA progress ≥ 100). |
| `ConnectionManager.java` | Creates `HealthTrackingService`, reports connect/fail, checks HR capability. |
| `ConnectionObserver.java` | Callback interface (`onConnectionResult`, `onHeartRateAvailability`). |
| `HeartRateListener.java` | **Legacy HR lane** — HR-only tracker listener; pulls `HEART_RATE` + `HEART_RATE_STATUS`. |
| `TrackerDataSubject.java` / `TrackerObserver.java` | Tiny observer bus between the HR listener and the service. |
| `OffBodyStatus.java` | Constants for on-body / off-body. |

> **Note:** HR does *not* go through `SensorSpec`. It predates the registry and has its own listener, its own table (`hr_samples`), its own wire format, and its own message path. Everything else goes through the generic path. Worth folding in before the 70-watch fleet.

---

## 3. The watch app — `watch/`

### Service + state (`tracking/`)

| File | Role |
|---|---|
| `tracking/HrTrackingService.kt` | **The hub.** Foreground service (type `health`). Owns the SDK connection, the HR path, the `SensorEngine`, the Room writes, the measurement notification/vibration, off-body detection, and the store-and-forward drain loop. |
| `tracking/MeasureAlarmReceiver.kt` | `setAlarmClock` tick that survives doze; restarts the service with `ACTION_RUN_ROUND`. |
| `tracking/TrackingState.kt` | In-memory `MutableStateFlow`s the watch UI reads (bpm, connected, worn, current measurement, buffer telemetry, dropped-row counter). Not persisted. |

### Storage & serialization

| File | Role |
|---|---|
| `data/HrDatabase.kt` | Room DB `hr.db`, version 2, two tables. Holds `MIGRATION_1_2` that added `sensor_batches`. |
| `data/HrSample.kt` / `data/HrDao.kt` | `hr_samples` row (`ts`, `bpm`, `status`, `synced`) + queries. |
| `data/SensorBatch.kt` / `data/SensorBatchDao.kt` | `sensor_batches` row: `sensor` id, `timestampMs`, `points` (a **JSON array string** — this field is the storage-size driver), `synced`. One row = one SDK callback. |
| `data/ParticipantStore.kt` | SharedPrefs participant ID; auto-generates `P-xxxxxxxx`, overridable by ADB broadcast. |
| `sync/BatchSerializer.kt` | Wire formats: `WireBatch` (HR) and `WireSensorBatch` (everything else), plus `encodePoints()` — the JSON that lands on disk. |

### Config (`config/` package)

| File | Role |
|---|---|
| `config/StorageConfig.kt` | **Store-and-forward budget** (2026-08-26 change): 5-min check interval, 12 GB high-water, 2 GB free floor, 1 GB emergency drop, plus the measured ~320 MB/day rate math. |
| `config/SensorConfig.kt` | On-demand round timings (5-min interval, 45 s timeout) + the dummy `TrackerUserProfile` BIA needs. |

### UI, boot, admin

| File | Role |
|---|---|
| `presentation/MainActivity.kt` | Requests permissions, starts the service, renders the watch face (HR / skin temp / SpO2 / measurement prompt). |
| `presentation/theme/Theme.kt` | Wear Material 3 theme. |
| `BootReceiver.kt` | Restarts `HrTrackingService` after a reboot (replaced the kiosk HOME-launcher auto-relaunch; the old placeholder-URL boot telemetry is gone). |
| `admin/SetParticipantIdReceiver.kt` | `adb shell am broadcast ... SET_PARTICIPANT_ID` → sets participant ID. |
| `AndroidManifest.xml` | Permissions (BODY_SENSORS, health.READ_*, Samsung READ_ADDITIONAL_HEALTH_DATA, exact alarm, FGS health), LAUNCHER activity, receivers. |

> **Kiosk mode was deleted 2026-08-27** (PIN screens, lock task, Device Owner receiver, HOME intent filter, boot-notify-to-placeholder-URL). If fleet lockdown is needed later, git history has it at commit `455a5e1`–`dd427f3`.

---

## 4. The mobile app — `mobile/`

| File | Role |
|---|---|
| `wear/HrBatchListenerService.kt` | **Entry point from the watch.** `WearableListenerService` on paths `/hr_batch` and `/sensor_batch`; decodes, writes to phone Room, updates `PipelineStatus`, enqueues `UploadWorker` with `ExistingWorkPolicy.KEEP`. |
| `data/PhoneHrDatabase.kt` | Room DB `hr_phone.db`, version 2, mirrors the watch schema plus `participantId` and `receivedAt`. |
| `data/PhoneHrSample.kt` / `PhoneHrDao.kt` | Phone-side `hr_samples` + `unuploaded()` (oldest-first, limit 5000). |
| `data/PhoneSensorBatch.kt` / `PhoneSensorDao.kt` | Phone-side `sensor_batches` + `counts()` for the dashboard. |
| `upload/UploadWorker.kt` | **The upload.** Builds JSON payloads (adds `watch_serial`, `uploaded_at`, `is_test = true`), writes them over **SFTP** via sshj, marks uploaded, prunes. Contains the Bouncy Castle + curve25519 workarounds Android needs. Retries up to 10 times. |
| `config/ServerConfig.kt` | SFTP host/port/user/password. **Gitignored — never committed**; copy `ServerConfig.kt.example` and fill in. |
| `status/PipelineStatus.kt` | In-memory counters for the dashboard (batches received, uploads OK/failed, totals). |
| `StatusActivity.kt` | Live pipeline dashboard UI. |
| `boot/BootReceiver.kt` | Re-enqueues `UploadWorker` after phone reboot. |

---

## 5. Config — what lives where, and why

Requested layout: all config in one place, separated from logic. Done where the toolchain allows:

| Config | Location | Movable? |
|---|---|---|
| Proguard rules | `config/proguard-rules.pro` (shared; both build files point at it) | ✅ moved |
| Watch runtime knobs | `watch/.../config/` (`StorageConfig`, `SensorConfig`) | ✅ consolidated into a `config` package — Kotlin objects must live in a module's source set, so "one root folder" is impossible for them; a per-module `config` package is the closest legal equivalent |
| Server credentials | `mobile/.../config/ServerConfig.kt` (+ `.example`) | ✅ same as above |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/` (wrapper + `libs.versions.toml`), `local.properties` | repo root / module roots | ❌ **cannot move** — Gradle resolves these at fixed conventional paths before any build script runs |
| `AndroidManifest.xml` (×2) | `*/src/main/` | ❌ pinned by the Android Gradle Plugin source-set layout |
| `.idea/` | repo root | ❌ owned and regenerated by Android Studio |
| `app/lint.xml` | — | 🗑 deleted — it only suppressed warnings for `tile_preview.png` files that no longer exist |

---

## 6. Everything else at the top level

Docs were foldered into `docs/` on 2026-08-27 so the repo root holds only README, the
three code folders, and the build files Gradle pins to fixed paths (the annotated tree
in `README.md` explains every root item and why the build files can't move).

| File | Role |
|---|---|
| `README.md` | Entry point; the high-level annotated tree. Only doc allowed at root. |
| `docs/handoff.md` | Living session-to-session handoff (device quirks, provisioning, open questions). |
| `docs/architecture.mermaid` / `docs/architecture.svg` | The diagram version of this map. |
| `scripts/dev-up.sh` | One-shot dev bring-up: adb-over-wifi connect, launch watch app, VS Code on `misr:/data1/wearables`, live `ls -lt` feed. |
| `config/proguard-rules.pro` | Shared release-shrinker rules (currently all defaults; minify is off). |
| `gradle/libs.versions.toml` | The one list of every dependency + version both modules pull from. |

Deleted 2026-08-27: `sensor-arch.md` (pre-implementation plan for the sensor layer, superseded by the code above — in git history if needed) and `PROJECT_CONTEXT.md` (stale snapshot of the Drive folder; Drive is authoritative — link in README).

---

## 7. Flagged while mapping

1. **`is_test = true` is hardcoded** in both upload payloads (`UploadWorker.kt`).
2. **HR bypasses the `SensorSpec` architecture entirely.** Two code paths, two tables, two wire formats for what is conceptually one continuous sensor. Worth folding in before 70 watches, not after.
3. **Uploads land as JSON files on the server filesystem, not in MySQL.** Either the phone writes to MySQL directly or a server-side ingester reads `/data1/wearables/*.json` — that decision is not in the code yet.
4. **SFTP auth is password-based with a promiscuous host-key verifier** — switch to a pinned host key + SSH key auth before the fleet build (`UploadWorker.uploadOverSftp()`).
5. **After a reboot the watch no longer auto-opens the app** (that was the kiosk HOME filter). `BootReceiver` restarts the tracking service directly instead; verify on-device that a boot-started health service gets sensor access before fleet provisioning.
