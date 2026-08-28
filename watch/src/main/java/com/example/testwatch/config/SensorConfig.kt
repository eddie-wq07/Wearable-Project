package com.example.testwatch.config

import com.samsung.android.service.health.tracking.data.TrackerUserProfile

/** Timing knobs for the on-demand measurement rounds (see sensors/SensorEngine.kt). */
object SensorConfig {
    /** How often the on-demand measurement round runs. */
    const val ON_DEMAND_INTERVAL_MIN = 5L

    /** Delay before the first round after startup (so it's quick to observe). */
    const val FIRST_ROUND_DELAY_MIN = 1L

    /** Max seconds each on-demand tracker collects before the queue moves on. */
    const val ON_DEMAND_TIMEOUT_SEC = 45L

    /** BIA/MF-BIA refuse to start without a profile; only derived body-composition
     *  values depend on it, raw impedance does not. Gender: 0 = male, 1 = female. */
    val userProfile: TrackerUserProfile = TrackerUserProfile.Builder()
        .setHeight(170f)
        .setWeight(70f)
        .setAge(30)
        .setGender(0)
        .build()
}
