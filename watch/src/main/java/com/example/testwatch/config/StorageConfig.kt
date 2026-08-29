package com.example.testwatch.config

/** Store-and-forward budget: check interval, high-water/low-free/emergency GB thresholds, and
 *  drain pacing. Governs when the watch actually sends its buffered backlog to the phone. */

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
