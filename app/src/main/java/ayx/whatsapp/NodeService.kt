package ayx.whatsapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Foreground service that keeps the node process (and the WhatsApp socket) alive. */
private fun notifIcon(c: android.content.Context): Int {
    val id = c.resources.getIdentifier("ic_notification", "drawable", c.packageName)
    return if (id != 0) id else android.R.drawable.ic_dialog_email
}

class NodeService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        NodeRuntime.ensureStarted(applicationContext)
        startNotifPolling()
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun startNotifPolling() {
        scope.launch {
            val prefs = getSharedPreferences("wagw", Context.MODE_PRIVATE)
            var lastTs = prefs.getLong("notif_ts", System.currentTimeMillis())
            while (isActive) {
                try {
                    val msgs = GatewayClient.getMessages()
                    val maxTs = msgs.maxOfOrNull { it.ts } ?: lastTs
                    if (maxTs > lastTs) {
                        msgs.filter { !it.fromMe && !it.deleted && it.ts > lastTs && it.chat != currentOpenChat }
                            .groupBy { it.chat }.forEach { (chat, group) ->
                                val m = group.maxByOrNull { it.ts }!!
                                val dp = try { GatewayClient.dpBytes(chat)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } catch (_: Exception) { null }
                                val body = if (m.text.isNotBlank()) m.text else if (m.mediaType != null) "[" + m.mediaType + "]" else ""
                                val name = group.firstOrNull { !it.name.isNullOrBlank() }?.name ?: chat.substringBefore("@")
                                NotificationHelper.notifyMessage(applicationContext, chat, name, body, dp)
                            }
                        lastTs = maxTs
                        prefs.edit().putLong("notif_ts", lastTs).apply()
                    }
                } catch (_: Exception) {}
                delay(4000)
            }
        }
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
        @Volatile var currentOpenChat: String? = null
        private const val CHANNEL = "wagw_engine"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, NodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
