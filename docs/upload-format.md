# Upload file format — project standard

Standard as of 2026-09-01. Every file the watch uploads to the server follows this
format. Supersedes the earlier shape that carried `timestamp_ms`, `watch_serial`, and
epoch-millisecond values (all removed) — every timestamp in this format is
human-readable, in the watch's local timezone.

## Participant ID scheme

Every physical watch is assigned a permanent number (`1`, `2`, … `20`). Every study
cycle on that watch is assigned a letter (`A` = first cycle, `B` = second, `C` = third, …).
The participant ID is the concatenation: **`<watch number><cycle letter>`**.

| Watch | Cycle 1 | Cycle 2 | Cycle 3 |
|-------|---------|---------|---------|
| 1     | `1A`    | `1B`    | `1C`    |
| 2     | `2A`    | `2B`    | `2C`    |
| 12    | `12A`   | `12B`   | `12C`   |

The number never changes — it identifies the hardware for the life of the study. The
letter increments each time the watch is reissued to a new participant. So `1B` means
"watch 1, second person to wear it."

Assign the ID at the start of each cycle:

```bash
adb -s <watch> shell am broadcast -a com.example.testwatch.SET_PARTICIPANT_ID \
    -n com.example.testwatch/.admin.SetParticipantIdReceiver \
    --es participant_id "1A"
```

## File naming

Uploads land in `/data1/wearables/<participantId>/`:

- `sensors_<participantId>_<YYYY-MM-DD_HHMMSS>.json` — all sensor batches (continuous + on-demand)
- `hr_<participantId>_<YYYY-MM-DD_HHMMSS>.json` — heart-rate samples

Example: `sensors_1A_2026-09-01_232000.json` (upload time, watch-local, filesystem-safe
and sorts chronologically).

## Sensors file

One `batches` entry per recorded batch; the envelope is identical for every sensor,
only the keys inside `points` differ (defined in `SensorSpec.kt`). Example with one
entry per sensor type:

```json
{
  "uploaded_at": "Sep 1 2026, 11:20:00 PM PDT",
  "is_test": true,
  "batches": [
    {
      "participant_id": "1A",
      "sensor": "ppg",
      "time": "Sep 1 2026, 10:20:00 PM PDT",
      "received_at": "Sep 1 2026, 11:20:00 PM PDT",
      "points": [
        { "green": 1834202, "ir": 902114, "red": 771530, "g_st": 0, "i_st": 0, "r_st": 0 },
        { "green": 1834550, "ir": 902377, "red": 771812, "g_st": 0, "i_st": 0, "r_st": 0 }
      ]
    },
    {
      "participant_id": "1A",
      "sensor": "accel",
      "time": "Sep 1 2026, 10:20:00 PM PDT",
      "received_at": "Sep 1 2026, 11:20:00 PM PDT",
      "points": [
        { "x": 0.012, "y": -9.794, "z": 0.310 },
        { "x": 0.015, "y": -9.801, "z": 0.298 }
      ]
    },
    {
      "participant_id": "1A",
      "sensor": "skin_temp",
      "time": "Sep 1 2026, 10:21:00 PM PDT",
      "received_at": "Sep 1 2026, 11:20:00 PM PDT",
      "points": [
        { "object_c": 33.1, "ambient_c": 26.4, "status": 0 }
      ]
    },
    {
      "participant_id": "1A",
      "sensor": "ecg",
      "time": "Sep 1 2026, 10:22:00 PM PDT",
      "received_at": "Sep 1 2026, 11:20:00 PM PDT",
      "points": [
        { "mv": 0.142, "lead_off": 0, "seq": 1 },
        { "mv": 0.156, "lead_off": 0, "seq": 2 }
      ]
    },
    {
      "participant_id": "1A",
      "sensor": "spo2",
      "time": "Sep 1 2026, 10:23:00 PM PDT",
      "received_at": "Sep 1 2026, 11:20:00 PM PDT",
      "points": [
        { "spo2": 97, "hr": 71, "status": 2, "accuracy": 1 }
      ]
    },
    {
      "participant_id": "1A",
      "sensor": "bia",
      "time": "Sep 1 2026, 10:24:00 PM PDT",
      "received_at": "Sep 1 2026, 11:20:00 PM PDT",
      "points": [
        {
          "status": 0,
          "progress": 100,
          "body_fat_ratio": 21.4,
          "body_fat_mass": 15.6,
          "total_body_water": 41.2,
          "skeletal_muscle_ratio": 44.8,
          "skeletal_muscle_mass": 32.7,
          "basal_metabolic_rate": 1712,
          "fat_free_ratio": 78.6,
          "fat_free_mass": 57.3,
          "impedance_mag": 512.4,
          "impedance_deg": -6.2
        }
      ]
    },
    {
      "participant_id": "1A",
      "sensor": "mf_bia",
      "time": "Sep 1 2026, 10:25:00 PM PDT",
      "received_at": "Sep 1 2026, 11:20:00 PM PDT",
      "points": [
        {
          "status": 0,
          "progress": 100,
          "mag_5k": 588.1, "phase_5k": -4.1,
          "mag_10k": 561.9, "phase_10k": -5.0,
          "mag_50k": 512.4, "phase_50k": -6.2,
          "mag_250k": 471.7, "phase_250k": -5.4
        }
      ]
    }
  ]
}
```

## HR file

```json
{
  "uploaded_at": "Sep 1 2026, 11:20:00 PM PDT",
  "is_test": true,
  "samples": [
    {
      "participant_id": "1A",
      "time": "Sep 1 2026, 10:20:00 PM PDT",
      "bpm": 72,
      "status": 1,
      "received_at": "Sep 1 2026, 11:20:00 PM PDT"
    }
  ]
}
```

## Notes

- All timestamps use one format: `MMM d yyyy, h:mm:ss a zzz` (e.g. `Sep 1 2026,
  11:20:00 PM PDT`), watch-local timezone, **seconds precision**. No epoch values
  anywhere.
- `time` is when the batch/sample was recorded; `received_at` / `uploaded_at` are when
  the upload pass ran ("left the watch at").
- `is_test` is `true` for pre-study data; flip to `false` for production uploads.
- Bytes on the server are this structure serialized compact (single line); pretty-print
  with `jq .` when reading.
- **Implementation status (2026-09-01):** `BatchSerializer.kt` still emits the old
  fields (`watch_serial`, `timestamp_ms`) and the suggested ingest schema in
  `handoff.md` §4 still keys off `timestamp_ms`. Both need updating to match this
  standard before study launch.
