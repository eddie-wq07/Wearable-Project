package com.example.testwatch.config

/** Per-measurement timeout for on-demand sensors and the dummy TrackerUserProfile BIA/mf_bia
 *  measurements require. Round scheduling lives in the ondemand package (participant-triggered). */

import com.samsung.android.service.health.tracking.data.TrackerUserProfile

object SensorConfig {
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
