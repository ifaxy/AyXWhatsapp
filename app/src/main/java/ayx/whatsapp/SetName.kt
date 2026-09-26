package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * User-set local display names (aliases) for contacts.
 *
 * Highest-priority name source: whatever the user types here overrides the
 * device-phonebook name and the WhatsApp pushName everywhere in the app
 * (chat list, chat header, status list/viewer, group sender labels).
 *
 * It is stored ONLY on this device (SharedPreferences "setname") and is
 * NEVER pushed to WhatsApp — the contact's real WhatsApp name is untouched.
 * Keyed by the digit phone number so one alias matches the same person across
 * their @s.whatsapp.net and @lid jids.
 */
object SetName {
    val map = mutableStateMapOf<String, String>()   // digit-number -> custom name
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("setname", Context.MODE_PRIVATE)
        prefs!!.all.forEach { (k, v) -> if (v is String && v.isNotBlank()) map[k] = v }
    }

    private fun keyOf(jid: String) =
        jid.substringBefore("@").substringBefore(":").filter { it.isDigit() }

    /** Custom name for a jid/number, or null if the user never set one. */
    fun get(jid: String): String? {
        if (jid.isBlank()) return null
        val num = keyOf(jid)
        if (num.isBlank()) return null
        map[num]?.let { if (it.isNotBlank()) return it }
        if (num.length >= 10) {
            val l10 = num.takeLast(10)
            map.entries.firstOrNull { it.key.takeLast(10) == l10 && it.value.isNotBlank() }?.let { return it.value }
        }
        return null
    }

    fun set(jid: String, name: String) {
        val num = keyOf(jid); if (num.isBlank()) return
        val n = name.trim()
        if (n.isBlank()) { clear(jid); return }
        map[num] = n
        prefs?.edit()?.putString(num, n)?.apply()
    }

    fun clear(jid: String) {
        val num = keyOf(jid); if (num.isBlank()) return
        map.remove(num)
        prefs?.edit()?.remove(num)?.apply()
    }
}
