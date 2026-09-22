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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val view = LocalView.current
                val barColor = MaterialTheme.colorScheme.surface
                SideEffect {
                    val window = (view.context as Activity).window
                    window.statusBarColor = barColor.toArgb()
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

private fun chatTitle(msgs: List<GatewayClient.Msg>): String {
    msgs.firstOrNull { !it.fromMe && it.name.isNotBlank() }?.let { return it.name }
    val chat = msgs.firstOrNull()?.chat ?: return "Unknown"
    return if (chat.endsWith("@s.whatsapp.net")) "+" + chat.substringBefore("@") else "Unknown contact"
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

class OptMsg(val chat: String, val text: String, val ts: Long)

@OptIn(ExperimentalMaterial3Api::class)
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val jid = openChat
        if (uri != null && jid != null) {
            scope.launch {
                try {
                    val cr = ctx.contentResolver
                    val mime = cr.getType(uri) ?: "application/octet-stream"
                    val type = when {
                        mime.startsWith("image") -> "image"; mime.startsWith("video") -> "video"
                        mime.startsWith("audio") -> "audio"; else -> "document"
                    }
                    val bytes = withContext(Dispatchers.IO) { cr.openInputStream(uri)?.use { it.readBytes() } }
                    if (bytes == null) { notify("can't read file"); return@launch }
                    notify("sending $type…")
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    GatewayClient.sendMedia(jid, b64, type, queryName(ctx, uri) ?: "file", "")
                } catch (e: Exception) { notify("send failed: ${e.message}") }
            }
        }
    }

    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    fun loadWallpaper() {
        val f = File(ctx.filesDir, "wallpaper.jpg")
        wallpaper = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() else null
    }
    LaunchedEffect(Unit) { loadWallpaper() }
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
        chatWp = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() else null
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
    var blockedJids by remember { mutableStateOf(blkPrefs.getStringSet("blocked", emptySet())!!.toSet()) }
    LaunchedEffect(openChat) { loadChatWp(openChat); openChat?.let { NotificationHelper.cancel(ctx, it) } }
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
                val maxTs = messages.maxOfOrNull { it.ts } ?: lastNotifTs
                if (maxTs > lastNotifTs) {
                    messages.filter { !it.fromMe && !it.deleted && it.ts > lastNotifTs && it.chat != openChat }
                        .groupBy { it.chat }.forEach { (chat, msgs) ->
                            val m = msgs.maxByOrNull { it.ts }!!
                            val dpBmp = GatewayClient.dpBytes(chat)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            val body = if (m.text.isNotBlank()) m.text else if (m.mediaType != null) "[${m.mediaType}]" else ""
                            NotificationHelper.notifyMessage(ctx, chat, chatTitle(msgs), body, dpBmp)
                        }
                    lastNotifTs = maxTs
                }
            }
            delay(3000)
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2500); toast = null } }

    BackHandler(enabled = viewImg != null || viewVideoUrl != null || openChat != null || screen == "settings") {
        when {
            viewImg != null -> viewImg = null
            viewVideoUrl != null -> viewVideoUrl = null
            openChat != null -> openChat = null
            else -> screen = "chats"
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
            if (status.registered && openChat == null && screen == "chats")
                FloatingActionButton(onClick = { showNewChat = true }) { Icon(Icons.Filled.Add, "new chat") }
        },
        topBar = {
            TopAppBar(
                title = {
                    if (searchMode && openChat == null && screen == "chats") {
                        OutlinedTextField(searchQuery, { searchQuery = it }, placeholder = { Text("Search chats") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else if (openChat != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(openChat!!, chatName ?: "?", dpCache, 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                chatPresence?.let { pr ->
                                    val sub = if (pr.online) "online" else if (pr.lastSeen > 0) "last seen " + fmt(pr.lastSeen * 1000) else ""
                                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else Text(title)
                },
                navigationIcon = {
                    if (openChat != null || screen == "settings")
                        IconButton(onClick = { if (openChat != null) openChat = null else screen = "chats" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "back")
                        }
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
        Box(Modifier.fillMaxSize().padding(pad)) {
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
                    onDeleteMsg = { m, everyone -> scope.launch { m.id?.let { GatewayClient.deleteMessage(m.chat, it, everyone, m.fromMe); messages = GatewayClient.getMessages() } } },
                    onAttach = { picker.launch("*/*") },
                    previewCache = previewCache,
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
                else -> ChatList(messages, dpCache, searchQuery, onDelete = { jid -> scope.launch { GatewayClient.deleteChat(jid); messages = GatewayClient.getMessages() } }) { openChat = it }
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
private fun Avatar(jid: String, name: String, cache: MutableMap<String, ImageBitmap?>, size: androidx.compose.ui.unit.Dp) {
    LaunchedEffect(jid) {
        if (!cache.containsKey(jid)) {
            val b = GatewayClient.dpBytes(jid)
            cache[jid] = b?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    val dp = cache[jid]
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        if (dp != null) Image(dp, "dp", Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatList(messages: List<GatewayClient.Msg>, dpCache: MutableMap<String, ImageBitmap?>, query: String, onDelete: (String) -> Unit, onOpen: (String) -> Unit) {
    val groups = messages.groupBy { it.chat }.entries
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
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(entry.key) }, onLongClick = { menu = true }).padding(horizontal = 14.dp, vertical = 12.dp),
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
    previewCache: MutableMap<String, ImageBitmap?>,
    wallpaper: ImageBitmap?,
) {
    var input by remember { mutableStateOf("") }
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
        (messages + optimistic.map { GatewayClient.Msg(chat, "", true, it.text, it.ts) }).sortedByDescending { it.ts }
    }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        wallpaper?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item { Spacer(Modifier.height(6.dp)) }
            itemsIndexed(rows, key = { i, m -> "${m.ts}-$i" }) { _, m -> MessageBubble(m, previewCache, onMedia, onShare, onDownload) { reactMsg = it } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onAttach) { Icon(Icons.Filled.AttachFile, "attach") }
            OutlinedTextField(input, { input = it }, placeholder = { Text("Message") },
                shape = RoundedCornerShape(24.dp), maxLines = 4, modifier = Modifier.weight(1f))
            FilledIconButton(onClick = { if (input.isNotBlank()) { onSend(input.trim()); input = "" } },
                enabled = input.isNotBlank() && canSend) { Icon(Icons.AutoMirrored.Filled.Send, "send") }
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
private fun MessageBubble(m: GatewayClient.Msg, previewCache: MutableMap<String, ImageBitmap?>, onMedia: (GatewayClient.Msg) -> Unit, onShare: (GatewayClient.Msg) -> Unit, onDownload: (GatewayClient.Msg) -> Unit, onLongClick: (GatewayClient.Msg) -> Unit) {
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

    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
        Surface(color = bubbleColor, shape = shape,
            modifier = Modifier.widthIn(max = 290.dp).combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = { onLongClick(m) })) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    if (m.text.isNotBlank()) Text(m.text, color = textColor)
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
                Text("On = incoming media downloaded (needed for view/play/share + deleted media). Uses storage.", style = MaterialTheme.typography.bodySmall)
                ToggleRow("Freeze last seen (stay offline)", settings.stayOffline) { onToggle(JSONObject().put("stayOffline", it)) }
                Text("On = never broadcasts online. (Overrides Always online.)", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionHeader("Profile", section == "profile") { toggle("profile") }
        AnimatedVisibility(section == "profile") {
            var pname by remember { mutableStateOf("") }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth()) { Text("Change profile photo") }
                OutlinedTextField(pname, { pname = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { if (pname.isNotBlank()) onSaveName(pname.trim()) }, enabled = pname.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save name") }
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
