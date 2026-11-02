package dev.localvitals.bridge.phone

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StatusActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var btnPermissions: Button
    private lateinit var btnCheck: Button

    private val permissionRequest = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ -> scope.launch { updateStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        status = findViewById(R.id.status)
        btnPermissions = findViewById(R.id.btnPermissions)
        btnCheck = findViewById(R.id.btnCheck)

        btnPermissions.setOnClickListener {
            val client = HealthConnectClient.getOrCreate(this)
            val perms = setOf(
                HealthPermission.getWritePermission(HeartRateRecord::class),
                HealthPermission.getWritePermission(StepsRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(StepsRecord::class)
            )
            permissionRequest.launch(perms)
        }

        btnCheck.setOnClickListener { scope.launch { updateStatus() } }
        scope.launch {
            SyncPullWorker.schedulePeriodic(this@StatusActivity)
            PullManager.pullNow(this@StatusActivity)
            updateStatus()
        }
    }

    private suspend fun updateStatus() {
        try {
            val client = HealthConnectClient.getOrCreate(this)
            val granted = client.permissionController.getGrantedPermissions()
            val hrGranted = granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class)) && granted.contains(HealthPermission.getWritePermission(HeartRateRecord::class))
            val stepsGranted = granted.contains(HealthPermission.getReadPermission(StepsRecord::class)) && granted.contains(HealthPermission.getWritePermission(StepsRecord::class))

            val since = Instant.now().minus(Duration.ofHours(24))
            val hr = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(since, Instant.now()))
            ).records
            val steps = client.readRecords(
                ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(since, Instant.now()))
            ).records

            val latestHr = hr.flatMap { it.samples }.maxByOrNull { it.time }
            runOnUiThread {
                status.text = buildString {
                    appendLine("HC write perms: HR=$hrGranted steps=$stepsGranted")
                    appendLine("Last 24h records: HR=${hr.size} steps=${steps.size}")
                    latestHr?.let { append("Latest HR: ${it.beatsPerMinute} bpm @ ${it.time}") }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { status.text = "Error: ${e.message}" }
        }
    }
}
