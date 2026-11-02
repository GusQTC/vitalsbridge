package dev.localvitals.bridge.phone

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant

/** Writes watch-collected samples into Health Connect so OpenVitals can read them. */
object HealthConnectWriter {

    /** @return summary string of how many records were written. */
    suspend fun write(context: Context, payload: VitalsPayload): String {
        val client = HealthConnectClient.getOrCreate(context)

        val hrRecords = payload.hr
            .filter { it.b in 1..300 }
            .map { p ->
                HeartRateRecord(
                    startTime = Instant.ofEpochMilli(p.t),
                    startZoneOffset = null,
                    endTime = Instant.ofEpochMilli(p.t + 60_000),
                    endZoneOffset = null,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = Instant.ofEpochMilli(p.t),
                            beatsPerMinute = p.b.toLong()
                        )
                    ),
                    metadata = Metadata.unknownRecordingMethod()
                )
            }

        val stepRecords = payload.steps
            .filter { it.s > 0 && it.e > it.t }
            .map { p ->
                StepsRecord(
                    startTime = Instant.ofEpochMilli(p.t),
                    startZoneOffset = null,
                    endTime = Instant.ofEpochMilli(p.e),
                    endZoneOffset = null,
                    count = p.s.toLong(),
                    metadata = Metadata.unknownRecordingMethod()
                )
            }

        if (hrRecords.isEmpty() && stepRecords.isEmpty()) return "nothing to write"

        client.insertRecords(
            buildList {
                addAll(hrRecords)
                addAll(stepRecords)
            }
        )
        return "hr=${hrRecords.size} steps=${stepRecords.size}"
    }
}
