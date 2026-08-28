package com.example.testwatch.config

/**
 * Store-and-forward budget for the on-watch buffer (architecture change 2026-08-26):
 * the watch no longer syncs on a fixed 30 s / 5 min cadence — it accumulates
 * locally and pushes to the phone only when the buffer crosses [HIGH_WATER_BYTES],
 * when device free space falls below [MIN_FREE_BYTES], or on a manual
 * DRAIN_NOW broadcast (end-of-study offload).
 *
 * Hardware (Galaxy Watch 8, SM-L320): 32 GB flash, of which ~18 GB is actually
 * available after Wear OS + preloads. The 2 GB of RAM is irrelevant to
 * buffering — every sample lands in SQLite (hr.db) via Room; nothing persists
 * in memory.
 *
 * Measured write rate with the current continuous set
 * (see SENSORS in SensorSpec.kt — ppg + accel + skin_temp, plus the legacy
 * 1 Hz HR path):
 *
 *   ppg        25 Hz  x ~90 B/point JSON  = ~194 MB/day
 *   accel      25 Hz  x ~43 B/point JSON  = ~93 MB/day
 *   hr          1 Hz  x ~40 B/row         = ~3.5 MB/day
 *   skin_temp  1/min                      = ~0.1 MB/day
 *   on-demand rounds (spo2/bia/mf_bia every 5 min)     = ~2 MB/day
 *   -------------------------------------------------------------
 *   ~293 MB/day of JSON, ~320 MB/day with SQLite overhead
 *
 * Timing that follows from the numbers below:
 *   HIGH_WATER 12 GB  = ~37 days of collection before an automatic drain.
 *                       A 2-4 week study window (4.5-9 GB) therefore never
 *                       auto-drains; the intended offload is the DRAIN_NOW
 *                       broadcast with the watch on its charger.
 *   MIN_FREE    2 GB  = physical floor — drain regardless of the logical
 *                       budget (protects the OS, and covers the case where
 *                       something other than us is eating storage).
 *   Full-capacity fill time at ~320 MB/day: ~56 days to 18 GB usable.
 *
 * SQLite reality check: deleting synced rows lets the DB reuse pages but the
 * hr.db file never shrinks without VACUUM (which needs temp space we may not
 * have). The logical cap is what actually bounds the file size: the DB file
 * plateaus around the high-water mark and stays there.
 */
object StorageConfig {
    /** How often the loop re-checks buffer size and free space. Replaces the
     *  old SYNC_INTERVAL_MS (which was stuck on a 30 s testing override). */
    const val CHECK_INTERVAL_MS = 5L * 60_000L

    /** Logical unsynced-bytes threshold that triggers an automatic drain. */
    const val HIGH_WATER_BYTES = 12L * 1024 * 1024 * 1024

    /** Device free-space floor: drain below this no matter what the logical
     *  budget says. */
    const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

    /** Critically low free space: start dropping oldest unsynced rows (loudly —
     *  TrackingState.droppedRows) so the DB cannot grow another byte. */
    const val EMERGENCY_FREE_BYTES = 1L * 1024 * 1024 * 1024

    /** Rows dropped per emergency pass, oldest first, per table. */
    const val EMERGENCY_DROP_ROWS = 20_000

    /** Estimated stored bytes per hr_samples row (fixed columns, no JSON). */
    const val HR_ROW_BYTES = 40L

    /** SQLite/Room overhead applied on top of logical JSON bytes, percent. */
    const val OVERHEAD_PCT = 115L

    /** Pause between drain rounds so a multi-GB backlog doesn't saturate the
     *  Bluetooth link or starve the sensor callbacks. */
    const val DRAIN_PAUSE_MS = 200L
}
