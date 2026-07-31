package com.example.testwatch.sync

import com.example.testwatch.data.HrSample
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WireSample(val ts: Long, val bpm: Int, val status: Int)

@Serializable
data class WireBatch(val participantId: String, val samples: List<WireSample>)

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
}
