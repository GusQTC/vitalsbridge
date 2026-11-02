package dev.localvitals.bridge.phone

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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

/**
 * Receives JSON sample batches from the watch on /vitals/push (MessageClient)
 * and writes them into Health Connect so any HC reader (e.g. OpenVitals)
 * can use them. The watch only marks samples as synced once this service
 * acks, so no data is lost when the phone is away.
 */
class WatchMessageService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/vitals/push") return
        android.util.Log.i("VitalsBridge", "Message received: ${event.data.size} bytes from node ${event.sourceNodeId}")
        scope.launch {
            try {
                val payload = json.decodeFromString(VitalsPayload.serializer(), String(event.data))
                val written = HealthConnectWriter.write(this@WatchMessageService, payload)
                android.util.Log.i("VitalsBridge", "Health Connect write: $written")
            } catch (e: Exception) {
                android.util.Log.e("VitalsBridge", "Failed to process payload", e)
            }
        }
    }
}
