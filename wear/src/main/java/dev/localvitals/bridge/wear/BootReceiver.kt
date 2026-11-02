package dev.localvitals.bridge.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms the pipeline after a watch reboot or app update:
 * re-registers the passive listener (permissions persist) and makes sure the
 * periodic sync job exists.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.MY_PACKAGE_REPLACED"
        ) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SyncWorker.schedulePeriodic(context)
                val ok = SyncManager.registerPassive(context)
                android.util.Log.i("VitalsBridge", "Boot re-registration: ok=$ok")
            } catch (e: Exception) {
                android.util.Log.e("VitalsBridge", "Boot re-registration failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
