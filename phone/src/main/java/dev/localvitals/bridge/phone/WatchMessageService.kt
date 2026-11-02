package dev.localvitals.bridge.phone

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.tasks.await
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
 * Consumes sample batches from the watch:
 *
 * - /vitals/batch (DataItem): delete the item first, then write to Health
 *   Connect. Deleting first is crash-safe: if we die before writing, the
 *   watch never sees the deletion, keeps the rows pending and re-sends.
 *   Writes are idempotent (clientRecordId), so re-delivery can't duplicate.
 * - /vitals/push (message): legacy path, same handling.
 *
 * The watch observes the deletion as its delivery ack.
 */
class WatchMessageService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (path != "/vitals/batch") continue
            val bytes = DataMapItem.fromDataItem(event.dataItem)
                .dataMap.getByteArray("payload") ?: continue
            android.util.Log.i("VitalsBridge", "Batch received: ${bytes.size} bytes")
            scope.launch {
                try {
                    Wearable.getDataClient(this@WatchMessageService)
                        .deleteDataItems(event.dataItem.uri).await()
                    val payload = json.decodeFromString(VitalsPayload.serializer(), String(bytes))
                    val written = HealthConnectWriter.write(this@WatchMessageService, payload)
                    android.util.Log.i("VitalsBridge", "Health Connect write: $written")
                } catch (e: Exception) {
                    android.util.Log.e("VitalsBridge", "Failed to process batch", e)
                }
            }
        }
    }

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
