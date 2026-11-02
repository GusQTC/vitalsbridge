package dev.localvitals.bridge.wear

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic (15 min) retry loop for pushing pending samples to the phone.
 * Cheap when there is nothing pending or no phone connected.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            SyncManager.pushPending(applicationContext)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("VitalsBridge", "SyncWorker failed, will retry", e)
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_WORK = "vitalsbridge-periodic-sync"

        fun schedulePeriodic(context: Context) {
            val request = androidx.work.PeriodicWorkRequestBuilder<SyncWorker>(
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
