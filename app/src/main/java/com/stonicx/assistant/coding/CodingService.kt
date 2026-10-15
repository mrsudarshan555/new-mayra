package com.stonicx.assistant.coding

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps the process alive while Mayra is coding in the background (shows a Stop action). */
class CodingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { CodeJobs.cancel(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mayra coding", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, CodingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Mayra is coding")
            .setContentText("Kaam peeche chal raha hai").setOngoing(true).addAction(0, "Stop", stop).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "mayra_coding"
        private const val NOTIF_ID = 880_002
        private const val ACTION_STOP = "com.stonicx.assistant.coding.STOP"
        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, CodingService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) { }
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, CodingService::class.java)) } catch (_: Exception) { } }
    }
}
