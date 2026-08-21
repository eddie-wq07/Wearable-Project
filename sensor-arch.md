# Wearable Project — Multi-Sensor Architecture Plan

**Target:** Friday Aug 21, 2026 demo — Sauder PhD office, for Angela Kwon + lab.
**Scope:** all sensor types integrated + on-demand sensor architecture.
**Baseline audited:** commit `84aee1f` (2026-07-30), Samsung Health Sensor SDK 1.4.1, `targetSdk = 36`.

---

## 0. Read this first — triage

Three things gate the demo. In priority order:

| # | Gate | Why it's a gate | Time |
|---|---|---|---|
| **G1** | **Missing Android permissions** | `targetSdk = 36` (Android 16). On Android 16 the SDK no longer accepts bare `BODY_SENSORS` for ECG/EDA/PPG/BIA/MF-BIA, SpO2, skin temp, or accel. The manifest declares none of the new ones. **Every non-HR tracker will fail with `PERMISSION_ERROR` until this is fixed.** | 20 min |
| **G2** | **Unknown device capability** | Nobody has run `getSupportHealthTrackerTypes()` on the actual SM-L320. Demo scope should be set by what the watch reports, not by what the enum contains. | 20 min |
| **G3** | **HR-shaped abstractions** | `TrackerObserver.onHeartRateChanged(ts, status, bpm)`, `HrSample(ts,bpm,status)`, `WireSample(ts,bpm,status)`, table `hr_samples`. Adding 10 sensors by copy-paste means 10 listeners, 10 entities, 10 tables, 10 upload paths. | the rest |

**Do G1 and G2 before writing any architecture code.** They are cheap, and G2's output determines what §4 has to support.

### Honest scoping advice for the presentation

"All sensor types integrated" splits into three groups that are *not* equally deliverable, and the split is worth saying out loud to Angela rather than discovering it during the study:

- **Passively collectable** (works on a wrist, participant unaware): HR, accelerometer, PPG (green/IR/red), EDA, skin temperature, SpO2 on-demand.
- **Requires active participant cooperation**: **BIA, MF-BIA, and ECG need the participant to hold a fingertip on the watch frame for ~30 s.** They cannot be sampled silently in kiosk mode. Integrating them is a code exercise; *collecting* them in a 60–80 person study requires a prompt-and-comply UX and a compliance-rate assumption in the study design.
- **Not applicable**: `SWEAT_LOSS` requires an `ExerciseType` + a running exercise session.

Recommended framing for Friday: **"the architecture accepts all 19 tracker types; N are verified live on the watch today; BIA/ECG/MF-BIA are wired but gated on a participant-interaction flow we need a study-design decision on."** That is a stronger result than claiming 19/19 and being asked to show ECG.

---

## 1. G1 — Permission matrix (Android 16 / `targetSdk 36`)

Per Samsung's permission guide, on Android 16+:

| Tracker | Required permission |
|---|---|
| `HEART_RATE*` | `android.permission.health.READ_HEART_RATE` ✅ already declared |
| `ECG_ON_DEMAND`, `EDA_CONTINUOUS`, `PPG_*`, `BIA_ON_DEMAND`, `MF_BIA_ON_DEMAND` | `com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA` ❌ **missing** |
| `SPO2_ON_DEMAND` | `android.permission.health.READ_OXYGEN_SATURATION` ❌ **missing** |
| `SKIN_TEMPERATURE*` | `android.permission.health.READ_SKIN_TEMPERATURE` ❌ **missing** |
| `ACCELEROMETER*` | `android.permission.ACTIVITY_RECOGNITION` ❌ **missing** |
| `SWEAT_LOSS` | `ACTIVITY_RECOGNITION` + `READ_ADDITIONAL_HEALTH_DATA` ❌ **missing** |

### Patch — `app/src/main/AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.health.READ_OXYGEN_SATURATION" />
<uses-permission android:name="android.permission.health.READ_SKIN_TEMPERATURE" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA" />
<!-- keep BODY_SENSORS for the Android <=15 path -->
```

### Patch — provisioning grants (add to the playbook in `handoff.md` §8)

```bash
P=com.example.testwatch
adb shell pm grant $P android.permission.BODY_SENSORS
adb shell pm grant $P android.permission.health.READ_HEART_RATE
adb shell pm grant $P android.permission.health.READ_OXYGEN_SATURATION
adb shell pm grant $P android.permission.health.READ_SKIN_TEMPERATURE
adb shell pm grant $P android.permission.ACTIVITY_RECOGNITION
adb shell pm grant $P com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA
```

`READ_ADDITIONAL_HEALTH_DATA` is a Samsung-defined permission; if `pm grant` rejects it as unknown, that itself is the answer — it means the platform doesn't expose it to a non-partner app, and ECG/EDA/PPG/BIA are out of reach until the Samsung distribution-partner submission lands. **Determining which of these two worlds you're in is the single highest-value 20 minutes available tonight.**

Also extend the runtime-request array at **`MainActivity.kt:48–51`** — it currently builds only `BODY_SENSORS`, `READ_HEART_RATE` (gated on `SDK_INT >= 36`) and `POST_NOTIFICATIONS`. Because the service now starts from `onRequestPermissionsResult` (the race fix in `handoff.md` §7), a tracker whose permission isn't in that list will start and then fail asynchronously with `PERMISSION_ERROR` rather than failing loudly at startup.

---

## 2. G2 — Capability probe (run this first)

Drop this in as a temporary debug path. It answers, in one logcat dump, exactly what the SM-L320 will give you.

```kotlin
// app/src/main/java/com/example/testwatch/debug/CapabilityProbe.kt
package com.example.testwatch.debug

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType

object CapabilityProbe {
    private const val TAG = "CapabilityProbe"

    fun run(service: HealthTrackingService) {
        val supported = try {
            service.trackingCapability.supportHealthTrackerTypes
        } catch (t: Throwable) {
            Log.e(TAG, "capability query failed", t); return
        }
        Log.i(TAG, "SDK version: ${'$'}{service.version}")
        Log.i(TAG, "=== supported (${'$'}{supported.size}) ===")
        supported.forEach { Log.i(TAG, "  SUPPORTED  ${'$'}it") }

        Log.i(TAG, "=== instantiation attempt for every enum constant ===")
        for (type in HealthTrackerType.values()) {
            val inList = type in supported
            try {
                service.getHealthTracker(type)          // may throw for profile/ppg-set types
                Log.i(TAG, "  OK       ${'$'}type (inCapabilityList=${'$'}inList)")
            } catch (t: Throwable) {
                Log.i(TAG, "  FAIL     ${'$'}type (inCapabilityList=${'$'}inList) -> ${'$'}{t.javaClass.simpleName}: ${'$'}{t.message}")
            }
        }
    }
}
```

Call it from `HrTrackingService.onConnectionResult(true)` behind a `BuildConfig.DEBUG` check. Read with:

```bash
adb -s <watch> logcat -c && adb -s <watch> logcat -s CapabilityProbe:V
```

**Expect three distinct outcomes and treat them differently:**

- `SUPPORTED` + `OK` → demo it live.
- in capability list but instantiation throws → needs `TrackerUserProfile` or a `Set<PpgType>` (see §3), *not* a capability problem.
- `PERMISSION_ERROR` / `SDK_POLICY_ERROR` at `setEventListener` time → G1 or the Samsung dev-mode toggle (`handoff.md` §7), not a code problem. Note that these arrive **asynchronously via `TrackerEventListener.onError`**, not as an exception from `getHealthTracker` — so the probe above will report `OK` for a tracker you cannot actually read. Extend it to attach a listener for ~5 s per tracker if you want the full truth.

---

## 3. Verified SDK surface (decompiled from `samsung-health-sensor-api-1.4.1.aar`)

Field names below are exact — taken from the AAR's constant pool, not from memory. Use them verbatim.

### 3.1 `HealthTrackerType` — all 19 constants

```
ACCELEROMETER                 ACCELEROMETER_CONTINUOUS      BIA_ON_DEMAND
ECG_ON_DEMAND                 EDA_CONTINUOUS                HEART_RATE
HEART_RATE_CONTINUOUS         MF_BIA_ON_DEMAND              PPG_CONTINUOUS
PPG_GREEN                     PPG_IR                        PPG_ON_DEMAND
PPG_RED                       SKIN_TEMPERATURE              SKIN_TEMPERATURE_CONTINUOUS
SKIN_TEMPERATURE_ON_DEMAND    SPO2                          SPO2_ON_DEMAND
SWEAT_LOSS
```

The bare `PPG_GREEN` / `PPG_IR` / `PPG_RED` / `HEART_RATE` / `SPO2` / `SKIN_TEMPERATURE` / `ACCELEROMETER` constants are the legacy pre-1.3 spellings, and their `ValueKey` sets (`PpgGreenSet`, `PpgIrSet`, `PpgRedSet`) carry `@Deprecated`. **Use the `_CONTINUOUS` / `_ON_DEMAND` variants and `ValueKey.PpgSet`.**

### 3.2 `getHealthTracker` overloads (from the AAR method descriptors)

```java
HealthTracker getHealthTracker(HealthTrackerType type);
HealthTracker getHealthTracker(HealthTrackerType type, TrackerUserProfile profile);          // BIA, MF_BIA, SWEAT_LOSS
HealthTracker getHealthTracker(HealthTrackerType type, TrackerUserProfile p, ExerciseType e); // SWEAT_LOSS
HealthTracker getHealthTracker(HealthTrackerType type, Set<PpgType> ppgTypes);                // PPG_CONTINUOUS, PPG_ON_DEMAND
```

`PpgType` has exactly two constants: `GREEN`, `RED`. (`ValueKey.PpgSet` also exposes `PPG_IR`, so IR appears to come along with one of these rather than being separately selectable — verify on device.)

`TrackerUserProfile.Builder`: `setHeight(float)`, `setWeight(float)`, `setAge(int)`, `setGender(int)`, `build()`. The service calls `validateUserProfile(...)` and throws `IllegalArgumentException` for BIA / MF_BIA / SWEAT_LOSS without one.

### 3.3 `HealthTracker`

```java
void setEventListener(TrackerEventListener l);
void unsetEventListener();
void flush();                    // <-- see §6, this is important and currently unused
```

`TrackerError` has exactly two constants: `PERMISSION_ERROR`, `SDK_POLICY_ERROR`.

### 3.4 `ValueKey` sets — exact field names and types

| Set | Fields | Type |
|---|---|---|
| `HeartRateSet` | `HEART_RATE`, `HEART_RATE_STATUS`, `IBI_LIST`, `IBI_STATUS_LIST` | Integer (⚠ `IBI_LIST`/`IBI_STATUS_LIST` are **`List<Integer>`**, not scalars) |
| `AccelerometerSet` | `ACCELEROMETER_X`, `ACCELEROMETER_Y`, `ACCELEROMETER_Z` | Integer |
| `PpgSet` | `PPG_GREEN`, `PPG_IR`, `PPG_RED`, `GREEN_STATUS`, `IR_STATUS`, `RED_STATUS` | Integer |
| `EcgSet` | `ECG_MV`, `LEAD_OFF`, `MAX_THRESHOLD_MV`, `MIN_THRESHOLD_MV`, `PPG_GREEN`, `SEQUENCE` | Float + Integer |
| `SpO2Set` | `SPO2`, `STATUS`, `HEART_RATE`, `ACCURACY_FLAG`, `O2S` | Integer |
| `SkinTemperatureSet` | `OBJECT_TEMPERATURE`, `AMBIENT_TEMPERATURE`, `STATUS` | Float + Integer |
| `EdaSet` | `SKIN_CONDUCTANCE`, `STATUS` | Float + Integer |
| `BiaSet` | `BASAL_METABOLIC_RATE`, `BODY_FAT_MASS`, `BODY_FAT_RATIO`, `BODY_IMPEDANCE_DEGREE`, `BODY_IMPEDANCE_MAGNITUDE`, `FAT_FREE_MASS`, `FAT_FREE_RATIO`, `SKELETAL_MUSCLE_MASS`, `SKELETAL_MUSCLE_RATIO`, `TOTAL_BODY_WATER`, `PROGRESS`, `STATUS` | Float + Integer |
| `MfBiaSet` | `BODY_IMPEDANCE_MAGNITUDE_{5K,10K,50K,250K}`, `BODY_IMPEDANCE_PHASE_{5K,10K,50K,250K}`, `PROGRESS`, `STATUS` | Float + Integer |
| `SweatLossSet` | `SWEAT_LOSS`, `STATUS` | Float + Integer |

**`PROGRESS` on `BiaSet` / `MfBiaSet` is your on-demand completion signal** — the measurement is finished when it reaches 100. That is the state machine hook §5 needs.

### 3.5 Sampling rates (Samsung data-specifications page)

| Tracker | Rate |
|---|---|
| `HEART_RATE_CONTINUOUS` | 1 Hz |
| `EDA_CONTINUOUS` | 1 Hz (Watch8+ only — you have a Watch 8 ✅) |
| `ACCELEROMETER_CONTINUOUS` | **25 Hz** |
| `PPG_CONTINUOUS` | **25 Hz** |
| `PPG_ON_DEMAND` | **100 Hz** |
| `ECG_ON_DEMAND` | **500 Hz** |
| `SKIN_TEMPERATURE_CONTINUOUS` | unspecified (Watch5+) |
| `BIA` / `MF_BIA` / `SPO2_ON_DEMAND` | one result per measurement session |

---

## 4. The data-rate problem (this drives the whole design)

This is the finding to lead with on Friday. The current pipeline was sized for 1 Hz HR. Two of the new sensors are 25 Hz.

**Per watch, per day, all continuous trackers on, at the current ~50 B/row JSON encoding:**

| Sensor | Rate | Rows/day | Bytes/day |
|---|---|---|---|
| HR | 1 Hz | 86,400 | 4.3 MB |
| EDA | 1 Hz | 86,400 | 4.3 MB |
| Skin temp | ~1/min | 1,440 | 0.1 MB |
| Accelerometer | 25 Hz | 2,160,000 | **108 MB** |
| PPG continuous | 25 Hz | 2,160,000 | **108 MB** |
| **Total** | | **~4.5 M rows** | **~225 MB/day/watch** |

Scale that to the study: **80 watches × 225 MB = 18 GB/day**, ≈ **250 GB over a two-week run**.

Three separate things break at that volume, and each needs a distinct fix:

**(a) Cellular.** 225 MB/day/phone is ~6.7 GB/month. `handoff.md` §1 assumes "Rogers throttled after cap, still fine for batched JSON" — that assumption held for HR alone and does not survive accelerometer.

**(b) Bluetooth / MessageClient.** `Wearable.MessageClient` caps a message at 100 KB. At `BATCH_LIMIT = 500` you'd send ~9,000 messages/day, one every ~10 s, each waking the BT radio. The handoff's own design note ("12 BT wake-ups per hour instead of 3600") is inverted by the new rates.

**(c) Server storage** at `/data1/wearables` and in the MISR DB.

### Recommended response — three compounding fixes

1. **Columnar + delta wire encoding** (§7). One field-name header per batch instead of per row, `int16` deltas for timestamps. Drops accel from ~50 B/row to ~7 B/row — **7×**.
2. **gzip the upload payload** in `UploadWorker` before the SFTP put. Numeric JSON compresses ~5–8×. This is a ~10-line change and the single best effort:reward ratio in the whole project. Name the file `.json.gz` and gunzip server-side.
3. **Duty-cycle the 25 Hz sensors.** Accel and PPG at 30 s on / 4.5 min off (10% duty) is usually plenty for the movement-context and PPG-quality questions a business-school study asks — **but this is a research-design decision, not an engineering one. Get Angela to state the required duty cycle for accel and PPG before you tune it.** That is a good question to bring to Friday.

With all three: **~3 MB/day/watch**, ~240 MB/day across 80 watches. Tractable.

**Battery is the second-order version of the same problem.** All continuous trackers on will not get a Galaxy Watch 8 through a waking day. Budget an overnight battery soak (it can share the doze soak already open in `handoff.md` §3) before committing to a sensor set.

---

## 5. Target architecture

### 5.1 The one idea

**Push every piece of sensor-specific knowledge up into a single registry file, and make every layer below it generic.** Today, "heart rate" is spelled out in the observer interface, the tracker listener, the Room entity, the wire DTO (twice), the phone entity, the upload DTO, the filename, and the server table. Ten sensors × eight layers is eighty places to edit. After this change it's ten entries in one file.

The seam is: **`DataPoint` → `Map<String, Number>`**. Above it, the SDK and per-sensor `ValueKey` reads. Below it, nothing knows what a heart is.

```
                      SENSOR-SPECIFIC  (one file: SensorSpec.kt)
  ┌──────────────────────────────────────────────────────────────┐
  │  SensorSpec: id, HealthTrackerType, mode, profile?, ppgTypes?, │
  │              extract: (DataPoint) -> Map<String, Number>       │
  └──────────────────────────────────────────────────────────────┘
                                  │  Sample(sensorId, tsMs, fields)
  ════════════════════════════════╪══════════════════════════════  the seam
                                  ▼
        TrackerSession  →  Room `samples`  →  BatchCodec  →  MessageClient
                        →  phone Room  →  gzip  →  SFTP  →  MySQL `samples` + per-sensor VIEWs
                      GENERIC — never mentions bpm, ecg, or accel
```

### 5.2 `SensorSpec` — the registry

```kotlin
// app/src/main/java/com/example/testwatch/sensors/SensorSpec.kt
enum class SensorMode { CONTINUOUS, ON_DEMAND }

data class SensorSpec(
    val id: String,                       // stable wire + DB key. NEVER rename after deployment.
    val trackerType: HealthTrackerType,
    val mode: SensorMode,
    val needsUserProfile: Boolean = false,
    val ppgTypes: Set<PpgType>? = null,
    val nominalHz: Double,                // for the budget in §4 and for back-pressure
    val extract: (DataPoint) -> Map<String, Number>,
)

object SensorRegistry {
    val ALL = listOf(
        SensorSpec("hr", HealthTrackerType.HEART_RATE_CONTINUOUS, CONTINUOUS, nominalHz = 1.0) { dp ->
            mapOf(
                "bpm"    to dp.getValue(ValueKey.HeartRateSet.HEART_RATE),
                "status" to dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS),
            )
            // NOTE: IBI_LIST / IBI_STATUS_LIST are List<Integer>. See §5.4.
        },
        SensorSpec("accel", HealthTrackerType.ACCELEROMETER_CONTINUOUS, CONTINUOUS, nominalHz = 25.0) { dp ->
            mapOf(
                "x" to dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X),
                "y" to dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y),
                "z" to dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z),
            )
        },
        SensorSpec("eda", HealthTrackerType.EDA_CONTINUOUS, CONTINUOUS, nominalHz = 1.0) { dp ->
            mapOf(
                "conductance" to dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE),
                "status"      to dp.getValue(ValueKey.EdaSet.STATUS),
            )
        },
        SensorSpec("skin_temp", HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS, CONTINUOUS, nominalHz = 0.017) { dp ->
            mapOf(
                "object_c"  to dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE),
                "ambient_c" to dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE),
                "status"    to dp.getValue(ValueKey.SkinTemperatureSet.STATUS),
            )
        },
        SensorSpec("ppg", HealthTrackerType.PPG_CONTINUOUS, CONTINUOUS,
                   ppgTypes = setOf(PpgType.GREEN, PpgType.RED), nominalHz = 25.0) { dp ->
            mapOf(
                "green" to dp.getValue(ValueKey.PpgSet.PPG_GREEN),
                "ir"    to dp.getValue(ValueKey.PpgSet.PPG_IR),
                "red"   to dp.getValue(ValueKey.PpgSet.PPG_RED),
                "g_st"  to dp.getValue(ValueKey.PpgSet.GREEN_STATUS),
                "i_st"  to dp.getValue(ValueKey.PpgSet.IR_STATUS),
                "r_st"  to dp.getValue(ValueKey.PpgSet.RED_STATUS),
            )
        },
        SensorSpec("spo2", HealthTrackerType.SPO2_ON_DEMAND, ON_DEMAND, nominalHz = 0.0) { dp ->
            mapOf(
                "spo2"     to dp.getValue(ValueKey.SpO2Set.SPO2),
                "status"   to dp.getValue(ValueKey.SpO2Set.STATUS),
                "hr"       to dp.getValue(ValueKey.SpO2Set.HEART_RATE),
                "accuracy" to dp.getValue(ValueKey.SpO2Set.ACCURACY_FLAG),
            )
        },
        SensorSpec("ecg", HealthTrackerType.ECG_ON_DEMAND, ON_DEMAND, nominalHz = 500.0) { dp ->
            mapOf(
                "mv"       to dp.getValue(ValueKey.EcgSet.ECG_MV),
                "lead_off" to dp.getValue(ValueKey.EcgSet.LEAD_OFF),
                "seq"      to dp.getValue(ValueKey.EcgSet.SEQUENCE),
            )
        },
        SensorSpec("bia", HealthTrackerType.BIA_ON_DEMAND, ON_DEMAND,
                   needsUserProfile = true, nominalHz = 0.0) { dp ->
            mapOf(
                "bfm"      to dp.getValue(ValueKey.BiaSet.BODY_FAT_MASS),
                "bfr"      to dp.getValue(ValueKey.BiaSet.BODY_FAT_RATIO),
                "ffm"      to dp.getValue(ValueKey.BiaSet.FAT_FREE_MASS),
                "smm"      to dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS),
                "tbw"      to dp.getValue(ValueKey.BiaSet.TOTAL_BODY_WATER),
                "bmr"      to dp.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE),
                "imp_mag"  to dp.getValue(ValueKey.BiaSet.BODY_IMPEDANCE_MAGNITUDE),
                "imp_deg"  to dp.getValue(ValueKey.BiaSet.BODY_IMPEDANCE_DEGREE),
                "progress" to dp.getValue(ValueKey.BiaSet.PROGRESS),
                "status"   to dp.getValue(ValueKey.BiaSet.STATUS),
            )
        },
        // mf_bia: same shape, 8 impedance fields (see §3.4). Watch8+ only.
    )

    fun byId(id: String) = ALL.first { it.id == id }
}
```

**Design rules for `SensorSpec.id`:** short, lowercase, stable forever. It becomes a column value in the MISR DB and appears in every uploaded file. Renaming `hr` → `heart_rate` after 80 watches are deployed silently forks the dataset.

### 5.3 `TrackerSession` — generic replacement for `HeartRateListener`

`HeartRateListener.java` and the HR-specific halves of `ConnectionManager.java` collapse into one parameterised class. Delete `HeartRateListener`, `TrackerDataSubject`, and `TrackerObserver` — the observer indirection was buying nothing even for one sensor, and the `notifyHeartRateTrackerObservers(long,int,int)` signature is exactly the thing that doesn't generalise.

```kotlin
class TrackerSession(
    private val spec: SensorSpec,
    private val service: HealthTrackingService,
    private val profile: TrackerUserProfile?,
    private val onSamples: (List<Sample>) -> Unit,
    private val onError: (SensorSpec, HealthTracker.TrackerError) -> Unit,
) {
    private var tracker: HealthTracker? = null
    private val handler = Handler(Looper.getMainLooper())

    private val listener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: MutableList<DataPoint>) {
            onSamples(points.map { Sample(spec.id, it.timestamp, spec.extract(it)) })
        }
        override fun onFlushCompleted() { Log.i(TAG, "${'$'}{spec.id} flush completed") }
        override fun onError(e: HealthTracker.TrackerError) { onError(spec, e) }
    }

    fun start() {
        val t = when {
            spec.ppgTypes != null    -> service.getHealthTracker(spec.trackerType, spec.ppgTypes)
            spec.needsUserProfile    -> service.getHealthTracker(spec.trackerType, requireNotNull(profile))
            else                     -> service.getHealthTracker(spec.trackerType)
        }
        tracker = t
        handler.post { t.setEventListener(listener) }
    }

    fun flush() { tracker?.flush() }
    fun stop()  { tracker?.unsetEventListener(); handler.removeCallbacksAndMessages(null); tracker = null }
}
```

`ConnectionObserver.onHeartRateAvailability(Boolean)` becomes `onCapabilities(Set<HealthTrackerType>)`, and `ConnectionManager.isHeartTrackingAvailable()` becomes a plain `getTrackingCapability().getSupportHealthTrackerTypes()` passthrough — the registry decides what to do with the set.

### 5.4 Generic on-watch storage

```kotlin
@Entity(
    tableName = "samples",
    indices = [Index(value = ["synced", "id"]), Index(value = ["sensorId", "timestampMs"])],
)
data class SampleRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sensorId: String,
    val timestampMs: Long,
    val fieldsJson: String,   // {"bpm":72,"status":1}
    val synced: Boolean = false,
)
```

**The tradeoff, stated plainly, because someone will ask on Friday:** a JSON blob column gives up per-field type safety and per-field indexing. In exchange you get one table, one DAO, one sync path, and — the part that actually matters operationally — **zero Room schema migrations when sensor #11 is added.** Shipping a Room migration to 80 kiosked watches mid-study is a genuinely bad afternoon; a schema that never changes cannot have that failure mode.

The general principle worth stating: **the watch and phone DBs are write-optimised append-only buffers whose only queries are "give me unsynced ordered by id" and "delete synced". They are not analysis stores.** Put the flexibility on the device and the structure on the server (§8), where the schema can change freely because there's one of it and it's not on someone's wrist.

**`IBI_LIST` is the exception that proves the rule.** It's a `List<Integer>`, so it doesn't fit `Map<String, Number>`. Two options: (a) widen the extractor to `Map<String, Any>` and let the JSON encoder emit an array; (b) drop IBI for now. Recommend (a) — IBI is the highest-value HR-derived signal for a stress/affect study and Angela will likely want it. Flag it as a deliberate widening of the seam, not an accident.

### 5.5 Back-pressure — the thing that's missing today

`pruneSynced` only runs *after* a successful sync. If the phone is unreachable for a day, `hr.db` grows without bound. At 1 Hz HR that's 86 k rows — invisible. At 4.5 M rows/day it fills the watch.

Add a hard cap enforced on every insert batch:

```kotlin
// after each insert batch
if (dao.count() > MAX_ROWS) {            // e.g. 500_000
    val dropped = dao.deleteOldestUnsynced(dao.count() - MAX_ROWS)
    Telemetry.samplesDropped += dropped   // MUST be reported, not silent
}
```

**Drop-oldest, and count what you dropped.** Silent data loss in a research pipeline is worse than a gap you can see — a study needs to be able to say "participant P042 has a 40-minute hole here" rather than quietly producing a dataset with unexplained thin spots.

---

## 6. On-demand sensor architecture (§the named deliverable)

On-demand trackers are not "continuous trackers you start later." They're **measurement sessions**: start → warm-up → `PROGRESS` climbs → result → stop. They have three properties that force a scheduler:

1. **They are mutually exclusive.** ECG, BIA, MF-BIA, and SpO2 all contend for the same optical/electrical front end. Running two at once will fail or produce garbage.
2. **They contend with the continuous set.** An ECG session will disturb PPG and HR.
3. **BIA / MF-BIA / ECG require the participant to touch the frame** for ~30 s. They cannot fire unattended.

### 6.1 Design — a single-slot scheduler with a mutex

```kotlin
sealed interface OnDemandState {
    object Idle : OnDemandState
    data class Requested(val sensorId: String, val at: Long) : OnDemandState
    data class AwaitingUser(val sensorId: String, val deadline: Long) : OnDemandState  // "hold your finger on the frame"
    data class Measuring(val sensorId: String, val progress: Int, val startedAt: Long) : OnDemandState
    data class Done(val sensorId: String, val ok: Boolean, val reason: String?) : OnDemandState
}

class OnDemandScheduler(
    private val sessions: SessionFactory,
    private val continuous: ContinuousController,
) {
    private val mutex = Mutex()                       // property (1): one at a time

    suspend fun measure(spec: SensorSpec, timeoutMs: Long = 60_000): Result<Unit> = mutex.withLock {
        require(spec.mode == ON_DEMAND)
        continuous.pause(conflictsWith(spec))         // property (2)
        try {
            withTimeout(timeoutMs) {                  // NEVER leave a tracker attached forever
                val session = sessions.create(spec)
                session.start()
                awaitCompletion(spec)                 // PROGRESS == 100, or a terminal STATUS
                session.stop()
            }
            Result.success(Unit)
        } catch (e: TimeoutCancellationException) {
            Result.failure(e)
        } finally {
            continuous.resume()                       // resume even on failure — this is the bug people ship
        }
    }
}
```

**Completion detection.** `BiaSet.PROGRESS` and `MfBiaSet.PROGRESS` reach 100. For ECG and SpO2 the SDK doesn't document a progress key, so treat them as **duration-bounded**: run for a fixed window (ECG 30 s is the clinical convention) and stop. Every on-demand session must have a timeout — a tracker left attached is a battery leak and a mutex deadlock.

**Trigger policy** — three sources, same code path:

| Trigger | Use |
|---|---|
| **Scheduled** (`WorkManager` periodic, e.g. SpO2 every 30 min) | passive sensors |
| **Participant-initiated** (tile/button in the kiosk UI) | BIA / ECG, which need cooperation anyway |
| **Remote / ADB broadcast** (mirror `SetParticipantIdReceiver`) | demo and debugging — **build this one first, it's what you'll drive Friday's demo with** |

```bash
adb shell am broadcast -a com.example.testwatch.MEASURE \
  -n com.example.testwatch/.admin.MeasureReceiver --es sensor "spo2"
```

### 6.2 Use `HealthTracker.flush()` — currently unused, and it may fix a known open item

`handoff.md` §7 documents that data stops being *delivered* ~30–90 s after screen sleep and arrives as a burst when the app returns to the foreground, with buffer depth beyond ~4 min unverified. **`HealthTracker.flush()` exists in the SDK and the codebase never calls it.** Calling `flush()` on every tracker at the top of the 5-minute sync loop is the natural way to pull the hardware buffer on your schedule instead of waiting for a foreground event. Cheap to try, and it directly de-risks the overnight soak test that's still open.

---

## 7. Wire format v2

Two problems with today's format. First, **there is no version field** — once 80 watches are deployed you cannot change the encoding without one. Second, `WireSample` / `WireBatch` are **declared twice**, in `app/.../sync/BatchSerializer.kt` and again as `private` classes in `mobile/.../wear/HrBatchListenerService.kt`. They can drift silently, and with ten sensors they will.

**Fix both:** add a `:protocol` Gradle module depended on by `:app` and `:mobile`, holding the DTOs and the codec. One definition, compiler-enforced.

```kotlin
// protocol/src/main/kotlin/.../Wire.kt
@Serializable
data class WireBatch(
    val v: Int = 2,                    // schema version — REQUIRED
    val participantId: String,
    val deviceId: String,              // stable install UUID; see bug B3
    val sensorId: String,              // one sensor per batch — enables the columnar form
    val t0: Long,                      // base timestamp
    val fields: List<String>,          // ["x","y","z"]  — names once, not per row
    val rows: List<List<Long>>,        // [[dtMs, x, y, z], ...]  dt relative to t0
)
```

Per-sensor batching plus a name header plus delta timestamps takes accelerometer from ~50 B/row to ~7 B/row. Then gzip in `UploadWorker`:

```kotlin
val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(payload) } }.toByteArray()
val filename = "hr_${'$'}{pid}_${'$'}{ts}.json.gz"
```

For accelerometer and PPG specifically, consider `ChannelClient` instead of `MessageClient` — it streams and has no 100 KB cap. Keep `MessageClient` for the low-rate sensors; it's simpler and already works.

---

## 8. Server side — MISR MySQL

**Correction to `handoff.md` §4, worth catching before you write the ingester:** the handoff specifies SQLite, but the Slack thread with Gene and Angela provisions access through **phpMyAdmin** to the **`ai science`** database — that's **MySQL/MariaDB**, not SQLite. Different DDL, different JSON support, different connection story. Confirm with Angela which target is authoritative before building the ingester twice.

### Schema — one narrow table plus per-sensor views

```sql
CREATE TABLE samples (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  participant_id VARCHAR(32)  NOT NULL,
  device_id      VARCHAR(64)  NOT NULL,
  sensor_id      VARCHAR(24)  NOT NULL,
  timestamp_ms   BIGINT       NOT NULL,
  received_at    BIGINT       NOT NULL,
  ingested_at    BIGINT       NOT NULL,
  fields         JSON         NOT NULL,
  UNIQUE KEY uq_sample (participant_id, sensor_id, timestamp_ms),
  KEY idx_query (participant_id, sensor_id, timestamp_ms)
) ENGINE=InnoDB;
```

**The `UNIQUE KEY` is the most important line in this document.** The pipeline is at-least-once by construction: `UploadWorker` marks rows uploaded *after* the SFTP put returns, so a crash in that window re-uploads them, and `WorkManager` retries on failure. A unique key plus `INSERT ... ON DUPLICATE KEY UPDATE id=id` turns at-least-once delivery into effectively-once storage. Without it, duplicates are guaranteed and Angela's analyses silently double-count.

Give the analysts normal-looking tables via views, so nobody has to learn JSON path syntax:

```sql
CREATE VIEW hr AS SELECT participant_id, timestamp_ms,
  CAST(fields->>'$.bpm'    AS SIGNED) AS bpm,
  CAST(fields->>'$.status' AS SIGNED) AS status
FROM samples WHERE sensor_id = 'hr';
```

If a view gets slow, promote the hot field to a `STORED` generated column and index it — no ingester change required.

### Ingester

A small Python daemon on the MISR box: scan `/data1/wearables` for `hr_*.json.gz`, gunzip, expand the columnar rows, `INSERT ... ON DUPLICATE KEY UPDATE`, then **move the file to `processed/`** rather than deleting it. Keeping raw uploads is cheap insurance for a research dataset and lets you re-ingest after a schema fix.

Two sanity checks the ingester should do, both prompted by real bugs already hit:
- **Clock drift** (`handoff.md` §7 — a watch drifted 7 weeks with BT off): flag any file where `median(timestamp_ms)` differs from `uploaded_at` by more than a few minutes. Quarantine, don't insert.
- **`is_test`**: `UploadWorker.buildPayload` hardcodes `is_test = true`. Route those to a separate table or refuse them in prod ingestion — and see bug B6.

Also still open from the handoff: `/data1/wearables` has June-11 junk files to archive.

---

## 9. Bug and risk list found in the audit

| # | Sev | File | Issue |
|---|---|---|---|
| **B1** | 🔴 | `AndroidManifest.xml` (app) | Missing 4 permissions for non-HR trackers on Android 16. Blocks the entire deliverable. See §1. |
| **B2** | 🔴 | `mobile/.../ServerConfig.kt` | `fun isConfigured() = PASSWORD != "Also "` — leftover garbage from an editing accident. It passes by luck. Should be `USER.isNotEmpty() && PASSWORD.isNotEmpty()`. The `.example` file has the correct version. |
| **B3** | 🔴 | `UploadWorker.buildPayload` | `android.os.Build.SERIAL` is deprecated and returns `"unknown"` on API 26+ without privileged permission. `watch_serial` is almost certainly a constant useless string. **In an 80-device study you currently cannot tell devices apart.** Replace with a UUID generated at first launch and persisted next to `participant_id` — `ParticipantStore` already does exactly this pattern (`"P-" + UUID.randomUUID()...` persisted in SharedPreferences); add a second `device_id` key alongside it. |
| **B4** | 🟠 | server / everywhere | No idempotency key anywhere. Retries produce duplicates. Fix = the `UNIQUE KEY` in §8. |
| **B5** | 🟠 | `BatchSerializer.kt` + `HrBatchListenerService.kt` | Wire DTOs declared twice, no version field. Fix = `:protocol` module (§7). |
| **B6** | 🟠 | `UploadWorker.kt:102` | `is_test = true` hardcoded with a "flip before prod" comment. A comment is not a mechanism — drive it from `BuildConfig.DEBUG` or a build flavour so it cannot be forgotten. |
| **B7** | 🟠 | `HrBatchListenerService.enqueueUpload` | `ExistingWorkPolicy.REPLACE` cancels an in-flight upload whenever a new batch arrives. With one sensor at 30 s intervals it's survivable; with ten sensors, batches can arrive faster than uploads complete and uploads can be starved indefinitely. Use `KEEP` for the trigger plus a periodic 15-min worker as a floor. |
| **B8** | 🟡 | `HrTrackingService.syncOnce` | `dao.pruneSynced(pending.last().id - KEEP_RECENT_SYNCED)` does arithmetic on autoincrement IDs as a proxy for row count. Correct only while IDs are dense and single-sensor. Once ten sensors interleave in one table, "keep 1000 rows" becomes "keep whatever the last 1000 IDs happened to be" — accel will evict HR. Prune by timestamp or by an explicit count query. |
| **B9** | 🟡 | `HrDao.markSynced(ids)` | `IN (:ids)` with up to `BATCH_LIMIT` values. SQLite's default host-parameter limit is 999. Safe at 500, breaks silently if anyone raises `BATCH_LIMIT`. Chunk it. |
| **B10** | 🟡 | `HrTrackingService.kt:211` | `SYNC_INTERVAL_MS = 30s` testing override still in (already tracked in `handoff.md` §3). |
| **B11** | 🟡 | watch Room | No cap on buffer growth; prune only runs after a successful sync. See §5.5. |
| **B12** | 🟡 | `UploadWorker.uploadOverSftp` | `PromiscuousVerifier` accepts any host key (already tracked). With one shared password across 80 phones, a MITM on campus wifi harvests a credential with write access to `/data1/wearables`. Pin the fingerprint; move to per-device keys. |
| **B13** | 🟡 | `ConnectionManager.connect` | Constructs a new `HealthTrackingService` on every call with no guard against double-connect; the generic rewrite should make this idempotent. |
| **B14** | ⚪ | Room | Migrating `hr_samples` → generic `samples` needs a migration or `fallbackToDestructiveMigration()`. Destructive is fine *now* (both DBs were wiped 2026-07-30) and must be locked down before any watch goes to a participant. |

---

## 10. Suggested work order for the coding agent

Each step is independently demoable, so if you run out of night you stop at a working boundary rather than a half-refactor.

| Step | Work | Acceptance |
|---|---|---|
| **1** | Manifest permissions + extend `MainActivity` runtime request + provisioning grants (§1) | `adb shell dumpsys package com.example.testwatch \| grep -A20 "runtime permissions"` shows all six granted |
| **2** | `CapabilityProbe` (§2) | logcat lists supported types and per-type instantiation result |
| **3** | **Freeze demo scope** against step 2's output | a written list of sensors that will be shown live |
| **4** | `SensorSpec` + `SensorRegistry` (§5.2) for the step-3 list | compiles; registry entries exist for every scoped sensor |
| **5** | `TrackerSession` (§5.3); delete `HeartRateListener` / `TrackerDataSubject` / `TrackerObserver` | HR still flows end-to-end through the new path — **regression-test this before adding sensor #2** |
| **6** | Generic `samples` Room table + DAO, watch side (§5.4) | multiple `sensorId`s coexist in one table |
| **7** | `:protocol` module, wire v2 with `v`, `sensorId`, columnar rows (§7) | phone decodes; `adb logcat -s HrBatchListener` shows per-sensor batches |
| **8** | Phone-side generic mirror + gzip upload (§7) | `.json.gz` lands in `/data1/wearables`, gunzips cleanly |
| **9** | `OnDemandScheduler` + `MeasureReceiver` broadcast (§6) | `adb shell am broadcast ... --es sensor "spo2"` produces a measurement, mutex holds under two concurrent requests |
| **10** | MySQL schema + ingester + views (§8) | `SELECT sensor_id, COUNT(*) FROM samples GROUP BY sensor_id` shows every scoped sensor; re-running the ingester on the same file adds zero rows |
| **11** | Bugs B2, B3, B6, B7 (cheap, high value) | — |
| **12** | Back-pressure cap + dropped-sample telemetry (§5.5) | — |

Steps 1–3 are tonight. Steps 4–8 are the core refactor. Step 9 is the headline. Step 10 can be a stub for the demo (show the JSON landing) if MISR DB access isn't fully sorted.

---

## 11. Demo script for the PhD office

1. **The problem** — one slide of §4's table. 1 Hz HR was 4 MB/day; the full sensor set is 225 MB/day/watch, 18 GB/day across the study. This is the interesting engineering result, not an apology.
2. **The architecture** — the §5.1 diagram. One sentence: *"every sensor-specific fact now lives in one file; everything downstream is generic, so sensor #11 is a ten-line diff instead of an eight-layer change."*
3. **Live** — watch on wrist, `adb logcat` on screen, multiple `sensorId`s streaming.
4. **On-demand** — fire the `MEASURE` broadcast for SpO2, show the state machine transition and the result row.
5. **Server** — the file landing in `/data1/wearables`, then `SELECT sensor_id, COUNT(*) ... GROUP BY sensor_id`.
6. **Three questions for Angela** (have these ready — they make it a working meeting rather than a status report):
    - What duty cycle do accelerometer and PPG actually need for the research question? This is a ~30× storage difference and it's her call, not yours.
    - Is BIA/ECG worth a participant-interaction flow, given they can't be collected passively?
    - SQLite or the MISR MySQL `ai science` DB — which is authoritative? (`handoff.md` says one thing, the Slack thread provisions the other.)

---

## 12. What this plan does *not* answer

Stated explicitly so nobody mistakes a guess for a verified fact:

- Whether `READ_ADDITIONAL_HEALTH_DATA` is grantable to a non-partner app. **Empirical, step 1.**
- Which of the 19 tracker types the SM-L320 actually supports. **Empirical, step 2.**
- Whether IR is separately selectable via `PpgType` (only `GREEN` and `RED` exist as constants, yet `PpgSet` exposes `PPG_IR`).
- Real battery life with the full continuous set. **Needs an overnight soak** — fold it into the doze soak already open in `handoff.md` §3.
- `SKIN_TEMPERATURE_CONTINUOUS` actual sample rate — Samsung's docs don't state it.
- Whether `flush()` actually drains the doze buffer on demand (§6.2). Cheap to test, high value.