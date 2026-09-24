package ayx.whatsapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Tiny REST client for the in-process node server on 127.0.0.1. */
object GatewayClient {
    private val base = "http://127.0.0.1:${NodeRuntime.PORT}"

    data class Status(
        val connection: String = "?",
        val registered: Boolean = false,
        val me: String? = null,
        val lastError: String? = null,
        val hasQr: Boolean = false,
        val pairingCode: String? = null,
        val reachable: Boolean = false,
    )

    data class Rule(val match: String, val reply: String, val mode: String = "contains")
    data class Settings(
        val alwaysOnline: Boolean = false,
        val autoRead: Boolean = false,
        val autoReplyEnabled: Boolean = false,
        val rules: List<Rule> = emptyList(),
        val aiReplyEnabled: Boolean = false,
        val aiApiUrl: String = "",
        val aiApiKey: String = "",
        val aiModel: String = "",
        val hideStatusRead: Boolean = true,
        val aiSystemPrompt: String = "",
        val saveMedia: Boolean = false,
        val stayOffline: Boolean = false,
    )
    data class Contact(val jid: String, val name: String, val number: String)
    data class Msg(val chat: String, val name: String, val fromMe: Boolean, val text: String, val ts: Long,
                   val mediaName: String? = null, val mediaType: String? = null, val thumb: String? = null, val deleted: Boolean = false, val id: String? = null, val reaction: String? = null, val sender: String? = null, val quotedText: String? = null)

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        try {
            val o = get("/status")
            Status(
                connection = o.optString("connection", "?"),
                registered = o.optBoolean("registered", false),
                me = o.optString("me").ifEmpty { null },
                lastError = o.optString("lastError").ifEmpty { null },
                hasQr = o.optBoolean("hasQr", false),
                pairingCode = o.optString("pairingCode").ifEmpty { null },
                reachable = true,
            )
        } catch (e: Exception) {
            Status(connection = "starting…", reachable = false)
        }
    }

    suspend fun qrBytes(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val c = (URL("$base/qr.png").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 3000; readTimeout = 8000
            }
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) { null }
    }

    suspend fun pair(number: String): String = withContext(Dispatchers.IO) {
        val o = post("/pair", JSONObject().put("number", number))
        when {
            o.optBoolean("registered", false) -> "registered"
            o.has("code") -> o.getString("code")
            else -> throw RuntimeException(o.optString("error", "unknown error"))
        }
    }

    suspend fun send(to: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val o = post("/send", JSONObject().put("to", to).put("text", text))
        if (o.optBoolean("ok", false)) true else throw RuntimeException(o.optString("error", "send failed"))
    }

    suspend fun sendToJid(jid: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val o = post("/sendraw", JSONObject().put("jid", jid).put("text", text))
        if (o.optBoolean("ok", false)) true else throw RuntimeException(o.optString("error", "send failed"))
    }

    fun mediaUrl(name: String) = "$base/media/$name"

    suspend fun sendMedia(jid: String, dataB64: String, type: String, filename: String, caption: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("jid", jid).put("type", type).put("data", dataB64).put("filename", filename).put("caption", caption)
        val o = post("/sendmedia", body)
        if (o.optBoolean("ok", false)) true else throw RuntimeException(o.optString("error", "send failed"))
    }

    suspend fun setProfileName(name: String) = withContext(Dispatchers.IO) {
        val o = post("/profile/name", JSONObject().put("name", name))
        if (!o.optBoolean("ok", false)) throw RuntimeException(o.optString("error", "failed"))
    }
    suspend fun setProfileBio(bio: String) = withContext(Dispatchers.IO) {
        val o = post("/profile/bio", JSONObject().put("bio", bio))
        if (!o.optBoolean("ok", false)) throw RuntimeException(o.optString("error", "failed"))
    }
    suspend fun setProfilePicture(dataB64: String): Boolean = withContext(Dispatchers.IO) {
        post("/profile/picture", JSONObject().put("data", dataB64)).optBoolean("ok", false)
    }
    suspend fun deleteChat(jid: String) = withContext(Dispatchers.IO) {
        post("/chat/delete", JSONObject().put("jid", jid)).optBoolean("ok", false)
    }
    suspend fun react(jid: String, id: String, emoji: String, fromMe: Boolean) = withContext(Dispatchers.IO) {
        post("/react", JSONObject().put("jid", jid).put("id", id).put("emoji", emoji).put("fromMe", fromMe)).optBoolean("ok", false)
    }
    data class Song(val title: String, val artist: String, val image: String?, val url: String)
    // returns synced lyrics as (timeMs, line); empty if none
    suspend fun getLyrics(title: String, artist: String): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        try {
            val t = java.net.URLEncoder.encode(title, "UTF-8"); val a = java.net.URLEncoder.encode(artist, "UTF-8")
            val o = get("/music/lyrics?title=$t&artist=$a")
            val synced = o.optString("synced")
            if (synced.isBlank()) return@withContext emptyList()
            val rx = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\](.*)")
            synced.lines().mapNotNull { line ->
                val m = rx.find(line) ?: return@mapNotNull null
                val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong()
                val cs = m.groupValues[3].ifEmpty { "0" }.take(2).padEnd(2, '0').toLong()
                val ms = min * 60000 + sec * 1000 + cs * 10
                val text = m.groupValues[4].trim()
                if (text.isEmpty()) null else ms to text
            }.sortedBy { it.first }
        } catch (e: Exception) { emptyList() }
    }
    suspend fun searchMusic(q: String): Pair<List<Song>, String> = withContext(Dispatchers.IO) {
        try {
            val enc = java.net.URLEncoder.encode(q, "UTF-8")
            val c = (URL("$base/music/search?q=$enc").openConnection() as HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 30000 }
            val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val obj = JSONObject(txt)
            val arr = obj.optJSONArray("items")
            val songs = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i); val url = o.optString("url")
                if (url.isBlank()) null else Song(o.optString("title"), o.optString("artist"), o.optString("image").ifEmpty { null }, url)
            }
            songs to obj.optString("error")
        } catch (e: Exception) { emptyList<Song>() to (e.message ?: "connection error") }
    }

    suspend fun postStatus(type: String, dataB64: String, caption: String, audience: String, jids: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val arr = org.json.JSONArray(); jids.forEach { arr.put(it) }
            val body = JSONObject().put("type", type).put("data", dataB64).put("caption", caption).put("audience", audience).put("jids", arr).toString()
            val c = (URL("$base/status/post").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 4000; readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            JSONObject(txt).optBoolean("ok", false)
        } catch (e: Exception) { false }
    }

    suspend fun onWhatsApp(numbers: List<String>): Map<String, String?> = withContext(Dispatchers.IO) {
        try {
            val arr = org.json.JSONArray(); numbers.forEach { arr.put(it) }
            val body = JSONObject().put("numbers", arr).toString()
            val c = (URL("$base/onwhatsapp").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 4000; readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val items = JSONObject(txt).optJSONArray("items") ?: return@withContext emptyMap()
            val m = HashMap<String, String?>()
            for (i in 0 until items.length()) { val o = items.getJSONObject(i); m[o.optString("number")] = o.optString("lid").ifEmpty { null } }
            m
        } catch (e: Exception) { emptyMap() }
    }

    suspend fun sendReply(jid: String, text: String, quotedId: String) = withContext(Dispatchers.IO) {
        post("/sendreply", JSONObject().put("jid", jid).put("text", text).put("quotedId", quotedId)).optBoolean("ok", false)
    }

    suspend fun deleteMessage(jid: String, id: String?, text: String, ts: Long, forEveryone: Boolean, fromMe: Boolean) = withContext(Dispatchers.IO) {
        val o = JSONObject().put("jid", jid).put("forEveryone", forEveryone).put("fromMe", fromMe).put("text", text).put("ts", ts)
        if (id != null) o.put("id", id)
        post("/message/delete", o).optBoolean("ok", false)
    }
    suspend fun getContacts(): List<Contact> = withContext(Dispatchers.IO) {
        try {
            val out = mutableListOf<Contact>()
            get("/contacts").optJSONArray("items")?.let { a ->
                for (i in 0 until a.length()) { val c = a.getJSONObject(i); out.add(Contact(c.optString("jid"), c.optString("name"), c.optString("number"))) }
            }
            out
        } catch (e: Exception) { emptyList() }
    }
    suspend fun exportSession(): String = withContext(Dispatchers.IO) { get("/session/export").toString() }
    suspend fun importSession(json: String) = withContext(Dispatchers.IO) {
        val c = (java.net.URL("$base/session/import").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 4000; readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(json.toByteArray()) }
        readJson(c).optBoolean("ok", false)
    }
    suspend fun blockChat(jid: String, block: Boolean) = withContext(Dispatchers.IO) {
        val o = post("/block", JSONObject().put("jid", jid).put("action", if (block) "block" else "unblock"))
        if (!o.optBoolean("ok", false)) throw RuntimeException(o.optString("error", "failed"))
    }

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        post("/logout", JSONObject()).optBoolean("ok", false)
    }

    suspend fun getSettings(): Settings = withContext(Dispatchers.IO) {
        parseSettings(get("/settings"))
    }

    /** Post a partial settings patch; returns the updated settings. */
    suspend fun patchSettings(patch: JSONObject): Settings = withContext(Dispatchers.IO) {
        parseSettings(post("/settings", patch))
    }

    suspend fun setRules(rules: List<Rule>): Settings = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        rules.forEach { arr.put(JSONObject().put("match", it.match).put("reply", it.reply).put("mode", it.mode)) }
        parseSettings(post("/settings", JSONObject().put("autoReplyRules", arr)))
    }

    suspend fun getDeleted(): List<Msg> = withContext(Dispatchers.IO) {
        try { parseMsgs(get("/deleted")) } catch (e: Exception) { emptyList() }
    }

    suspend fun getMessages(): List<Msg> = withContext(Dispatchers.IO) {
        try { parseMsgs(get("/messages")) } catch (e: Exception) { emptyList() }
    }

    data class StatusItem(val sender: String, val name: String, val text: String,
        val mediaName: String? = null, val mediaType: String? = null, val thumb: String? = null, val ts: Long = 0L, val mine: Boolean = false, val id: String? = null)
    suspend fun deleteStatus(id: String): Boolean = withContext(Dispatchers.IO) {
        post("/status/delete", JSONObject().put("id", id)).optBoolean("ok", false)
    }
    suspend fun getMe(): String = withContext(Dispatchers.IO) {
        try { get("/me").optString("jid") } catch (e: Exception) { "" }
    }
    suspend fun getStatuses(): List<StatusItem> = withContext(Dispatchers.IO) {
        try {
            val arr = get("/statuses").optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val md = o.optJSONObject("media")
                StatusItem(
                    sender = o.optString("sender"),
                    name = o.optString("name").ifEmpty { o.optString("sender").substringBefore("@") },
                    text = o.optString("text"),
                    mediaName = md?.optString("name")?.ifEmpty { null },
                    mediaType = md?.optString("type")?.ifEmpty { null },
                    thumb = md?.optString("thumb")?.ifEmpty { null },
                    ts = o.optLong("ts", 0L),
                    mine = o.optBoolean("mine", false),
                    id = o.optString("id").ifEmpty { null },
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    data class Presence(val online: Boolean, val lastSeen: Long)
    suspend fun getPresence(jid: String): Presence = withContext(Dispatchers.IO) {
        try {
            val enc = java.net.URLEncoder.encode(jid, "UTF-8")
            val o = get("/presence?jid=$enc")
            Presence(o.optString("presence") == "available", o.optLong("lastSeen", 0L))
        } catch (e: Exception) { Presence(false, 0L) }
    }

    suspend fun dpBytes(jid: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val enc = java.net.URLEncoder.encode(jid, "UTF-8")
            val c = (URL("$base/dp?jid=$enc").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 4000; readTimeout = 15000
            }
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) { null }
    }

    suspend fun mediaBytes(name: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val c = (URL("$base/media/$name").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 4000; readTimeout = 20000
            }
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) { null }
    }

    private fun parseSettings(o: JSONObject): Settings {
        val rules = mutableListOf<Rule>()
        o.optJSONArray("autoReplyRules")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                rules.add(Rule(r.optString("match"), r.optString("reply"), r.optString("mode", "contains")))
            }
        }
        return Settings(
            alwaysOnline = o.optBoolean("alwaysOnline", false),
            autoRead = o.optBoolean("autoRead", false),
            autoReplyEnabled = o.optBoolean("autoReplyEnabled", false),
            rules = rules,
            aiReplyEnabled = o.optBoolean("aiReplyEnabled", false),
            aiApiUrl = o.optString("aiApiUrl", ""),
            aiApiKey = o.optString("aiApiKey", ""),
            aiModel = o.optString("aiModel", ""),
            hideStatusRead = o.optBoolean("hideStatusRead", true),
            aiSystemPrompt = o.optString("aiSystemPrompt", ""),
            saveMedia = o.optBoolean("saveMedia", false),
            stayOffline = o.optBoolean("stayOffline", false),
        )
    }

    private fun parseMsgs(o: JSONObject): List<Msg> {
        val out = mutableListOf<Msg>()
        o.optJSONArray("items")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val md = m.optJSONObject("media")
                out.add(Msg(
                    m.optString("chat"), m.optString("name"), m.optBoolean("fromMe", false), m.optString("text"), m.optLong("ts"),
                    mediaName = md?.optString("name")?.ifEmpty { null },
                    mediaType = md?.optString("type")?.ifEmpty { null },
                    thumb = md?.optString("thumb")?.ifEmpty { null },
                    deleted = m.optBoolean("deleted", false),
                    id = m.optString("id").ifEmpty { null },
                    reaction = m.optString("reaction").ifEmpty { null },
                    sender = m.optString("sender").ifEmpty { null },
                    quotedText = m.optJSONObject("quoted")?.optString("text")?.ifEmpty { null },
                ))
            }
        }
        return out
    }

    private fun get(path: String): JSONObject {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 3000; readTimeout = 8000
        }
        return readJson(c)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 3000; readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return readJson(c)
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        return try {
            JSONObject(if (text.isBlank()) "{}" else text)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", text.take(180))
        }
    }
}
