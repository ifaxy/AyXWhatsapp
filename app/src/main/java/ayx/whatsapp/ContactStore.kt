package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * Local persistent store of number/jid -> display name.
 * Populated after login from: device phonebook + WhatsApp synced contacts.
 * Used on the home screen to resolve chat names (by full jid OR by number).
 */
object ContactStore {
    // key can be a full jid, a @lid jid, or a pure digit-number. value = display name.
    val map = mutableStateMapOf<String, String>()
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("contactstore", Context.MODE_PRIVATE)
        prefs!!.all.forEach { (k, v) -> if (v is String && v.isNotBlank()) map[k] = v }
    }

    /** Save under both the raw key and its digit-number form. */
    fun put(key: String, name: String) {
        if (key.isBlank() || name.isBlank()) return
        val e = prefs?.edit()
        if (map[key] != name) { map[key] = name; e?.putString(key, name) }
        val digits = key.substringBefore("@").substringBefore(":").filter { it.isDigit() }
        if (digits.isNotBlank() && map[digits] != name) { map[digits] = name; e?.putString(digits, name) }
        e?.apply()
    }

    fun putAll(pairs: List<Pair<String, String>>) { pairs.forEach { put(it.first, it.second) } }

    /** Look up a name for a chat jid: exact jid, then by number, then by last-10 digits. */
    fun nameFor(jid: String): String? {
        SetName.get(jid)?.let { return it }   // user-set alias always wins
        map[jid]?.let { if (it.isNotBlank()) return it }
        val num = jid.substringBefore("@").substringBefore(":").filter { it.isDigit() }
        if (num.isBlank()) return null
        map[num]?.let { if (it.isNotBlank()) return it }
        if (num.length >= 10) {
            val l10 = num.takeLast(10)
            map.entries.firstOrNull { it.key.filter { c -> c.isDigit() }.takeLast(10) == l10 && it.value.isNotBlank() }?.let { return it.value }
        }
        return null
    }
}
