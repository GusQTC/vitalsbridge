package dev.localvitals.bridge.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.localvitals.bridge.wear.data.SampleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StatusActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repo: SampleRepository
    private lateinit var status: TextView
    private lateinit var registeredView: TextView
    private lateinit var btnRegister: Button
    private lateinit var btnSync: Button

    private val permissionsRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) scope.launch { register() }
        else registeredView.text = "Permissions denied: ${results.filterValues { !it }.keys}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        repo = SampleRepository.get(this)
        status = findViewById(R.id.status)
        registeredView = findViewById(R.id.registered)
        btnRegister = findViewById(R.id.btnRegister)
        btnSync = findViewById(R.id.btnSync)

        btnRegister.setOnClickListener {
            val needed = listOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) scope.launch { register() }
            else permissionsRequest.launch(needed.toTypedArray())
        }

        btnSync.setOnClickListener {
            scope.launch {
                val r = SyncManager.pushPending(this@StatusActivity)
                status.text = if (r.sentSomething) "Pushed ${r.hrSent} HR, ${r.stepsSent} steps"
                else if (r.deliveredNodes == 0) "No phone connected — kept pending"
                else "Nothing pending to sync"
            }
        }

        scope.launch {
            repo.heartRateCount().collect { hr ->
                runOnUiThread { status.text = "HR samples: $hr" }
            }
        }
        scope.launch {
            repo.stepsCount().collect { steps ->
                runOnUiThread { registeredView.text = "Steps snapshots: $steps\nRegistered: ${repo.registered}" }
            }
        }
    }

    private suspend fun register() {
        if (SyncManager.registerPassive(this)) {
            SyncWorker.schedulePeriodic(this)
            registeredView.text = "Registered: true"
        }
    }
}
