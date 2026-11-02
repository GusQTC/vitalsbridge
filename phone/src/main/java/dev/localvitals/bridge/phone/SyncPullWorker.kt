package dev.localvitals.bridge.phone

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Phone-initiated pull: asks every connected watch for pending samples.
 *
 * Needed because some Samsung watches report an empty getConnectedNodes() list
 * on their own side while the underlying data layer works fine — phone→watch
 * messages get through, and the watch replies to the sender's node id.
 */
object PullManager {

    const val PULL_PATH = "/vitals/pull"

    /** @return number of watch nodes the pull request reached. */
    suspend fun pullNow(context: Context): Int {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        var reached = 0
        for (node in nodes) {
            try {
                val listeners = Wearable.getMessageClient(context)
                    .sendMessage(node.id, PULL_PATH, ByteArray(0)).await()
                if (listeners > 0) reached++
                android.util.Log.i("VitalsBridge", "Pull sent to ${node.displayName} (${node.id}), listeners=$listeners")
            } catch (e: Exception) {
                android.util.Log.w("VitalsBridge", "Pull to ${node.displayName} failed", e)
            }
        }
        android.util.Log.i("VitalsBridge", "pullNow: ${nodes.size} node(s) known, reached=$reached")
        return reached
    }
}

class SyncPullWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            PullManager.pullNow(applicationContext)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("VitalsBridge", "SyncPullWorker failed, will retry", e)
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_WORK = "vitalsbridge-periodic-pull"

        fun schedulePeriodic(context: Context) {
            val request = androidx.work.PeriodicWorkRequestBuilder<SyncPullWorker>(
                java.time.Duration.ofMinutes(15)
            ).build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
