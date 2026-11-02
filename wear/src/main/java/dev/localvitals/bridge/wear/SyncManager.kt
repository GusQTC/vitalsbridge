package dev.localvitals.bridge.wear

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.DataType
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.localvitals.bridge.wear.data.SampleRepository
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HrPayload(val t: Long, val b: Int)

/** Step count over an explicit [start, end) window. */
@Serializable
data class StepsPayload(val t: Long, val e: Long, val s: Int)

@Serializable
data class VitalsPayload(
    val hr: List<HrPayload> = emptyList(),
    val steps: List<StepsPayload> = emptyList()
)

data class PushResult(val deliveredNodes: Int, val hrSent: Int, val stepsSent: Int) {
    val sentSomething: Boolean get() = hrSent + stepsSent > 0
}

/**
 * All watch-side bridge logic that must work without UI: passive-monitoring
 * registration (re-run on boot) and pushing pending samples to the phone.
 *
 * Delivery uses MessageClient so a batch is only marked synced after the
 * phone acknowledged it. If no phone is connected nothing is marked and the
 * next run (WorkManager every 15 min, or the next incoming sample) retries.
 */
object SyncManager {

    const val MESSAGE_PATH = "/vitals/push"
    const val PULL_PATH = "/vitals/pull"
    const val BATCH_PATH = "/vitals/batch"
    private const val HR_BATCH = 400
    private const val STEPS_BATCH = 300

    private val json = Json { ignoreUnknownKeys = true }

    /** Registers the passive listener. Idempotent; safe to call on every boot. */
    suspend fun registerPassive(context: Context): Boolean {
        return try {
            val client = HealthServices.getClient(context).passiveMonitoringClient
            val config = PassiveListenerConfig.Builder()
                .setDataTypes(setOf(DataType.HEART_RATE_BPM, DataType.STEPS_DAILY))
                .setShouldUserActivityInfoBeRequested(false)
                .build()
            @Suppress("UNCHECKED_CAST")
            val serviceClass =
                PassiveMonitorService::class.java as Class<androidx.health.services.client.PassiveListenerService>
            client.setPassiveListenerServiceAsync(serviceClass, config).await()
            SampleRepository.get(context).markRegistered()
            true
        } catch (e: Exception) {
            android.util.Log.e("VitalsBridge", "Passive registration failed", e)
            false
        }
    }

    /**
     * Queues all pending samples as a DataItem batch. Delivery is handled by
     * GMS (replicates whenever a phone connects — no node list needed, which
     * matters because some Samsung watches report an empty connectedNodes).
     * The phone DELETES the batch after writing it to Health Connect; the
     * deletion event is our ack — see [WatchPullService.onDataChanged].
     */
    suspend fun pushPending(context: Context): PushResult {
        val repo = SampleRepository.get(context)
        val hr = repo.pendingHeartRate()
        val steps = repo.pendingSteps()
        if (hr.isEmpty() && steps.isEmpty()) return PushResult(0, 0, 0)

        val payload = VitalsPayload(
            hr = hr.map { HrPayload(t = it.timestampMs, b = it.bpm) },
            steps = steps.map { StepsPayload(t = it.startMs, e = it.endMs, s = it.stepsValue) }
        )
        val bytes = json.encodeToString(payload).encodeToByteArray()

        val request = PutDataMapRequest.create(BATCH_PATH).apply {
            dataMap.putByteArray("payload", bytes)
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request).await()

        val now = System.currentTimeMillis()
        repo.lastPushedAt = now
        android.util.Log.i("VitalsBridge", "pushPending: queued hr=${hr.size} steps=${steps.size} (${bytes.size} bytes)")
        return PushResult(1, hr.size, steps.size)
    }
}
