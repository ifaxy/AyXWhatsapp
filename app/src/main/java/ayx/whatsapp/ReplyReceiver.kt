package ayx.whatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jid = intent.getStringExtra(NotificationHelper.EXTRA_JID) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotificationHelper.KEY_REPLY)?.toString()
        if (reply.isNullOrBlank()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { GatewayClient.sendToJid(jid, reply) } catch (_: Exception) {}
            finally {
                NotificationHelper.cancel(context, jid)
                pending.finish()
            }
        }
    }
}
