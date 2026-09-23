package com.powercess.mbrain.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.powercess.mbrain.MainActivity
import com.powercess.mbrain.R

class TunnelService : Service() {
    override fun onCreate() {
        super.onCreate()
        RemoteRuntime.initialize(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "内网穿透", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11, Intent(this, TunnelService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2410, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mbrain)
            .setContentTitle("MBrain 内网穿透").setContentText("隧道服务已启动，点按管理连接")
            .setContentIntent(open).addAction(android.R.drawable.ic_media_pause, "停止全部隧道", stop).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) RemoteRuntime.stopAll() else RemoteRuntime.serviceStarted()
        return START_NOT_STICKY
    }
    override fun onDestroy() { RemoteRuntime.serviceStopped(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL = "mbrain_tunnels"; private const val STOP = "com.powercess.mbrain.STOP_TUNNELS" }
}
