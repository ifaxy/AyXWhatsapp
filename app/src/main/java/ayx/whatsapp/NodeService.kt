package ayx.whatsapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Foreground service that keeps the node process (and the WhatsApp socket) alive. */
private fun notifIcon(c: android.content.Context): Int {
    val id = c.resources.getIdentifier("ic_notification", "drawable", c.packageName)
    return if (id != 0) id else android.R.drawable.ic_dialog_email
}

class NodeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        NodeRuntime.ensureStarted(applicationContext)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "WA Gateway", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("WA Gateway running")
            .setContentText("Gateway engine is active")
            .setSmallIcon(notifIcon(this))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "wagw_engine"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, NodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
