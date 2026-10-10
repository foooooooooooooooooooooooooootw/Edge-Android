@file:OptIn(ExperimentalFoundationApi::class)

package com.edge.mobile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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

private sealed class Item(val ts: Long) {
    class Msg(val m: ChatMsg) : Item(m.ts)
    class File(val t: Transfer) : Item(t.ts)
}

private val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "🎉")

@Composable
fun ColumnScope.ChatScreen(peer: UiPeer, onBack: () -> Unit) {
    val c = LocalEdge.current
    val ctx = LocalContext.current
    val messages by EdgeState.messages.collectAsState()
    val transfers by EdgeState.transfers.collectAsState()
    val streaks by EdgeState.streaks.collectAsState()
    val streak = streaks[peer.id]?.count ?: 0
    var text by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<ChatMsg?>(null) }
    var actionMsg by remember { mutableStateOf<ChatMsg?>(null) }
    var showCode by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        uris.forEach { EdgeService.sendFile(ctx, peer.id, it) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) EdgeService.sendFolder(ctx, peer.id, uri)
    }

    val timeline = remember(messages, transfers, peer.id) {
        (messages.filter { it.peerId == peer.id }.map { Item.Msg(it) } +
            transfers.values.filter { it.peerId == peer.id }.map { Item.File(it) }).sortedBy { it.ts }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(timeline.size, streak >= 2) {
        if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.size + 1 + (if (streak >= 2) 1 else 0))
    }

    // header
    Row(Modifier.fillMaxWidth().background(c.side).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundBtn("←", Color.Transparent, c.text2, onClick = onBack)
        Spacer(Modifier.width(4.dp))
        Avatar(peer.name, peer.id, 36.dp, status = if (peer.connected) c.online else c.offline, pic = peer.profilePic)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.clickable(enabled = peer.connected) { showCode = true }) {
            Text(peer.name, color = c.text1, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                if (peer.connected) "${if (peer.lan) "LAN" else "WAN"} · 🔒 ${peer.fingerprint.take(8)} · tap to verify" else "offline · reconnecting…",
                color = c.text3, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }

    // timeline
    LazyColumn(Modifier.weight(1f).fillMaxWidth().background(c.chat).padding(horizontal = 10.dp), state = listState) {
        item { Spacer(Modifier.height(8.dp)) }
        items(timeline, key = { when (it) { is Item.Msg -> "m" + it.m.id; is Item.File -> "f" + it.t.fileId } }) { item ->
            when (item) {
                is Item.Msg -> MessageBubble(item.m, peer.id, onLong = { actionMsg = item.m })
                is Item.File -> FileBubble(item.t)
            }
        }
        if (streak >= 2) item { StreakBadge(streak) }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showCode) {
        val code = peer.fingerprint.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }
        AlertDialog(
            onDismissRequest = { showCode = false }, containerColor = c.elevated,
            title = { Text("Security code", color = c.text1) },
            text = {
                Column {
                    Text(code, color = c.text1, fontFamily = FontFamily.Monospace, fontSize = 18.sp, lineHeight = 28.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Open this chat on ${peer.name} and compare its code. If they match, nobody is intercepting the connection. This matters most over the internet (WAN). If they differ, disconnect.", color = c.text2, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton({ showCode = false }) { Text("Done", color = c.accent) } },
        )
    }

    actionMsg?.let { m ->
        MessageActions(
            m,
            onReact = { EdgeService.react(peer.id, m.id, it); actionMsg = null },
            onReply = { replyTo = m; actionMsg = null },
            onClose = { actionMsg = null },
        )
    }

    // reply banner
    replyTo?.let { r ->
        Row(Modifier.fillMaxWidth().background(c.side).padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(32.dp).background(c.accent))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (r.mine) "You" else peer.name, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(r.text, color = c.text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundBtn("✕", Color.Transparent, c.text2, onClick = { replyTo = null })
        }
    }

    // input bar
    Row(Modifier.fillMaxWidth().background(c.side).padding(8.dp), verticalAlignment = Alignment.Bottom) {
        RoundBtn("📎", c.elevated, c.accent, enabled = peer.connected) { picker.launch(arrayOf("*/*")) }
        Spacer(Modifier.width(6.dp))
        RoundBtn("📁", c.elevated, c.accent, enabled = peer.connected) { folderPicker.launch(null) }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(18.dp)).background(c.elevated)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.055f)), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("Message", color = c.text3, fontSize = 14.sp)
            BasicTextField(
                text, { text = it },
                textStyle = androidx.compose.ui.text.TextStyle(color = c.text1, fontSize = 14.sp),
                cursorBrush = SolidColor(c.accent), maxLines = 5,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        val canSend = peer.connected && text.isNotBlank()
        RoundBtn("➤", if (canSend) c.accent else c.elevated, if (canSend) Color.White else c.text3, enabled = canSend) {
            EdgeService.chat(peer.id, text.trim(), replyTo); text = ""; replyTo = null
        }
    }
}

@Composable
private fun MessageActions(m: ChatMsg, onReact: (String) -> Unit, onReply: () -> Unit, onClose: () -> Unit) {
    val c = LocalEdge.current
    val clip = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.elevated,
        title = null,
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QUICK.take(4).forEach { e -> Text(e, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable { onReact(e) }.padding(6.dp)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QUICK.drop(4).forEach { e -> Text(e, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable { onReact(e) }.padding(6.dp)) }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onReply, Modifier.fillMaxWidth()) { Text("↩  Reply", color = c.text1) }
                TextButton({ clip.setText(AnnotatedString(m.text)); onClose() }, Modifier.fillMaxWidth()) { Text("⧉  Copy text", color = c.text1) }
            }
        },
        confirmButton = {},
    )
}

val OutText = Color(0xFFD8E8FF)   // .bubble.out color in the desktop css

@Composable
private fun MessageBubble(m: ChatMsg, peerId: String, onLong: () -> Unit) {
    val c = LocalEdge.current
    val reactions by EdgeState.reactions.collectAsState()
    val chips = reactions[reactionKey(peerId, m.id)].orEmpty()
    Bubble(m.mine, m.ts, onLong = onLong) {
        m.replyTo?.let { q ->
            val who = if (q.byMe) "You" else EdgeState.peers.value.firstOrNull { it.id == peerId }?.name ?: "Them"
            Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.18f)).height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(c.accent))
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(who, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(q.text, color = c.text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(m.text, fontSize = 14.sp, color = if (m.mine) OutText else c.text1, lineHeight = 20.sp)
        if (chips.isNotEmpty()) ReactionBar(chips) { EdgeService.react(peerId, m.id, it) }
    }
}

@Composable
private fun ReactionBar(chips: List<Reaction>, onToggle: (String) -> Unit) {
    val c = LocalEdge.current
    val grouped = chips.groupBy { it.emoji }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        grouped.forEach { (emoji, list) ->
            val mine = list.any { it.mine }
            Row(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (mine) c.accent.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.10f))
                    .clickable { onToggle(emoji) }.padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 13.sp)
                if (list.size > 1) Text(" ${list.size}", fontSize = 11.sp, color = c.text2)
            }
        }
    }
}

@Composable
fun Bubble(mine: Boolean, ts: Long, onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalEdge.current
    // same corner treatment as the desktop: 16px with a 4px "tail" corner
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = if (mine) 4.dp else 16.dp, bottomStart = if (mine) 16.dp else 4.dp)
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    val time = remember(ts) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts)) }
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = maxW).clip(shape).background(if (mine) c.msgOut else c.msgIn)
                .then(if (onClick != null || onLong != null) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLong) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            content()
            Text(
                time, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = if (mine) Color(0x66B4D2FF) else Color(0x4DFFFFFF),
                modifier = Modifier.align(Alignment.End).padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    val c = LocalEdge.current
    Text(
        label, fontSize = 12.sp, color = c.text1,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun FileBubble(t: Transfer) {
    val c = LocalEdge.current
    val ctx = LocalContext.current
    val openable = !t.sending && t.finished && t.uri != null && !t.isFolder
    val kind = remember(t.name, t.isFolder) { if (t.isFolder) MediaKind.OTHER else mediaKind(t.name) }
    val thumb by produceState<ImageBitmap?>(null, t.uri, kind) {
        value = if (t.uri != null && kind != MediaKind.OTHER && (t.sending || t.finished))
            withContext(Dispatchers.IO) { Thumbnails.load(ctx, t.uri, kind) } else null
    }

    val onTap: (() -> Unit)? = when {
        kind != MediaKind.OTHER && t.uri != null && (t.sending || t.finished) -> ({ EdgeState.viewer.value = ViewerItem(t.uri, t.name, kind) })
        openable -> ({ openExternally(ctx, t.uri!!) })
        else -> null
    }

    Bubble(t.sending, t.ts, onClick = onTap) {
        val th = thumb
        if (th != null) {
            Box(
                Modifier.fillMaxWidth().aspectRatio((th.width.toFloat() / th.height).coerceIn(0.75f, 1.6f)).clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(th, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (kind == MediaKind.VIDEO) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Text("▶", color = Color.White, fontSize = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(t.name, fontSize = 12.sp, color = if (t.sending) OutText else c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    Text(if (t.isFolder) "📁" else fileIcon(t.name), fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(t.name, fontSize = 13.5.sp, color = if (t.sending) OutText else c.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val count = if (t.isFolder && t.fileCount > 0) "${t.fileCount} files · " else ""
        val sub = when {
            t.error == "cancelled" -> "Cancelled"
            t.error != null -> "Failed: ${t.error}"
            t.finished && t.isFolder -> "$count${fmt(t.total)} · " + (t.detail ?: if (t.sending) "sent" else "saved")
            t.finished -> "${fmt(t.total)} · " + if (t.sending) "sent" else if (kind == MediaKind.OTHER) "tap to open" else "tap to view"
            t.paused -> "Paused · ${fmt(t.done)} / ${fmt(t.total)}"
            t.total > 0 -> "$count${(t.done * 100 / t.total).toInt()}% · ${fmt(t.done)} / ${fmt(t.total)}"
            else -> "waiting…"
        }
        Text(sub, fontSize = 11.sp, color = if (t.error != null && t.error != "cancelled") Color(0xFFEF4444) else c.text2, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 2.dp))
        if (t.isFolder && !t.finished && t.error == null && !t.detail.isNullOrBlank()) {
            Text(t.detail, fontSize = 10.sp, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
        }
        if (!t.finished && t.error == null) {
            LinearProgressIndicator(
                progress = { if (t.total > 0) (t.done.toFloat() / t.total).coerceIn(0f, 1f) else 0f },
                color = if (t.paused) c.text3 else c.accent, trackColor = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
            )
            // Pause only makes sense once bytes are flowing (or on the receiving side); cancel is sender-only in the protocol.
            val canPause = t.done > 0 || !t.sending
            if (canPause || t.sending) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canPause) Pill(if (t.paused) "▶ Resume" else "⏸ Pause") { if (t.paused) EdgeService.resume(t) else EdgeService.pause(t) }
                    if (t.sending) Pill("✕ Cancel") { EdgeService.cancel(t) }
                }
            }
        }
    }
}

// ───────────────────────────── small pieces ─────────────────────────────

@Composable
fun Avatar(name: String, id: String, size: androidx.compose.ui.unit.Dp, status: Color? = null, pic: String? = null) {
    val c = LocalEdge.current
    val bmp = remember(pic) { pic?.let { ProfilePic.decode(it) } }
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(peerColor(id)), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp)
        }
        if (status != null) {
            Box(
                Modifier.size(size * 0.28f).align(Alignment.BottomEnd).clip(CircleShape).background(status)
                    .border(BorderStroke(2.dp, c.side), CircleShape)
            )
        }
    }
}

@Composable
fun RoundBtn(label: String, bg: Color, fg: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 17.sp) }
}

fun fileIcon(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic" -> "🖼"
    "mp4", "mkv", "mov", "webm", "avi" -> "🎬"
    "mp3", "wav", "flac", "ogg", "m4a" -> "🎵"
    "zip", "rar", "7z", "gz", "tar" -> "📦"
    "pdf" -> "📕"
    else -> "📄"
}

fun fmt(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
