package dev.localvitals.bridge.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.localvitals.bridge.wear.data.SampleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles both directions of the bridge protocol:
 *
 * - /vitals/pull (message from the phone): push pending samples now.
 * - /vitals/batch deletion (phone consumed the batch and wrote it to Health
 *   Connect): mark the pushed rows as synced, then push again if newer
 *   samples accumulated in the meantime.
 */
class WatchPullService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != SyncManager.PULL_PATH) return
        android.util.Log.i("VitalsBridge", "Pull request from node ${event.sourceNodeId}")
        scope.launch {
            try {
                SyncManager.pushPending(this@WatchPullService)
            } catch (e: Exception) {
                android.util.Log.e("VitalsBridge", "Failed to answer pull", e)
            }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_DELETED) continue
            if (event.dataItem.uri.path != SyncManager.BATCH_PATH) continue
            android.util.Log.i("VitalsBridge", "Batch acked (deleted by phone)")
            scope.launch {
                try {
                    val repo = SampleRepository.get(applicationContext)
                    repo.markPendingSyncedUpTo(repo.lastPushedAt)
                    // Push again in case new samples arrived since the last put.
                    SyncManager.pushPending(applicationContext)
                } catch (e: Exception) {
                    android.util.Log.e("VitalsBridge", "Failed to ack batch", e)
                }
            }
        }
    }
}
