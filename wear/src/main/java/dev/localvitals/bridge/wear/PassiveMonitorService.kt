package dev.localvitals.bridge.wear

import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import dev.localvitals.bridge.wear.data.SampleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives passive data (heart rate, steps) from Health Services while the
 * watch is worn, stores samples locally, and opportunistically pushes them
 * to the phone (delivery only succeeds when a phone is connected; otherwise
 * the WorkManager retry loop picks it up).
 */
class PassiveMonitorService : PassiveListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        android.util.Log.i("VitalsBridge", "onNewDataPoints: types=${dataPoints.dataTypes}")
        scope.launch {
            try {
                SampleRepository.get(applicationContext).storeDataPoints(dataPoints)
                SyncManager.pushPending(applicationContext)
            } catch (e: Exception) {
                android.util.Log.e("VitalsBridge", "Failed to handle data points", e)
            }
        }
    }

    override fun onPermissionLost() {
        android.util.Log.w("VitalsBridge", "Permission lost — marking unregistered")
        SampleRepository.get(applicationContext).markUnregistered()
    }
}
