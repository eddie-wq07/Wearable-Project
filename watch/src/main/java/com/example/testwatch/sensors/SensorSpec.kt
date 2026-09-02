package com.example.testwatch.sensors

/** Registry of every supported sensor (ppg, accel, skin_temp, ecg, spo2, bia, mf_bia): SDK type,
 *  continuous vs on-demand, and how to extract values from a raw SDK data point. */

import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey

enum class SensorMode { CONTINUOUS, ON_DEMAND }

/**
 * Everything sensor-specific lives in [SENSORS]; every layer below it
 * (session, Room row, wire message, upload) is generic.
 */
class SensorSpec(
    /** Stable wire/DB key — never rename after deployment. */
    val id: String,
    val trackerType: HealthTrackerType,
    val mode: SensorMode,
    val needsProfile: Boolean = false,
    val ppgTypes: Set<PpgType>? = null,
    /** Shown to the participant while an on-demand measurement runs. */
    val prompt: String? = null,
    val extract: (DataPoint) -> Map<String, Number>,
)

/** Wire/DB keys of the on-demand sensors, for the daily round's replace-semantics delete. */
val ON_DEMAND_SENSOR_IDS: List<String> by lazy {
    SENSORS.filter { it.mode == SensorMode.ON_DEMAND }.map { it.id }
}

val SENSORS = listOf(
    SensorSpec("ppg", HealthTrackerType.PPG_CONTINUOUS, SensorMode.CONTINUOUS,
        ppgTypes = setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)) { dp ->
        mapOf(
            "green" to dp.getValue(ValueKey.PpgSet.PPG_GREEN),
            "ir" to dp.getValue(ValueKey.PpgSet.PPG_IR),
            "red" to dp.getValue(ValueKey.PpgSet.PPG_RED),
            "g_st" to dp.getValue(ValueKey.PpgSet.GREEN_STATUS),
            "i_st" to dp.getValue(ValueKey.PpgSet.IR_STATUS),
            "r_st" to dp.getValue(ValueKey.PpgSet.RED_STATUS),
        )
    },
    SensorSpec("accel", HealthTrackerType.ACCELEROMETER_CONTINUOUS, SensorMode.CONTINUOUS) { dp ->
        mapOf(
            "x" to dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X),
            "y" to dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y),
            "z" to dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z),
        )
    },
    SensorSpec("skin_temp", HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS, SensorMode.CONTINUOUS) { dp ->
        mapOf(
            "object_c" to dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE),
            "ambient_c" to dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE),
            "status" to dp.getValue(ValueKey.SkinTemperatureSet.STATUS),
        )
    },
    SensorSpec("ecg", HealthTrackerType.ECG_ON_DEMAND, SensorMode.ON_DEMAND,
        prompt = "ECG: finger on top button") { dp ->
        mapOf(
            "mv" to dp.getValue(ValueKey.EcgSet.ECG_MV),
            "lead_off" to dp.getValue(ValueKey.EcgSet.LEAD_OFF),
            "seq" to dp.getValue(ValueKey.EcgSet.SEQUENCE),
        )
    },
    SensorSpec("spo2", HealthTrackerType.SPO2_ON_DEMAND, SensorMode.ON_DEMAND,
        prompt = "SpO2: hold still") { dp ->
        mapOf(
            "spo2" to dp.getValue(ValueKey.SpO2Set.SPO2),
            "hr" to dp.getValue(ValueKey.SpO2Set.HEART_RATE),
            "status" to dp.getValue(ValueKey.SpO2Set.STATUS),
            "accuracy" to dp.getValue(ValueKey.SpO2Set.ACCURACY_FLAG),
        )
    },
    SensorSpec("bia", HealthTrackerType.BIA_ON_DEMAND, SensorMode.ON_DEMAND,
        needsProfile = true, prompt = "Body comp 1 of 2: fingers on both buttons") { dp ->
        mapOf(
            "status" to dp.getValue(ValueKey.BiaSet.STATUS),
            "progress" to dp.getValue(ValueKey.BiaSet.PROGRESS),
            "body_fat_ratio" to dp.getValue(ValueKey.BiaSet.BODY_FAT_RATIO),
            "body_fat_mass" to dp.getValue(ValueKey.BiaSet.BODY_FAT_MASS),
            "total_body_water" to dp.getValue(ValueKey.BiaSet.TOTAL_BODY_WATER),
            "skeletal_muscle_ratio" to dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_RATIO),
            "skeletal_muscle_mass" to dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS),
            "basal_metabolic_rate" to dp.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE),
            "fat_free_ratio" to dp.getValue(ValueKey.BiaSet.FAT_FREE_RATIO),
            "fat_free_mass" to dp.getValue(ValueKey.BiaSet.FAT_FREE_MASS),
            "impedance_mag" to dp.getValue(ValueKey.BiaSet.BODY_IMPEDANCE_MAGNITUDE),
            "impedance_deg" to dp.getValue(ValueKey.BiaSet.BODY_IMPEDANCE_DEGREE),
        )
    },
    SensorSpec("mf_bia", HealthTrackerType.MF_BIA_ON_DEMAND, SensorMode.ON_DEMAND,
        needsProfile = true, prompt = "Body comp 2 of 2: fingers on both buttons") { dp ->
        mapOf(
            "status" to dp.getValue(ValueKey.MfBiaSet.STATUS),
            "progress" to dp.getValue(ValueKey.MfBiaSet.PROGRESS),
            "mag_5k" to dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_5K),
            "phase_5k" to dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_5K),
            "mag_10k" to dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_10K),
            "phase_10k" to dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_10K),
            "mag_50k" to dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_50K),
            "phase_50k" to dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_50K),
            "mag_250k" to dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_250K),
            "phase_250k" to dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_250K),
        )
    },
)
