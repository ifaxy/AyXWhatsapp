package ayx.whatsapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device persistent cache of status updates.
 * Survives app/APK updates so the Status tab shows instantly on open
 * (before the Node gateway reconnects) and never resets to empty.
 * Expired (>24h) entries are dropped on load.
 */
object StatusData {
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.getSharedPreferences("statusdata", Context.MODE_PRIVATE)
    }

    fun load(): List<GatewayClient.StatusItem> {
        val json = prefs?.getString("items", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val cutoff = System.currentTimeMillis() - 24L * 3600L * 1000L
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val ts = o.optLong("ts", 0L)
                if (ts < cutoff) null else GatewayClient.StatusItem(
                    sender = o.optString("sender"),
                    name = o.optString("name"),
                    text = o.optString("text"),
                    mediaName = o.optString("mediaName").ifEmpty { null },
                    mediaType = o.optString("mediaType").ifEmpty { null },
                    thumb = o.optString("thumb").ifEmpty { null },
                    ts = ts,
                    mine = o.optBoolean("mine", false),
                    id = o.optString("id").ifEmpty { null },
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Merge fresh items with the cached ones (by sender+ts+id), keep last 24h, persist. */
    fun merge(fresh: List<GatewayClient.StatusItem>): List<GatewayClient.StatusItem> {
        val cutoff = System.currentTimeMillis() - 24L * 3600L * 1000L
        val map = LinkedHashMap<String, GatewayClient.StatusItem>()
        // keep existing first, then overlay fresh (fresh wins)
        for (s in load()) if (s.ts >= cutoff) map[keyOf(s)] = s
        for (s in fresh) if (s.ts >= cutoff) map[keyOf(s)] = s
        val merged = map.values.sortedByDescending { it.ts }.take(150)
        save(merged)
        return merged
    }

    private fun keyOf(s: GatewayClient.StatusItem): String =
        s.sender.substringBefore("@").substringBefore(":").filter { it.isDigit() } + ":" + (s.id ?: s.ts.toString())

    fun save(items: List<GatewayClient.StatusItem>) {
        try {
            val arr = JSONArray()
            for (s in items) {
                arr.put(JSONObject().apply {
                    put("sender", s.sender)
                    put("name", s.name)
                    put("text", s.text)
                    put("mediaName", s.mediaName ?: "")
                    put("mediaType", s.mediaType ?: "")
                    put("thumb", s.thumb ?: "")
                    put("ts", s.ts)
                    put("mine", s.mine)
                    put("id", s.id ?: "")
                })
            }
            prefs?.edit()?.putString("items", arr.toString())?.apply()
        } catch (e: Exception) {}
    }
}
