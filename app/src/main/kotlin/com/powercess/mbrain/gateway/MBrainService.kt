package com.powercess.mbrain.gateway

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.powercess.mbrain.MainActivity
import com.powercess.mbrain.R
import io.droidmcp.core.DroidMcp
import io.droidmcp.server.DroidMcpServerService
import com.powercess.mbrain.remote.RemoteRuntime

class MBrainService : DroidMcpServerService() {
    override fun createServer(): DroidMcp = GatewayRuntime.createServer(this)
    override fun onServerStarted(server: DroidMcp) {
        GatewayRuntime.started(server)
        RemoteRuntime.gatewayChanged(GatewayRuntime.PORT)
    }
    override fun onServerStartFailed(error: Exception) = GatewayRuntime.failed(error)
    override fun onServerStopped() { RemoteRuntime.gatewayChanged(null); GatewayRuntime.stopped() }

    override fun onDestroy() {
        RemoteRuntime.gatewayChanged(null)
        GatewayRuntime.shutdown()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        super.onStartCommand(intent, flags, startId)
        // Require an explicit user start after process death; tokens are session-scoped.
        return START_NOT_STICKY
    }

    override fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MBrainService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, DroidMcpServerService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mbrain)
            .setContentTitle("MBrain 正在运行")
            .setContentText("本机 MCP 能力网关 · 端口 ${GatewayRuntime.PORT}")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "停止服务", stop)
            .setOngoing(true)
            .build()
    }

    companion object { private const val ACTION_STOP = "com.powercess.mbrain.STOP" }
}
