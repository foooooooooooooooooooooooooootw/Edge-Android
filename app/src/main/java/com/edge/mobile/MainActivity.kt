package com.edge.mobile

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.os.PowerManager
import androidx.compose.ui.graphics.graphicsLayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        intent.getStringExtra("peerId")?.let { EdgeState.openPeer.value = it }
    }

    override fun onStart() { super.onStart(); EdgeState.appVisible.value = true }
    override fun onStop() { EdgeState.appVisible.value = false; super.onStop() }

    /** Called for the Share menu (ACTION_SEND / SEND_MULTIPLE). The UI then asks which device to use. */
    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> return
        }
        val text = if (uris.isEmpty()) i.getStringExtra(Intent.EXTRA_TEXT) else null
        if (uris.isEmpty() && text.isNullOrBlank()) return
        EdgeState.pendingShare.value = PendingShare(uris, text)
        setIntent(Intent())   // so rotating the screen doesn't re-share
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val prefs = getSharedPreferences("edge", MODE_PRIVATE)
        if (savedInstanceState == null) { handleShare(intent); intent.getStringExtra("peerId")?.let { EdgeState.openPeer.value = it } }
        setContent {
            var themeId by remember { mutableStateOf(prefs.getString("theme", "ocean") ?: "ocean") }
            var myName by remember { mutableStateOf(prefs.getString("name", null) ?: Build.MODEL) }
            var myPic by remember { mutableStateOf(prefs.getString("pic", null)) }
            var gallery by remember { mutableStateOf(prefs.getBoolean("gallery", true)) }
            val c = Themes.byId(themeId)
            CompositionLocalProvider(LocalEdge provides c) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = c.accent, onPrimary = Color.White, background = c.base, onBackground = c.text1,
                        surface = c.elevated, onSurface = c.text1, error = Color(0xFFEF4444),
                    )
                ) {
                    Root(
                        themeId, myName, myPic, gallery,
                        onGallery = { gallery = it; prefs.edit().putBoolean("gallery", it).apply() },
                        onPic = { myPic = it; prefs.edit().putString("pic", it).apply(); EdgeService.setPic(it) },
                        onTheme = { themeId = it; prefs.edit().putString("theme", it).apply() },
                        onName = { myName = it; prefs.edit().putString("name", it).apply(); EdgeService.rename(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Root(themeId: String, myName: String, myPic: String?, gallery: Boolean, onGallery: (Boolean) -> Unit, onPic: (String?) -> Unit, onTheme: (String) -> Unit, onName: (String) -> Unit) {
    val c = LocalEdge.current
    val ctx = LocalContext.current
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { EdgeService.start(ctx) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else EdgeService.start(ctx)
    }

    val peers by EdgeState.peers.collectAsState()
    val requests by EdgeState.requests.collectAsState()
    val share by EdgeState.pendingShare.collectAsState()
    val viewer by EdgeState.viewer.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val peer = peers.firstOrNull { it.id == selected }
    val openPeer by EdgeState.openPeer.collectAsState()
    LaunchedEffect(openPeer) { openPeer?.let { selected = it; EdgeState.openPeer.value = null } }
    LaunchedEffect(selected) {
        EdgeState.activePeer.value = selected
        selected?.let { Notifier.cancelPeer(ctx, it) }
    }
    BackHandler(enabled = share != null) { EdgeState.pendingShare.value = null }
    BackHandler(enabled = share == null && selected != null) { selected = null }

    requests.firstOrNull()?.let { req ->
        val who = peers.firstOrNull { it.id == req.peerId }?.name ?: "A device"
        AlertDialog(
            onDismissRequest = {},
            containerColor = c.elevated,
            title = { Text(if (req.isFolder) "Incoming folder" else "Incoming file", color = c.text1) },
            text = { Text("$who wants to send\n${req.name}  (" + (if (req.isFolder) "${req.fileCount} files, " else "") + "${fmt(req.size)})", color = c.text2) },
            confirmButton = { TextButton({ EdgeService.respond(req, true) }) { Text("Accept", color = c.accent) } },
            dismissButton = { TextButton({ EdgeService.respond(req, false) }) { Text("Decline", color = c.text2) } },
        )
    }
    viewer?.let { MediaViewer(it) { EdgeState.viewer.value = null } }
    if (showSettings) SettingsDialog(themeId, myName, myPic, gallery, onGallery, onPic, onTheme, onName) { showSettings = false }
    if (showAdd) AddDeviceDialog { showAdd = false }

    Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (share != null) ShareTarget(share!!, peers) { id ->
            val sh = share!!
            sh.uris.forEach { EdgeService.sendFile(ctx, id, it) }
            sh.text?.let { EdgeService.chat(id, it) }
            EdgeState.pendingShare.value = null
            selected = id
        }
        else if (peer == null) PeerList(peers, myName, myPic, onPick = { selected = it }, onSettings = { showSettings = true }, onAdd = { showAdd = true })
        else ChatScreen(peer) { selected = null }
    }
}

// ───────────────────────────── peer list ─────────────────────────────

@Composable
private fun ColumnScope.PeerList(peers: List<UiPeer>, myName: String, myPic: String?, onPick: (String) -> Unit, onSettings: () -> Unit, onAdd: () -> Unit) {
    val c = LocalEdge.current
    val status by EdgeState.status.collectAsState()

    Row(Modifier.fillMaxWidth().background(c.side).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(myName, "me", 38.dp, pic = myPic)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("⚡ Edge", color = c.text1, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(status, color = c.text3, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundBtn("+", c.accent.copy(alpha = 0.12f), c.accent, onClick = onAdd)
        Spacer(Modifier.width(8.dp))
        RoundBtn("⚙", Color.Transparent, c.text2, onClick = onSettings)
    }

    val sorted = remember(peers) { peers.sortedByDescending { it.connected } }
    Box(Modifier.weight(1f).fillMaxWidth()) {
    // The desktop's floating hexagon stays on, faintly, behind the list once devices appear.
    if (sorted.isNotEmpty()) FloatingHex(Modifier.align(Alignment.Center).graphicsLayer { alpha = 0.55f })
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        if (sorted.isEmpty()) item {
            EmptyState("No devices yet", "Open Edge on your computer on the same Wi-Fi, or tap + to connect by IP.")
        }
        items(sorted, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(p.id) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p.name, p.id, 44.dp, status = if (p.connected) c.online else c.offline, pic = p.profilePic)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(p.name, color = if (p.connected) c.text1 else c.text2, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        (if (!p.lan) "WAN · " else "") + (if (p.connected) p.ip else "offline"), color = c.text3, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
    }
}

// ───────────────────────────── share target ─────────────────────────────

@Composable
private fun ColumnScope.ShareTarget(share: PendingShare, peers: List<UiPeer>, onPick: (String) -> Unit) {
    val c = LocalEdge.current
    val online = peers.filter { it.connected }
    val what = when {
        share.uris.size > 1 -> "${share.uris.size} files"
        share.uris.size == 1 -> "1 file"
        else -> "text"
    }
    Row(Modifier.fillMaxWidth().background(c.side).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundBtn("✕", Color.Transparent, c.text2, onClick = { EdgeState.pendingShare.value = null })
        Spacer(Modifier.width(8.dp))
        Text("Send $what to…", color = c.text1, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
        if (online.isEmpty()) item {
            Text("Waiting for a device… open Edge on your computer, or connect by IP from the main screen.", color = c.text3, modifier = Modifier.padding(24.dp), lineHeight = 20.sp)
        }
        items(online, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(p.id) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p.name, p.id, 44.dp, status = c.online, pic = p.profilePic)
                Spacer(Modifier.width(12.dp))
                Text(p.name, color = c.text1, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

// ───────────────────────────── dialogs ─────────────────────────────

@Composable
private fun SettingsDialog(themeId: String, name: String, pic: String?, gallery: Boolean, onGallery: (Boolean) -> Unit, onPic: (String?) -> Unit, onTheme: (String) -> Unit, onName: (String) -> Unit, onClose: () -> Unit) {
    val c = LocalEdge.current
    var n by remember { mutableStateOf(name) }
    val ctx = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val d = ProfilePic.fromUri(ctx, uri)
            if (d != null) onPic(d) else Toast.makeText(ctx, "Couldn't read that image", Toast.LENGTH_SHORT).show()
        }
    }
    AlertDialog(
        onDismissRequest = { onName(n.trim().ifEmpty { name }); onClose() },
        containerColor = c.elevated,
        title = { Text("Settings", color = c.text1) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(n.ifBlank { name }, "me", 64.dp, pic = pic)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        TextButton({ pickImage.launch("image/*") }) { Text("Change photo", color = c.accent) }
                        if (pic != null) TextButton({ onPic(null) }) { Text("Remove", color = c.text2) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Display name", color = c.text2, fontSize = 12.sp)
                OutlinedTextField(
                    n, { n = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text1, unfocusedTextColor = c.text1, focusedBorderColor = c.accent),
                )
                Text("Other devices see the new name the next time you connect.", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Save photos & videos to Gallery", color = c.text1, fontSize = 14.sp)
                        Text(
                            if (gallery) "Saved in Pictures/Edge and Movies/Edge" else "Everything is saved in Downloads/Edge",
                            color = c.text3, fontSize = 11.sp,
                        )
                    }
                    Switch(gallery, onGallery, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
                }
                Spacer(Modifier.height(16.dp))
                val prefs = ctx.getSharedPreferences("edge", android.content.Context.MODE_PRIVATE)
                var keep by remember { mutableStateOf(prefs.getBoolean("keepalive", true)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Stay connected with screen off", color = c.text1, fontSize = 14.sp)
                        Text("Keeps the CPU awake so messages arrive. Takes effect after restarting the app.", color = c.text3, fontSize = 11.sp)
                    }
                    Switch(keep, { keep = it; prefs.edit().putBoolean("keepalive", it).apply() }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
                }
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    TextButton({
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
                    }) { Text("Allow unrestricted battery use", color = c.accent) }
                }
                Spacer(Modifier.height(16.dp))
                Text("Theme", color = c.text2, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Themes.all.forEach { t ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onTheme(t.id) }) {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape).background(t.accent)
                                    .border(if (t.id == themeId) BorderStroke(2.dp, c.text1) else BorderStroke(0.dp, Color.Transparent), CircleShape)
                            )
                            Text(t.label, color = if (t.id == themeId) c.text1 else c.text3, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onName(n.trim().ifEmpty { name }); onClose() }) { Text("Done", color = c.accent) } },
    )
}

@Composable
private fun AddDeviceDialog(onClose: () -> Unit) {
    val c = LocalEdge.current
    val ctx = LocalContext.current
    val saved by EdgeState.savedWan.collectAsState()
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("42069") }
    var wan by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.elevated,
        title = { Text("Connect by address", color = c.text1) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (wan) "Enter your computer's public IP or a dynamic-DNS name, and the port it opened (shown in Edge's \"My info\"). It's remembered and re-connected automatically, including over mobile data."
                    else "Use this if devices don't appear automatically (some routers block discovery).",
                    color = c.text2, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(ip, { ip = it }, label = { Text(if (wan) "IP or hostname" else "IP address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Over the internet (WAN)", color = c.text1, fontSize = 14.sp)
                        if (wan) Text("Not on the same Wi-Fi. After connecting, compare the security code with your computer's.", color = c.text3, fontSize = 11.sp)
                    }
                    Switch(wan, { wan = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
                }
                if (saved.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Remembered internet devices", color = c.text2, fontSize = 12.sp)
                    saved.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${a.host}:${a.port}", color = c.text1, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            TextButton({ EdgeService.removeSaved(ctx, a) }) { Text("Forget", color = c.text2) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ if (ip.isNotBlank()) EdgeService.connect(ctx, ip, port.toIntOrNull() ?: 42069, wan); onClose() }) { Text("Connect", color = c.accent) }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel", color = c.text2) } },
    )
}
