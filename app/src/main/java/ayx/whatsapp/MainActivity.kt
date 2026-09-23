package ayx.whatsapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.app.KeyguardManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import android.provider.ContactsContract
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val APP_NAME = "AyX WhatsApp"
private val IOS_BLUE = Color(0xFF0A84FF)

private const val PRIVACY_TEXT = """WA Gateway runs entirely on your device. It does not collect, sell, or send your chats, contacts, or personal data to us or any third party.

1. Local only. Your WhatsApp session, messages, and media stay on your phone. There is no server owned by us.

2. WhatsApp. The app connects to WhatsApp's own servers to work as a linked device, exactly like WhatsApp Web.

3. AI auto-reply (optional). If you turn on AI reply, only the incoming message text is sent to the AI provider you configure (e.g. Groq) to generate a reply. This uses your own API key and is your choice. Keep it off if you don't want it.

4. Media and history. Saved media and message logs are stored only inside this app on your device. Unlink/reset or clearing app data removes them.

5. No tracking. No analytics, no ads, no telemetry.

6. Your responsibility. This is an unofficial WhatsApp tool. WhatsApp may restrict or ban accounts that use automation. Do not use it for spam. Use at your own risk.

By continuing you agree to use this app responsibly and accept these terms."""

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("openChat")?.let { AppNav.pendingOpenChat.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        intent?.getStringExtra("openChat")?.let { AppNav.pendingOpenChat.value = it }
        intent?.data?.let { uri ->
            val host = uri.host ?: ""
            val num = when {
                host.contains("wa.me") -> uri.pathSegments.firstOrNull()?.filter { it.isDigit() }
                else -> uri.getQueryParameter("phone")?.filter { it.isDigit() }
            }
            if (!num.isNullOrBlank()) AppNav.pendingOpenChat.value = num + "@s.whatsapp.net"
        }

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val view = LocalView.current
                val barColor = MaterialTheme.colorScheme.surface
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                }
                Surface(Modifier.fillMaxSize()) {
                    val ctx = LocalContext.current
                    val prefs = remember { ctx.getSharedPreferences("wagw", Context.MODE_PRIVATE) }
                    var agreed by remember { mutableStateOf(prefs.getBoolean("privacy_agreed", false)) }
                    if (!agreed) PrivacyGate { prefs.edit().putBoolean("privacy_agreed", true).apply(); agreed = true }
                    else GatewayApp()
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private fun fmt(ts: Long) = if (ts > 0) timeFmt.format(Date(ts)).lowercase(Locale.getDefault()) else ""

object ChatFlags {
    val hidden = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    val locked = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    var reveal by androidx.compose.runtime.mutableStateOf(false)
    var prefs: android.content.SharedPreferences? = null
    private fun save() { prefs?.edit()?.putStringSet("hidden", hidden.keys.toSet())?.putStringSet("locked", locked.keys.toSet())?.apply() }
    fun toggleHidden(jid: String) { if (hidden[jid] == true) hidden.remove(jid) else hidden[jid] = true; save() }
    fun toggleLocked(jid: String) { if (locked[jid] == true) locked.remove(jid) else locked[jid] = true; save() }
}


private fun chatTitle(msgs: List<GatewayClient.Msg>): String {
    val chat = msgs.firstOrNull()?.chat ?: return "Unknown"
    ContactStore.nameFor(chat)?.let { if (it.isNotBlank()) return it }
    msgs.firstOrNull { !it.fromMe && it.name.isNotBlank() }?.let { return it.name }
    return when {
        chat.endsWith("@g.us") -> "Group"
        else -> { val n = chat.substringBefore("@").filter { it.isDigit() }; if (n.isNotBlank()) "+" + n else "Unknown" }
    }
}

private fun decodeThumb(b64: String?): ImageBitmap? = b64?.let {
    runCatching { val by = Base64.decode(it, Base64.DEFAULT); BitmapFactory.decodeByteArray(by, 0, by.size)?.asImageBitmap() }.getOrNull()
}

private fun downloadMedia(scope: CoroutineScope, ctx: Context, m: GatewayClient.Msg, onLog: (String) -> Unit) {
    val name = m.mediaName ?: return
    scope.launch {
        val bytes = GatewayClient.mediaBytes(name)
        if (bytes == null) { onLog("turn on Save media (Settings) to download"); return@launch }
        val ok = MediaSaver.save(ctx, bytes, name, m.mediaType ?: "document")
        onLog(if (ok) "saved to $APP_NAME folder" else "save failed")
    }
}

private suspend fun loadPreview(ctx: Context, m: GatewayClient.Msg): ImageBitmap? {
    val name = m.mediaName ?: return null
    return withContext(Dispatchers.IO) {
        try {
            if (m.mediaType == "video") {
                val r = MediaMetadataRetriever()
                r.setDataSource(GatewayClient.mediaUrl(name), HashMap<String, String>())
                val bmp = r.getFrameAtTime(1_000_000L)
                r.release()
                bmp?.asImageBitmap()
            } else {
                val bytes = GatewayClient.mediaBytes(name) ?: return@withContext null
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }
        } catch (e: Exception) { null }
    }
}

private fun shareMedia(scope: CoroutineScope, ctx: Context, m: GatewayClient.Msg, onLog: (String) -> Unit) {
    val name = m.mediaName ?: return
    scope.launch {
        val bytes = GatewayClient.mediaBytes(name)
        if (bytes == null) { onLog("turn on Save media to share"); return@launch }
        try {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                val f = File(dir, name); f.writeBytes(bytes)
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            }
            val mime = when (m.mediaType) { "image" -> "image/*"; "video" -> "video/*"; "audio" -> "audio/*"; else -> "*/*" }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { onLog("share failed: ${e.message}") }
    }
}

private fun decodeScaled(path: String, maxDim: Int): android.graphics.Bitmap? {
    return try {
        val o1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o1)
        var sample = 1
        val big = maxOf(o1.outWidth, o1.outHeight)
        while (big / sample > maxDim) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, o2)
    } catch (e: Exception) { null }
}

data class DeviceContact(val name: String, val number: String)

private fun normNumber(raw: String): String {
    var d = raw.filter { it.isDigit() }
    if (d.length == 11 && d.startsWith("0")) d = "91" + d.substring(1)
    else if (d.length == 10) d = "91" + d
    return d
}

private fun loadDeviceContacts(ctx: Context): List<DeviceContact> {
    val out = ArrayList<DeviceContact>()
    val seen = HashSet<String>()
    try {
        val cur = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cur?.use { c ->
            val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val name = if (ni >= 0) (c.getString(ni) ?: "") else ""
                val raw = if (pi >= 0) (c.getString(pi) ?: "") else ""
                val num = normNumber(raw)
                if (num.length < 10) continue
                if (seen.add(num)) out.add(DeviceContact(name.ifBlank { num }, num))
            }
        }
    } catch (_: Exception) {}
    return out
}

private fun queryName(ctx: Context, uri: Uri): String? =
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

@Composable
fun PrivacyGate(onAgree: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Privacy Policy", style = MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(PRIVACY_TEXT, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(typed, { typed = it }, label = { Text("Type 'ok' to continue") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = onAgree, enabled = typed.trim().equals("ok", true), modifier = Modifier.fillMaxWidth()) { Text("I Agree") }
    }
}

class OptMsg(val chat: String, val text: String, val ts: Long, val quotedText: String? = null)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GatewayApp() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var status by remember { mutableStateOf(GatewayClient.Status()) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastQrHash by remember { mutableStateOf(0) }
    var settings by remember { mutableStateOf(GatewayClient.Settings()) }
    var messages by remember { mutableStateOf(listOf<GatewayClient.Msg>()) }
    var settingsLoaded by remember { mutableStateOf(false) }

    var screen by remember { mutableStateOf("chats") }
    var openChat by remember { mutableStateOf<String?>(null) }
    var viewImg by remember { mutableStateOf<ImageBitmap?>(null) }
    var viewVideoUrl by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val optimistic = remember { mutableStateListOf<OptMsg>() }
    val dpCache = remember { mutableStateMapOf<String, ImageBitmap?>() }
    val previewCache = remember { mutableStateMapOf<String, ImageBitmap?>() }

    fun notify(s: String) { toast = s }
    fun openMedia(m: GatewayClient.Msg) {
        if (m.mediaType == "video") {
            if (m.mediaName != null) viewVideoUrl = GatewayClient.mediaUrl(m.mediaName!!) else notify("turn on Save media to play video")
            return
        }
        scope.launch {
            val full = m.mediaName?.let { GatewayClient.mediaBytes(it) }
            val bmp = if (full != null) BitmapFactory.decodeByteArray(full, 0, full.size)?.asImageBitmap() else decodeThumb(m.thumb)
            if (bmp != null) viewImg = bmp else notify("turn on Save media to view full image")
        }
    }

    // file picker for sending media
    var pendingMedia by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var chatsPage by remember { mutableStateOf(0) }
    var storyView by remember { mutableStateOf<String?>(null) }
    var myJid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status.registered) { if (status.registered) { val j = GatewayClient.getMe(); if (j.isNotBlank()) myJid = j } }
    var pendingLockOpen by remember { mutableStateOf<String?>(null) }
    val unlockLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) pendingLockOpen?.let { openChat = it }
        pendingLockOpen = null
    }
    val revealUnlock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) ChatFlags.reveal = true
    }
    var pendingStatus by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    val statusPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            val mime = ctx.contentResolver.getType(uri) ?: ""
            pendingStatus = uri to (if (mime.startsWith("video")) "video" else "image")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && openChat != null) {
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val type = when {
                mime.startsWith("image") -> "image"; mime.startsWith("video") -> "video"
                mime.startsWith("audio") -> "audio"; else -> "document"
            }
            pendingMedia = uri to type
        }
    }

    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    fun loadWallpaper() {
        val f = File(ctx.filesDir, "wallpaper.jpg")
        wallpaper = if (f.exists()) runCatching { decodeScaled(f.absolutePath, 1440)?.asImageBitmap() }.getOrNull() else null
    }
    LaunchedEffect(Unit) { loadWallpaper() }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val jid = openChat; val u = cameraUri
        if (ok && u != null && jid != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }
                if (bytes != null) { notify("sending photo…"); GatewayClient.sendMedia(jid, Base64.encodeToString(bytes, Base64.NO_WRAP), "image", "camera.jpg", "") }
            } catch (e: Exception) { notify("send failed: ${e.message}") }
        }
    }
    val cameraPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraUri?.let { cameraLauncher.launch(it) } else notify("camera permission needed")
    }
    fun openCamera() {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "cam_" + System.currentTimeMillis() + ".jpg")
        val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        cameraUri = u
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraLauncher.launch(u)
        else cameraPerm.launch(Manifest.permission.CAMERA)
    }

    var deviceContacts by remember { mutableStateOf<List<DeviceContact>>(emptyList()) }
    var contactsLoading by remember { mutableStateOf(false) }
    fun doLoadContacts() {
        contactsLoading = true
        scope.launch {
            val all = withContext(Dispatchers.IO) { loadDeviceContacts(ctx) }
            all.forEach { ContactStore.put(it.number, it.name) }
            val reg = GatewayClient.onWhatsApp(all.map { it.number })   // number -> lid?
            all.forEach { c -> reg[c.number]?.let { lid -> if (lid.isNotBlank()) ContactStore.put(lid, c.name) } }
            deviceContacts = if (reg.isEmpty()) all else all.filter { reg.containsKey(it.number) }
            contactsLoading = false
        }
    }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doLoadContacts() else notify("contacts permission needed")
    }
    fun ensureContacts() {
        if (deviceContacts.isNotEmpty() || contactsLoading) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) doLoadContacts()
        else contactsPerm.launch(Manifest.permission.READ_CONTACTS)
    }

    var statuses by remember { mutableStateOf<List<GatewayClient.StatusItem>>(emptyList()) }

    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    File(ctx.filesDir, "wallpaper.jpg").outputStream().use { input.copyTo(it) }
                }
            }
            loadWallpaper(); notify("wallpaper set")
        }
    }
    val profilePicPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                if (bytes != null) { GatewayClient.setProfilePicture(Base64.encodeToString(bytes, Base64.NO_WRAP)); notify("profile photo updated") }
            } catch (e: Exception) { notify("failed: ${e.message}") }
        }
    }

    var chatWp by remember { mutableStateOf<ImageBitmap?>(null) }
    fun loadChatWp(jid: String?) {
        val perChat = jid?.let { File(ctx.filesDir, "wp_${it.hashCode()}.jpg") }
        val f = if (perChat != null && perChat.exists()) perChat else File(ctx.filesDir, "wallpaper.jpg")
        chatWp = if (f.exists()) runCatching { decodeScaled(f.absolutePath, 1440)?.asImageBitmap() }.getOrNull() else null
    }
    val chatWallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val jid = openChat
        if (uri != null && jid != null) scope.launch {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    File(ctx.filesDir, "wp_${jid.hashCode()}.jpg").outputStream().use { input.copyTo(it) }
                }
            }
            loadChatWp(jid); notify("chat wallpaper set")
        }
    }
    var lastNotifTs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showNewChat by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var chatPresence by remember { mutableStateOf<GatewayClient.Presence?>(null) }
    val blkPrefs = remember { ctx.getSharedPreferences("wagw", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        ChatFlags.prefs = blkPrefs
        ChatFlags.reveal = false
        blkPrefs.getStringSet("hidden", emptySet())!!.forEach { ChatFlags.hidden[it] = true }
        blkPrefs.getStringSet("locked", emptySet())!!.forEach { ChatFlags.locked[it] = true }
    }
    var blockedJids by remember { mutableStateOf(blkPrefs.getStringSet("blocked", emptySet())!!.toSet()) }
    LaunchedEffect(openChat) { loadChatWp(openChat); NodeService.currentOpenChat = openChat; openChat?.let { NotificationHelper.cancel(ctx, it) } }
    LaunchedEffect(openChat) {
        chatPresence = null
        val c = openChat
        if (c != null && c.endsWith("@s.whatsapp.net")) {
            while (openChat == c) { chatPresence = GatewayClient.getPresence(c); delay(5000) }
        }
    }
    LaunchedEffect(AppNav.pendingOpenChat.value) {
        AppNav.pendingOpenChat.value?.let { openChat = it; screen = "chats"; AppNav.pendingOpenChat.value = null }
    }

    LaunchedEffect(Unit) { NodeService.start(ctx) }
    LaunchedEffect(Unit) {
        ContactStore.init(ctx)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val all = withContext(Dispatchers.IO) { loadDeviceContacts(ctx) }
            all.forEach { ContactStore.put(it.number, it.name) }
            doLoadContacts()
        } else contactsPerm.launch(Manifest.permission.READ_CONTACTS)
    }
    LaunchedEffect(status.registered) {
        if (status.registered) {
            val wa = runCatching { GatewayClient.getContacts() }.getOrDefault(emptyList())
            wa.forEach { c -> if (c.name.isNotBlank()) { ContactStore.put(c.jid, c.name); if (c.number.isNotBlank()) ContactStore.put(c.number, c.name) } }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            status = GatewayClient.status()
            if (!status.registered) {
                settingsLoaded = false; openChat = null
                val b = GatewayClient.qrBytes()
                if (b != null) {
                    val h = b.contentHashCode()
                    if (h != lastQrHash) BitmapFactory.decodeByteArray(b, 0, b.size)?.let { qr = it.asImageBitmap(); lastQrHash = h }
                }
            } else {
                qr = null; lastQrHash = 0
                if (!settingsLoaded) { settings = GatewayClient.getSettings(); settingsLoaded = true }
                messages = GatewayClient.getMessages()
                optimistic.removeAll { opt -> messages.any { it.fromMe && it.chat == opt.chat && it.text == opt.text && it.ts >= opt.ts - 8000 } }
            }
            delay(3000)
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2500); toast = null } }

    BackHandler(enabled = viewImg != null || viewVideoUrl != null || openChat != null || screen == "settings" || screen == "newchat" || screen == "profile") {
        when {
            viewImg != null -> viewImg = null
            viewVideoUrl != null -> viewVideoUrl = null
            openChat != null -> openChat = null
            else -> screen = "chats"
        }
    }

    storyView?.let { sender ->
        StatusViewer(statuses, sender, dpCache,
            onDownload = { st -> downloadMedia(scope, ctx, GatewayClient.Msg(st.sender, "", false, st.text, st.ts, st.mediaName, st.mediaType)) { m -> notify(m) } },
            onReply = { jid, text -> scope.launch { runCatching { GatewayClient.sendToJid(jid, text) }.onSuccess { notify("reply sent") }.onFailure { notify("reply failed: " + it.message) } } },
            onClose = { storyView = null })
    }

    pendingStatus?.let { ps ->
        StatusEditor(ps.first, ps.second, deviceContacts, onUpload = { caption, audience, jids ->
            val u = ps.first; val t = ps.second
            pendingStatus = null
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }
                    if (bytes != null) { notify("uploading status…"); val ok = GatewayClient.postStatus(t, Base64.encodeToString(bytes, Base64.NO_WRAP), caption, audience, jids); notify(if (ok) "status uploaded" else "upload failed") }
                } catch (e: Exception) { notify("upload failed: " + e.message) }
            }
        }, onCancel = { pendingStatus = null })
    }

    pendingMedia?.let { pm ->
        val uri = pm.first; val mtype = pm.second
        var cap by remember(uri) { mutableStateOf("") }
        var original by remember(uri) { mutableStateOf(false) }
        Dialog(onDismissRequest = { pendingMedia = null }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp).widthIn(max = 340.dp)) {
                    Text("Send " + mtype, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (mtype == "image") {
                        val bmp = remember(uri) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull() }
                        bmp?.let { Image(it, null, Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit) }
                    } else Text(queryName(ctx, uri) ?: "file", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(cap, { cap = it }, placeholder = { Text("Add a caption…") }, modifier = Modifier.fillMaxWidth())
                    if (mtype == "image" || mtype == "video") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(original, { original = it })
                            Text("Original quality (send as file)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { pendingMedia = null }) { Text("Cancel") }
                        Button(onClick = {
                            val jid = openChat; val u = uri
                            val sendType = if (original) "document" else mtype; val caption = cap.trim()
                            pendingMedia = null
                            if (jid != null) scope.launch {
                                try {
                                    val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }
                                    if (bytes == null) { notify("can't read file"); return@launch }
                                    notify("sending…")
                                    GatewayClient.sendMedia(jid, Base64.encodeToString(bytes, Base64.NO_WRAP), sendType, queryName(ctx, u) ?: "file", caption)
                                } catch (e: Exception) { notify("send failed: ${e.message}") }
                            }
                        }) { Text("Send") }
                    }
                }
            }
        }
    }

    // image viewer (full screen)
    viewImg?.let { img ->
        Dialog(onDismissRequest = { viewImg = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewImg = null }, contentAlignment = Alignment.Center) {
                Image(img, "image", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
    // video viewer (full screen, in-app player)
    viewVideoUrl?.let { url ->
        Dialog(onDismissRequest = { viewVideoUrl = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(Uri.parse(url))
                        val mc = MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                        setOnPreparedListener { it.start() }
                    }
                }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { viewVideoUrl = null }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }

    val chatName = openChat?.let { c -> chatTitle(messages.filter { it.chat == c }) }
    val title = when {
        !status.registered -> "Link device"
        openChat != null -> chatName ?: "Chat"
        screen == "settings" -> "Settings"
        screen == "newchat" -> "New chat"
        screen == "profile" -> "Profile"
        else -> APP_NAME
    }

    if (showNewChat) {
        var num by remember { mutableStateOf("91") }
        var cts by remember { mutableStateOf(listOf<GatewayClient.Contact>()) }
        var csearch by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { cts = GatewayClient.getContacts() }
        Dialog(onDismissRequest = { showNewChat = false }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New chat", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(num, { num = it.filter(Char::isDigit) }, label = { Text("Number with country code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { if (num.length in 8..15) { openChat = num + "@s.whatsapp.net"; showNewChat = false } },
                        enabled = num.length in 8..15, modifier = Modifier.fillMaxWidth()) { Text("Start with number") }
                    HorizontalDivider()
                    OutlinedTextField(csearch, { csearch = it }, label = { Text("Search contacts") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val filtered = cts.filter { it.name.contains(csearch, true) || it.number.contains(csearch) }
                    if (cts.isEmpty()) Text("No contacts synced yet (they sync from WhatsApp over time).", style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        filtered.take(80).forEach { c ->
                            Row(Modifier.fillMaxWidth().clickable { openChat = c.jid; showNewChat = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(c.name.ifBlank { "+" + c.number }, style = MaterialTheme.typography.bodyLarge)
                                    if (c.name.isNotBlank()) Text("+" + c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { showNewChat = false }, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            if (status.registered && openChat == null && screen == "chats") {
                if (chatsPage == 1) FloatingActionButton(onClick = { ensureContacts(); statusPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) { Icon(Icons.Filled.PhotoCamera, "add status") }
                else FloatingActionButton(onClick = { screen = "newchat"; ensureContacts() }) { Icon(Icons.Filled.Add, "new chat") }
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (searchMode && openChat == null && screen == "chats") {
                        OutlinedTextField(searchQuery, { searchQuery = it }, placeholder = { Text("Search chats") },
                            leadingIcon = { Icon(Icons.Filled.Search, null, tint = IOS_BLUE) }, singleLine = true, shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth())
                    } else if (openChat != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(openChat!!, chatName ?: "?", dpCache, 38.dp, CircleShape)
                            Spacer(Modifier.width(9.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(title, maxLines = 1, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(max = 190.dp).basicMarquee())
                                val sub = chatPresence?.let { pr -> if (pr.online) "online" else if (pr.lastSeen > 0) "last seen " + fmt(pr.lastSeen * 1000) else "" } ?: ""
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = {},
                        onLongClick = {
                            if (ChatFlags.reveal) ChatFlags.reveal = false
                            else {
                                val km = ctx.getSystemService(KeyguardManager::class.java)
                                if (km != null && km.isKeyguardSecure) revealUnlock.launch(km.createConfirmDeviceCredentialIntent("Show hidden chats", "Verify to reveal"))
                                else ChatFlags.reveal = true
                            }
                        }))
                },
                navigationIcon = {
                    if (openChat != null || screen == "settings" || screen == "newchat" || screen == "profile")
                        IconButton(onClick = { if (openChat != null) openChat = null else screen = "chats" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "back")
                        }
                    else if (status.registered && myJid != null)
                        Box(Modifier.padding(start = 10.dp).clip(CircleShape).clickable { screen = "profile" }) { Avatar(myJid!!, "Me", dpCache, 34.dp) }
                },
                actions = {
                    if (status.registered && openChat == null && screen == "chats") {
                        if (searchMode) {
                            IconButton(onClick = { searchMode = false; searchQuery = "" }) { Icon(Icons.Filled.Close, "close") }
                        } else {
                            IconButton(onClick = { searchMode = true }) { Icon(Icons.Filled.Search, "search") }
                            IconButton(onClick = { screen = "settings" }) { Icon(Icons.Filled.Settings, "settings") }
                        }
                    }
                    if (openChat != null) {
                        var menu by remember { mutableStateOf(false) }
                        val ocb = openChat
                        val isBlocked = ocb != null && ocb in blockedJids
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Change wallpaper") }, onClick = { menu = false; chatWallpaperPicker.launch("image/*") })
                            if (!isBlocked) DropdownMenuItem(text = { Text("Block contact") }, onClick = {
                                menu = false
                                val c = openChat
                                if (c != null) scope.launch { runCatching { GatewayClient.blockChat(c, true) }.onSuccess { notify("blocked"); blockedJids = blockedJids + c; blkPrefs.edit().putStringSet("blocked", blockedJids).apply() }.onFailure { notify("failed: ${it.message}") } }
                            })
                            if (isBlocked) DropdownMenuItem(text = { Text("Unblock contact") }, onClick = {
                                menu = false
                                val c = openChat
                                if (c != null) scope.launch { runCatching { GatewayClient.blockChat(c, false) }.onSuccess { notify("unblocked"); blockedJids = blockedJids - c; blkPrefs.edit().putStringSet("blocked", blockedJids).apply() }.onFailure { notify("failed: ${it.message}") } }
                            })
                        }
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
            when {
                !status.registered -> LinkScreen(qr, status.pairingCode,
                    onPair = { n -> scope.launch { try { notify("code: " + GatewayClient.pair(n)) } catch (e: Exception) { notify("pair error: ${e.message}") } } },
                    onReset = { scope.launch { GatewayClient.logout(); notify("reset") } })
                openChat != null -> ChatDetail(
                    chat = openChat!!,
                    messages = messages.filter { it.chat == openChat },
                    optimistic = optimistic.filter { it.chat == openChat },
                    canSend = status.connection == "open",
                    onSend = { text ->
                        optimistic.add(OptMsg(openChat!!, text, System.currentTimeMillis()))
                        scope.launch { try { GatewayClient.sendToJid(openChat!!, text) } catch (e: Exception) { notify("send error: ${e.message}") } }
                    },
                    onMedia = { openMedia(it) },
                    onShare = { shareMedia(scope, ctx, it) { s -> notify(s) } },
                    onDownload = { downloadMedia(scope, ctx, it) { s -> notify(s) } },
                    onReact = { m, e -> scope.launch { m.id?.let { GatewayClient.react(m.chat, it, e, m.fromMe) } } },
                    onDeleteMsg = { m, everyone -> scope.launch { GatewayClient.deleteMessage(m.chat, m.id, m.text, m.ts, everyone, m.fromMe); messages = GatewayClient.getMessages() } },
                    onAttach = { picker.launch("*/*") },
                    onCamera = { openCamera() },
                    onReplySend = { text, qid, qtext ->
                        optimistic.add(OptMsg(openChat!!, text, System.currentTimeMillis(), qtext))
                        scope.launch { try { GatewayClient.sendReply(openChat!!, text, qid) } catch (e: Exception) { notify("send error: ${e.message}") } }
                    },
                    previewCache = previewCache,
                    dpCache = dpCache,
                    wallpaper = chatWp
                )
                screen == "settings" -> SettingsScreen(status, settings,
                    onToggle = { patch -> scope.launch { settings = GatewayClient.patchSettings(patch) } },
                    onRules = { r -> scope.launch { settings = GatewayClient.setRules(r) } },
                    onLogout = { scope.launch { GatewayClient.logout(); notify("logged out") } }, ctx = ctx,
                    onPickWallpaper = { wallpaperPicker.launch("image/*") },
                    onRemoveWallpaper = { File(ctx.filesDir, "wallpaper.jpg").delete(); loadWallpaper(); notify("wallpaper removed") },
                    onPickPhoto = { profilePicPicker.launch("image/*") },
                    onSaveName = { n -> scope.launch { runCatching { GatewayClient.setProfileName(n) }.onSuccess { notify("name updated") }.onFailure { notify("name: ${it.message}") } } })
                screen == "newchat" -> NewChatScreen(deviceContacts, contactsLoading, dpCache,
                    onPickNumber = { num -> openChat = num + "@s.whatsapp.net"; screen = "chats" })
                screen == "profile" -> ProfileScreen(myJid, dpCache,
                    onPickPhoto = { profilePicPicker.launch("image/*") },
                    onSaveName = { n -> scope.launch { runCatching { GatewayClient.setProfileName(n) }.onSuccess { notify("name updated") }.onFailure { notify("name: ${it.message}") } } })
                else -> ChatsWithStatus(messages, statuses, dpCache, searchQuery,
                    onPageChange = { chatsPage = it },
                    onLoadStatuses = { scope.launch { statuses = GatewayClient.getStatuses() } },
                    onOpenStatus = { st -> storyView = st.sender },
                    onDelete = { jid -> scope.launch { GatewayClient.deleteChat(jid); messages = GatewayClient.getMessages() } },
                    onOpen = { jid ->
                        if (ChatFlags.locked[jid] == true) {
                            val km = ctx.getSystemService(KeyguardManager::class.java)
                            if (km != null && km.isKeyguardSecure) {
                                pendingLockOpen = jid
                                unlockLauncher.launch(km.createConfirmDeviceCredentialIntent("Unlock chat", "Verify to open this chat"))
                            } else openChat = jid
                        } else openChat = jid
                    })
            }
            toast?.let {
                Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)) {
                    Text(it, Modifier.padding(12.dp, 8.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
    }
}

@Composable
private fun Avatar(jid: String, name: String, cache: MutableMap<String, ImageBitmap?>, size: androidx.compose.ui.unit.Dp, shape: Shape = CircleShape) {
    LaunchedEffect(jid) {
        if (!cache.containsKey(jid)) {
            val b = GatewayClient.dpBytes(jid)
            cache[jid] = b?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    val dp = cache[jid]
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, shape), contentAlignment = Alignment.Center) {
        if (dp != null) Image(dp, "dp", Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatList(messages: List<GatewayClient.Msg>, dpCache: MutableMap<String, ImageBitmap?>, query: String, onDelete: (String) -> Unit, onOpen: (String) -> Unit) {
    val groups = messages.groupBy { it.chat }.entries
        .filter { ChatFlags.reveal || ChatFlags.hidden[it.key] != true }
        .filter { query.isBlank() || chatTitle(it.value).contains(query, true) || it.key.contains(query) }
        .sortedByDescending { it.value.maxOf { m -> m.ts } }
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No chats yet.\nIncoming messages will appear here.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(groups, key = { _, e -> e.key }) { _, entry ->
            val msgs = entry.value
            val last = msgs.maxByOrNull { it.ts }!!
            val name = chatTitle(msgs)
          Box {
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onOpen(entry.key) }, onLongClick = { menu = true }).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(entry.key, name, dpCache, 50.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmt(last.ts), style = MaterialTheme.typography.labelSmall)
                    }
                    val preview = when { last.deleted -> "deleted"; last.text.isNotBlank() -> last.text; last.mediaType != null -> "[${last.mediaType}]"; else -> "" }
                    Text((if (last.fromMe) "You: " else "") + preview, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (ChatFlags.hidden[entry.key] == true) "Unhide chat" else "Hide chat") }, onClick = { menu = false; ChatFlags.toggleHidden(entry.key) })
                DropdownMenuItem(text = { Text(if (ChatFlags.locked[entry.key] == true) "Unlock chat" else "Lock chat") }, onClick = { menu = false; ChatFlags.toggleLocked(entry.key) })
                DropdownMenuItem(text = { Text("Delete chat") }, onClick = { menu = false; onDelete(entry.key) })
            }
          }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatDetail(
    chat: String,
    messages: List<GatewayClient.Msg>,
    optimistic: List<OptMsg>,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onMedia: (GatewayClient.Msg) -> Unit,
    onShare: (GatewayClient.Msg) -> Unit,
    onDownload: (GatewayClient.Msg) -> Unit,
    onReact: (GatewayClient.Msg, String) -> Unit,
    onDeleteMsg: (GatewayClient.Msg, Boolean) -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    onReplySend: (String, String, String) -> Unit,
    previewCache: MutableMap<String, ImageBitmap?>,
    dpCache: MutableMap<String, ImageBitmap?>,
    wallpaper: ImageBitmap?,
) {
    var input by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<GatewayClient.Msg?>(null) }
    var reactMsg by remember { mutableStateOf<GatewayClient.Msg?>(null) }
    reactMsg?.let { rm ->
        Dialog(onDismissRequest = { reactMsg = null }) {
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 4.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { e ->
                            Text(e, style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.clickable { onReact(rm, e); reactMsg = null }.padding(6.dp))
                        }
                    }
                    HorizontalDivider()
                    if (rm.fromMe) TextButton(onClick = { onDeleteMsg(rm, true); reactMsg = null }) { Text("Delete for everyone") }
                    TextButton(onClick = { onDeleteMsg(rm, false); reactMsg = null }) { Text("Delete for me") }
                }
            }
        }
    }
    // newest first (reverseLayout shows newest at bottom, opens there, no jump)
    val rows = remember(messages, optimistic) {
        (messages + optimistic.map { GatewayClient.Msg(chat, "", true, it.text, it.ts, quotedText = it.quotedText) }).sortedByDescending { it.ts }
    }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        wallpaper?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item { Spacer(Modifier.height(6.dp)) }
            itemsIndexed(rows, key = { i, m -> "${m.ts}-$i" }) { _, m -> Box(Modifier.fillMaxWidth().animateItem()) { MessageBubble(m, previewCache, dpCache, onMedia, onShare, onDownload, onReply = { replyTo = it }) { reactMsg = it } } }
        }
        replyTo?.let { rt ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(34.dp).background(IOS_BLUE, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (rt.fromMe) "You" else rt.name.ifBlank { "Reply" }, style = MaterialTheme.typography.labelMedium, color = IOS_BLUE, maxLines = 1)
                    Text(rt.text.ifBlank { "\uD83D\uDCCE media" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { replyTo = null }) { Icon(Icons.Filled.Close, "cancel reply") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = onAttach) { Icon(Icons.Filled.AttachFile, "attach") }
            IconButton(onClick = onCamera) { Icon(Icons.Filled.PhotoCamera, "camera") }
            OutlinedTextField(input, { input = it }, placeholder = { Text("Message") },
                shape = RoundedCornerShape(24.dp), maxLines = 4, modifier = Modifier.weight(1f))
            FilledIconButton(onClick = {
                if (input.isNotBlank()) {
                    val rt = replyTo
                    if (rt?.id != null) onReplySend(input.trim(), rt.id!!, rt.text) else onSend(input.trim())
                    input = ""; replyTo = null
                }
            }, enabled = input.isNotBlank() && canSend) { Icon(Icons.AutoMirrored.Filled.Send, "send") }
        }
        }
    }
}

@Composable
private fun AudioPlayer(url: String, tint: Color) {
    var playing by remember(url) { mutableStateOf(false) }
    var progress by remember(url) { mutableStateOf(0f) }
    var ready by remember(url) { mutableStateOf(false) }
    val player = remember(url) { MediaPlayer() }
    DisposableEffect(url) {
        runCatching {
            player.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            player.setDataSource(url)
            player.setOnPreparedListener { ready = true }
            player.setOnCompletionListener { playing = false; progress = 0f }
            player.prepareAsync()
        }
        onDispose { runCatching { if (player.isPlaying) player.stop() }; runCatching { player.release() } }
    }
    LaunchedEffect(playing) {
        while (playing) {
            runCatching { if (player.duration > 0) progress = player.currentPosition.toFloat() / player.duration }
            delay(200)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(min = 200.dp)) {
        Box(Modifier.size(38.dp).background(tint.copy(alpha = 0.22f), CircleShape).clickable(enabled = ready) {
            if (playing) { runCatching { player.pause() }; playing = false }
            else { runCatching { player.start(); playing = true } }
        }, contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "play", tint = tint, modifier = Modifier.size(24.dp))
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(4.dp), color = tint, trackColor = tint.copy(alpha = 0.3f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: GatewayClient.Msg, previewCache: MutableMap<String, ImageBitmap?>, dpCache: MutableMap<String, ImageBitmap?>, onMedia: (GatewayClient.Msg) -> Unit, onShare: (GatewayClient.Msg) -> Unit, onDownload: (GatewayClient.Msg) -> Unit, onReply: (GatewayClient.Msg) -> Unit, onLongClick: (GatewayClient.Msg) -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val recvColor = if (dark) Color(0xFF2C2C2E) else Color(0xFFE9E9EB)
    val bubbleColor = if (m.fromMe) IOS_BLUE else recvColor
    val textColor = if (m.fromMe) Color.White else (if (dark) Color.White else Color.Black)
    val shape = if (m.fromMe) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)
    val isVideo = m.mediaType == "video"
    val hasMedia = m.mediaType != null

    val name = m.mediaName
    LaunchedEffect(name) { if (name != null && !previewCache.containsKey(name)) previewCache[name] = loadPreview(ctx, m) }
    val preview = (name?.let { previewCache[it] }) ?: remember(m.thumb) { decodeThumb(m.thumb) }

    var swipeX by remember(m.id) { mutableStateOf(0f) }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
        .pointerInput(m.id) {
            detectHorizontalDragGestures(
                onDragEnd = { if (swipeX > 55f) onReply(m); swipeX = 0f },
                onDragCancel = { swipeX = 0f },
                onHorizontalDrag = { _, amt -> swipeX = (swipeX + amt).coerceIn(0f, 130f) }
            )
        }
        .offset { IntOffset(swipeX.roundToInt(), 0) },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
        if (!m.fromMe && m.chat.endsWith("@g.us") && !m.sender.isNullOrBlank()) {
            Avatar(m.sender!!, m.name.ifBlank { "?" }, dpCache, 30.dp)
            Spacer(Modifier.width(6.dp))
        }
        Surface(color = bubbleColor, shape = shape,
            modifier = Modifier.widthIn(max = 290.dp).combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = { onLongClick(m) })) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!m.quotedText.isNullOrBlank()) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Box(Modifier.width(3.dp).height(30.dp).background(if (m.fromMe) Color.White.copy(alpha = 0.7f) else IOS_BLUE, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(6.dp))
                        Text(m.quotedText!!, style = MaterialTheme.typography.bodySmall,
                            color = if (m.fromMe) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!m.fromMe && m.chat.endsWith("@g.us") && !m.name.isNullOrBlank()) {
                    Text(m.name, style = MaterialTheme.typography.labelMedium, color = IOS_BLUE,
                        modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (hasMedia && preview != null) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onMedia(m) }, contentAlignment = Alignment.Center) {
                        Image(preview, "media", Modifier.widthIn(min = 220.dp, max = 280.dp).heightIn(max = 340.dp), contentScale = ContentScale.Crop)
                        if (isVideo) Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, "play", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (m.mediaType == "audio") {
                        if (m.mediaName != null) AudioPlayer(GatewayClient.mediaUrl(m.mediaName!!), textColor)
                        else Text("Voice message (enable Save media)", color = textColor, style = MaterialTheme.typography.bodySmall)
                    }
                    if (m.text.isNotBlank()) LinkText(m.text, textColor)
                    else if (hasMedia && preview == null && m.mediaType != "audio") Text("[${m.mediaType}]", color = textColor, style = MaterialTheme.typography.bodySmall)
                    if (!m.reaction.isNullOrBlank()) {
                        Surface(shape = CircleShape, color = if (m.fromMe) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.15f)) {
                            Text(m.reaction, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(m.ts), style = MaterialTheme.typography.labelSmall,
                            color = if (m.fromMe) Color.White.copy(alpha = 0.7f) else textColor.copy(alpha = 0.6f))
                        if (m.deleted) Text("deleted", color = Color(0xFFFF4D4D), style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
                        if (m.mediaName != null) {
                            IconButton(onClick = { onDownload(m) }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Filled.Download, "download", modifier = Modifier.size(15.dp), tint = if (m.fromMe) Color.White else textColor)
                            }
                            IconButton(onClick = { onShare(m) }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Filled.Share, "share", modifier = Modifier.size(15.dp), tint = if (m.fromMe) Color.White else textColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(status: GatewayClient.Status, settings: GatewayClient.Settings,
    onToggle: (JSONObject) -> Unit, onRules: (List<GatewayClient.Rule>) -> Unit, onLogout: () -> Unit, ctx: Context,
    onPickWallpaper: () -> Unit, onRemoveWallpaper: () -> Unit, onPickPhoto: () -> Unit,
    onSaveName: (String) -> Unit) {
    var section by remember { mutableStateOf("general") }
    fun toggle(name: String) { section = if (section == name) "" else name }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Engine: " + if (status.reachable) "up" else status.connection)
                Text("Connection: " + status.connection)
                Text("Linked: " + if (status.registered) "yes" else "no")
            }
        }

        SectionHeader("General", section == "general") { toggle("general") }
        AnimatedVisibility(section == "general") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow("Always online", settings.alwaysOnline) { onToggle(JSONObject().put("alwaysOnline", it)) }
                ToggleRow("Auto-read messages", settings.autoRead) { onToggle(JSONObject().put("autoRead", it)) }
                ToggleRow("Save media (photos/videos)", settings.saveMedia) { onToggle(JSONObject().put("saveMedia", it)) }
                ToggleRow("Hide status view (don't show you saw)", settings.hideStatusRead) { onToggle(JSONObject().put("hideStatusRead", it)) }
                Text("On = incoming media downloaded (needed for view/play/share + deleted media). Uses storage.", style = MaterialTheme.typography.bodySmall)
                ToggleRow("Freeze last seen (stay offline)", settings.stayOffline) { onToggle(JSONObject().put("stayOffline", it)) }
                Text("On = never broadcasts online. (Overrides Always online.)", style = MaterialTheme.typography.bodySmall)
            }
        }


        SectionHeader("Chat wallpaper", section == "wallpaper") { toggle("wallpaper") }
        AnimatedVisibility(section == "wallpaper") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickWallpaper, modifier = Modifier.fillMaxWidth()) { Text("Set wallpaper from gallery") }
                OutlinedButton(onClick = onRemoveWallpaper, modifier = Modifier.fillMaxWidth()) { Text("Remove wallpaper") }
                Text("Wallpaper shows behind all chats.", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionHeader("Auto-reply", section == "autoreply") { toggle("autoreply") }
        AnimatedVisibility(section == "autoreply") {
            Column { AutoReplySection(settings, onToggle, onRules) }
        }

        SectionHeader("About", section == "about") { toggle("about") }
        AnimatedVisibility(section == "about") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$APP_NAME — Created by imayx", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/ayxhere"))) } },
                    modifier = Modifier.fillMaxWidth()) { Text("Contact on Telegram (t.me/ayxhere)") }
                Text("Runs locally, no data collected. AI reply (if on) sends message text to your chosen API only.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Unlink / reset") }
    }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
    }
}

@Composable
private fun AutoReplySection(settings: GatewayClient.Settings, onToggle: (JSONObject) -> Unit, onRules: (List<GatewayClient.Rule>) -> Unit) {
    var match by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("contains") }
    Text("Auto-reply", style = MaterialTheme.typography.titleMedium)
    ToggleRow("Auto-reply enabled", settings.autoReplyEnabled) { onToggle(JSONObject().put("autoReplyEnabled", it)) }
    if (settings.rules.isEmpty()) Text("No rules yet.", style = MaterialTheme.typography.bodySmall)
    settings.rules.forEachIndexed { i, r ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("[${r.mode}] " + r.match, style = MaterialTheme.typography.labelMedium)
                    Text("→ " + r.reply, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onRules(settings.rules.toMutableList().also { it.removeAt(i) }) }) { Text("Delete") }
            }
        }
    }
    OutlinedTextField(match, { match = it }, label = { Text("If message (keyword)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(reply, { reply = it }, label = { Text("Reply with") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("contains", "exact", "starts").forEach { md -> FilterChip(selected = mode == md, onClick = { mode = md }, label = { Text(md) }) }
    }
    Button(onClick = { if (match.isNotBlank() && reply.isNotBlank()) { onRules(settings.rules + GatewayClient.Rule(match.trim(), reply.trim(), mode)); match = ""; reply = "" } },
        enabled = match.isNotBlank() && reply.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add rule") }
    HorizontalDivider()
    Text("AI auto-reply", style = MaterialTheme.typography.titleMedium)
    Text("Replies with AI when no keyword rule matches. Free key: console.groq.com.", style = MaterialTheme.typography.bodySmall)
    ToggleRow("AI reply enabled", settings.aiReplyEnabled) { onToggle(JSONObject().put("aiReplyEnabled", it)) }
    var url by remember { mutableStateOf(settings.aiApiUrl) }
    var key by remember { mutableStateOf(settings.aiApiKey) }
    var model by remember { mutableStateOf(settings.aiModel) }
    var sys by remember { mutableStateOf(settings.aiSystemPrompt) }
    OutlinedTextField(url, { url = it }, label = { Text("API URL") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(key, { key = it }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(sys, { sys = it }, label = { Text("System prompt (optional)") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = { onToggle(JSONObject().put("aiApiUrl", url.trim()).put("aiApiKey", key.trim()).put("aiModel", model.trim()).put("aiSystemPrompt", sys)) },
        modifier = Modifier.fillMaxWidth()) { Text("Save AI settings") }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LinkScreen(qr: ImageBitmap?, pairingCode: String?, onPair: (String) -> Unit, onReset: () -> Unit) {
    var number by remember { mutableStateOf("91") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Option A — Scan QR", style = MaterialTheme.typography.titleMedium)
        Text("On another phone: WhatsApp → Linked devices → Link a device → scan this.", style = MaterialTheme.typography.bodySmall)
        ElevatedCard {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                if (qr != null) Image(qr, "QR", Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Fit)
                else Text("Generating QR…", Modifier.padding(32.dp))
            }
        }
        HorizontalDivider()
        Text("Option B — Pairing code (same phone)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(number, { number = it.filter(Char::isDigit) }, label = { Text("Number with country code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        Button(onClick = { onPair(number) }, enabled = number.length in 8..15, modifier = Modifier.fillMaxWidth()) { Text("Get pairing code") }
        pairingCode?.let {
            ElevatedCard { Column(Modifier.padding(16.dp)) { Text("Pairing code", style = MaterialTheme.typography.labelMedium); Text(it, style = MaterialTheme.typography.headlineMedium) } }
        }
        HorizontalDivider()
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Reset session (fresh QR)") }
    }
}


@Composable
private fun NewChatScreen(contacts: List<DeviceContact>, loading: Boolean, dpCache: MutableMap<String, ImageBitmap?>, onPickNumber: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(contacts, q) {
        if (q.isBlank()) contacts else contacts.filter { it.name.contains(q, true) || it.number.contains(q) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(q, { q = it }, placeholder = { Text("Search name or number") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = IOS_BLUE) },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text("Finding your WhatsApp contacts…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            contacts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No WhatsApp contacts found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Text("${filtered.size} contacts on WhatsApp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(filtered) { _, c ->
                        Row(Modifier.fillMaxWidth().clickable { onPickNumber(c.number) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(c.number + "@s.whatsapp.net", c.name, dpCache, 46.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("+" + c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatsWithStatus(messages: List<GatewayClient.Msg>, statuses: List<GatewayClient.StatusItem>, dpCache: MutableMap<String, ImageBitmap?>, query: String, onPageChange: (Int) -> Unit, onLoadStatuses: () -> Unit, onOpenStatus: (GatewayClient.StatusItem) -> Unit, onDelete: (String) -> Unit, onOpen: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = 0) { 2 }
    val cs = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) {
        onPageChange(pager.currentPage)
        while (pager.currentPage == 1) { onLoadStatuses(); delay(5000) }
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pager.currentPage) {
            Tab(selected = pager.currentPage == 0, onClick = { cs.launch { pager.animateScrollToPage(0) } }, text = { Text("Chats") })
            Tab(selected = pager.currentPage == 1, onClick = { cs.launch { pager.animateScrollToPage(1) } }, text = { Text("Status") })
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) ChatList(messages, dpCache, query, onDelete, onOpen)
            else StatusScreen(statuses, onOpenStatus, dpCache)
        }
    }
}

@Composable
private fun StatusScreen(statuses: List<GatewayClient.StatusItem>, onOpen: (GatewayClient.StatusItem) -> Unit, dpCache: MutableMap<String, ImageBitmap?>) {
    val mine = statuses.filter { it.mine }
    val others = statuses.filter { !it.mine }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("My Status", style = MaterialTheme.typography.labelMedium, color = IOS_BLUE, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 2.dp))
            if (mine.isEmpty()) {
                Text("Tap the camera button to add a status update", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 10.dp))
            } else {
                val st = mine.first()
                Row(Modifier.fillMaxWidth().clickable { onOpen(st) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(st.sender, "Me", dpCache, 50.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("My Status", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(mine.size.toString() + " update(s) · " + fmt(st.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider()
            if (others.isNotEmpty()) Text("Recent updates", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp))
        }
        itemsIndexed(others) { _, st ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(st) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(st.sender, st.name, dpCache, 50.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.name.ifBlank { "Status" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (st.mediaType != null) ("photo/video") else st.text.ifBlank { "status" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(fmt(st.ts), style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider()
        }
        if (others.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text("No recent updates", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}


@Composable
private fun LinkText(text: String, color: Color) {
    val annotated = remember(text) {
        buildAnnotatedString {
            val regex = Regex("(https?://\\S+|www\\.\\S+)")
            var last = 0
            for (mt in regex.findAll(text)) {
                if (mt.range.first > last) append(text.substring(last, mt.range.first))
                val raw = mt.value
                val url = if (raw.startsWith("http")) raw else "https://" + raw
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Color(0xFF4EA1FF), textDecoration = TextDecoration.Underline)))) {
                    append(raw)
                }
                last = mt.range.last + 1
            }
            if (last < text.length) append(text.substring(last))
        }
    }
    Text(annotated, color = color)
}


@Composable
private fun StatusEditor(uri: Uri, type: String, contacts: List<DeviceContact>, onUpload: (String, String, List<String>) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var caption by remember { mutableStateOf("") }
    var audience by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pickAudience by remember { mutableStateOf(false) }
    var song by remember { mutableStateOf<GatewayClient.Song?>(null) }
    var musicOpen by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    val audLabel = when (audience) { "except" -> "Except " + selected.size; "only" -> "Only " + selected.size; else -> "My contacts" }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "close", tint = Color.White) }
                    Text("New status", color = Color.White, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { pickAudience = true }) { Text(audLabel, color = IOS_BLUE) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
                    if (type == "image") {
                        val bmp = remember(uri) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull() }
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("Preview unavailable", color = Color.White)
                    } else Text("Video selected", color = Color.White)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val sg = song
                    if (sg == null) {
                        TextButton(onClick = { musicOpen = true }) { Text("♪  Add music", color = IOS_BLUE) }
                    } else {
                        TextButton(onClick = {
                            if (playing) { runCatching { player.pause() }; playing = false }
                            else runCatching {
                                player.reset(); player.setDataSource(sg.url)
                                player.setOnPreparedListener { it.start(); playing = true }
                                player.setOnCompletionListener { playing = false }
                                player.setOnErrorListener { _, _, _ -> playing = false; true }
                                player.prepareAsync()
                            }
                        }) { Text(if (playing) "⏸ Pause" else "▶ Play", color = IOS_BLUE) }
                        Text("♪ " + sg.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { runCatching { player.reset() }; playing = false; song = null }) { Icon(Icons.Filled.Close, "remove", tint = Color.White) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(caption, { caption = it }, placeholder = { Text("Add a caption…", color = Color.White.copy(alpha = 0.6f)) }, singleLine = true, shape = RoundedCornerShape(26.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.White.copy(alpha = 0.14f), unfocusedContainerColor = Color.White.copy(alpha = 0.14f),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White),
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = { onUpload(caption.trim(), audience, selected.toList()) }) { Icon(Icons.AutoMirrored.Filled.Send, "upload") }
                }
            }
        }
    }
    if (pickAudience) AudienceSheet(contacts, audience, selected) { a, sset -> audience = a; selected = sset; pickAudience = false }
    if (musicOpen) MusicSearchSheet(onPick = { song = it; musicOpen = false }, onClose = { musicOpen = false })
}

@Composable
private fun AudienceSheet(contacts: List<DeviceContact>, audienceIn: String, selectedIn: Set<String>, onDone: (String, Set<String>) -> Unit) {
    var aud by remember { mutableStateOf(audienceIn) }
    var sel by remember { mutableStateOf(selectedIn) }
    var q by remember { mutableStateOf("") }
    val filtered = remember(contacts, q) { if (q.isBlank()) contacts else contacts.filter { it.name.contains(q, true) || it.number.contains(q) } }
    Dialog(onDismissRequest = { onDone(aud, sel) }) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp).heightIn(max = 560.dp)) {
                Text("Status privacy", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                AudRadio("My contacts", aud == "all") { aud = "all" }
                AudRadio("My contacts except…", aud == "except") { aud = "except" }
                AudRadio("Only share with…", aud == "only") { aud = "only" }
                if (aud != "all") {
                    OutlinedTextField(q, { q = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        itemsIndexed(filtered) { _, c ->
                            val jid = c.number + "@s.whatsapp.net"
                            Row(Modifier.fillMaxWidth().clickable { sel = if (jid in sel) sel - jid else sel + jid }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(jid in sel, { checked -> sel = if (checked) sel + jid else sel - jid })
                                Spacer(Modifier.width(6.dp))
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Button(onClick = { onDone(aud, sel) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun AudRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}


@Composable
private fun StatusViewer(statuses: List<GatewayClient.StatusItem>, startSender: String, dpCache: MutableMap<String, ImageBitmap?>, onDownload: (GatewayClient.StatusItem) -> Unit, onReply: (String, String) -> Unit, onClose: () -> Unit) {
    val groups = remember(statuses) {
        statuses.groupBy { it.sender }.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<GatewayClient.StatusItem>>> { e -> e.value.any { it.mine } }.thenByDescending { e -> e.value.maxOf { it.ts } })
            .map { it.key to it.value.sortedBy { s -> s.ts } }
    }
    if (groups.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val target = startSender.substringBefore("@").substringBefore(":").filter { it.isDigit() }
    var si by remember { mutableStateOf(groups.indexOfFirst { g -> g.first.substringBefore("@").substringBefore(":").filter { it.isDigit() } == target }.coerceAtLeast(0)) }
    var ii by remember { mutableStateOf(0) }
    val group = groups.getOrNull(si) ?: run { LaunchedEffect(Unit) { onClose() }; return }
    val items = group.second
    val st = items.getOrNull(ii) ?: run { LaunchedEffect(Unit) { onClose() }; return }
    fun goNext() { if (ii < items.size - 1) ii++ else if (si < groups.size - 1) { si++; ii = 0 } else onClose() }
    fun goPrev() { if (ii > 0) ii-- else if (si > 0) { si--; ii = 0 } }
    var replyText by remember { mutableStateOf("") }
    var progress by remember(si, ii) { mutableStateOf(0f) }

    var bmp by remember(st.mediaName, si, ii) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(st.mediaName, si, ii) {
        bmp = null
        val name = st.mediaName
        if (name != null && st.mediaType != "video") {
            val bytes = GatewayClient.mediaBytes(name)
            bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } ?: decodeThumb(st.thumb)
        }
    }
    LaunchedEffect(si, ii, replyText.isBlank()) {
        if (replyText.isNotBlank()) return@LaunchedEffect
        if (st.mediaType == "video") return@LaunchedEffect
        progress = 0f
        val dur = 5000L; val step = 40L; var elapsed = 0L
        while (elapsed < dur) {
            delay(step); elapsed += step; progress = (elapsed.toFloat() / dur).coerceIn(0f, 1f)
            if (replyText.isNotBlank()) return@LaunchedEffect
        }
        goNext()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                if (st.mediaType == "video" && st.mediaName != null) {
                    key(si, ii, st.mediaName) {
                        AndroidView(factory = { c -> VideoView(c).apply { setVideoURI(Uri.parse(GatewayClient.mediaUrl(st.mediaName!!))); setOnPreparedListener { it.start() }; setOnCompletionListener { goNext() } } }, modifier = Modifier.fillMaxSize())
                    }
                } else if (bmp != null) {
                    Image(bmp!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(st.text.ifBlank { "…" }, color = Color.White, modifier = Modifier.padding(24.dp)) }
                }
                if (st.text.isNotBlank() && st.mediaType != null) {
                    Text(st.text, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp))
                }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { goPrev() })
                    Box(Modifier.weight(1.6f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { goNext() })
                }
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        items.indices.forEach { idx ->
                            LinearProgressIndicator(progress = { if (idx < ii) 1f else if (idx == ii) progress else 0f }, modifier = Modifier.weight(1f).height(3.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.35f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(group.first, group.first.substringBefore("@"), dpCache, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (items.first().mine) "My Status" else (ContactStore.nameFor(group.first) ?: items.first().name.ifBlank { "Status" }), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                        if (st.mediaName != null) IconButton(onClick = { onDownload(st) }) { Icon(Icons.Filled.Download, "download", tint = Color.White) }
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "close", tint = Color.White) }
                    }
                }
                if (!items.first().mine) {
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(replyText, { replyText = it }, placeholder = { Text("Reply to status…", color = Color.White.copy(alpha = 0.6f)) }, singleLine = true, shape = RoundedCornerShape(26.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White.copy(alpha = 0.4f), unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                                focusedContainerColor = Color.Black.copy(alpha = 0.4f), unfocusedContainerColor = Color.Black.copy(alpha = 0.4f),
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White),
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { if (replyText.isNotBlank()) { onReply(group.first, replyText.trim()); replyText = "" } }, enabled = replyText.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "send") }
                    }
                }
            }
        }
    }
}


@Composable
private fun ProfileScreen(myJid: String?, dpCache: MutableMap<String, ImageBitmap?>, onPickPhoto: () -> Unit, onSaveName: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.clip(CircleShape).clickable { onPickPhoto() }) {
            if (myJid != null) Avatar(myJid, "Me", dpCache, 120.dp)
            else Box(Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
        }
        TextButton(onClick = onPickPhoto) { Text("Change photo") }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Button(onClick = { if (name.isNotBlank()) onSaveName(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save name") }
    }
}


@Composable
private fun MusicSearchSheet(onPick: (GatewayClient.Song) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GatewayClient.Song>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(q) {
        if (q.trim().length >= 2) { loading = true; delay(450); val r = GatewayClient.searchMusic(q.trim()); results = r.first; error = r.second; loading = false }
        else { results = emptyList(); error = "" }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp).heightIn(max = 520.dp)) {
                Text("Add music", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(q, { q = it }, placeholder = { Text("Search songs…") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                if (!loading && results.isEmpty() && q.trim().length >= 2) {
                    Text(if (error.isNotBlank()) "No songs (" + error.take(120) + ")" else "No results", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.fillMaxWidth()) {
                    itemsIndexed(results) { _, sg ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(sg) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = IOS_BLUE)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(sg.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(sg.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
