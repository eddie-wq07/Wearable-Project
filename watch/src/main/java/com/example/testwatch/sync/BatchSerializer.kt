package com.example.testwatch.sync

/** Converts Room rows into the JSON wire format sent from watch to phone (and decodes on the
 *  receiving side). Two parallel formats: WireBatch for HR, WireSensorBatch for everything else. */

import com.example.testwatch.data.HrSample
import com.example.testwatch.data.SensorBatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class WireSample(val ts: Long, val bpm: Int, val status: Int)

@Serializable
data class WireBatch(val participantId: String, val samples: List<WireSample>)

@Serializable
data class WireSensorRow(val sensor: String, val ts: Long, val points: JsonElement)

@Serializable
data class WireSensorBatch(val participantId: String, val rows: List<WireSensorRow>)

object BatchSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(participantId: String, samples: List<HrSample>): ByteArray {
        val batch = WireBatch(
            participantId = participantId,
            samples = samples.map { WireSample(it.timestampMs, it.bpm, it.status) },
        )
        return json.encodeToString(batch).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): WireBatch =
        json.decodeFromString(WireBatch.serializer(), String(bytes, Charsets.UTF_8))

    fun encodePoints(points: List<Map<String, Number>>): String =
        JsonArray(points.map { p -> JsonObject(p.mapValues { JsonPrimitive(it.value) }) }).toString()

    fun encodeSensorRows(participantId: String, batches: List<SensorBatch>): ByteArray {
        val wire = WireSensorBatch(
            participantId = participantId,
            rows = batches.map {
                WireSensorRow(it.sensor, it.timestampMs, json.parseToJsonElement(it.points))
            },
        )
        return json.encodeToString(wire).toByteArray(Charsets.UTF_8)
    }
}
