package ayx.whatsapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

private fun notifIcon(c: android.content.Context): Int {
    val id = c.resources.getIdentifier("ic_notification", "drawable", c.packageName)
    return if (id != 0) id else android.R.drawable.ic_dialog_email
}

object AppNav { val pendingOpenChat = mutableStateOf<String?>(null) }

object NotificationHelper {
    const val CHANNEL = "messages"
    const val KEY_REPLY = "key_reply"
    const val EXTRA_JID = "jid"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }

    fun notifyMessage(ctx: Context, jid: String, name: String, text: String, dp: Bitmap?) {
        ensureChannel(ctx)
        val id = jid.hashCode()
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        val replyIntent = Intent(ctx, ReplyReceiver::class.java).setPackage(ctx.packageName).putExtra(EXTRA_JID, jid)
        val replyPending = PendingIntent.getBroadcast(ctx, id, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val replyAction = NotificationCompat.Action.Builder(notifIcon(ctx), "Reply", replyPending)
            .addRemoteInput(remoteInput).build()

        val openIntent = Intent(ctx, MainActivity::class.java)
            .putExtra("openChat", jid)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPending = PendingIntent.getActivity(ctx, id + 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(notifIcon(ctx))
            .setContentTitle(name)
            .setContentText(if (text.isBlank()) "\uD83D\uDCCE Media" else text)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(replyAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { if (dp != null) setLargeIcon(dp) }
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }

    fun cancel(ctx: Context, jid: String) {
        try { NotificationManagerCompat.from(ctx).cancel(jid.hashCode()) } catch (_: Exception) {}
    }
}
