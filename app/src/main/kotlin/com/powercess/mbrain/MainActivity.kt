package com.powercess.mbrain

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.powercess.mbrain.gateway.GatewayRuntime
import com.powercess.mbrain.gateway.MBrainService
import com.powercess.mbrain.ui.MBrainApp
import com.powercess.mbrain.remote.RemoteRuntime

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GatewayRuntime.initialize(applicationContext)
        RemoteRuntime.initialize(applicationContext)
        setContent {
            MBrainApp(
                start = {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    runCatching { startForegroundService(Intent(this, MBrainService::class.java)) }
                        .onFailure { GatewayRuntime.failed(Exception(it)) }
                },
                stop = { stopService(Intent(this, MBrainService::class.java)) },
            )
        }
    }
    override fun onResume() { super.onResume(); GatewayRuntime.refreshPermissions() }
}
