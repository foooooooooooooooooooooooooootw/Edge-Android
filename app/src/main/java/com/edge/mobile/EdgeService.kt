package com.edge.mobile

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import edge.core.*
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/**
 * Keeps the Edge node alive while the app is in the background. Android drops UDP broadcasts
 * unless a MulticastLock is held, and kills idle sockets unless a foreground service is running.
 */
class EdgeService : Service() {
    companion object {
        @Volatile var node: EdgeNode? = null
        private val io = Executors.newCachedThreadPool()

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, EdgeService::class.java))
        }

        @Volatile private var redialer: Redialer? = null

        // Saved internet addresses: one "host|port|peerId" per line in prefs.
        fun saved(ctx: Context): List<SavedAddr> =
            ctx.getSharedPreferences("edge", Context.MODE_PRIVATE).getString("wan", "").orEmpty().lines().mapNotNull { l ->
                val p = l.split("|"); val port = p.getOrNull(1)?.toIntOrNull()
                if (p[0].isBlank() || port == null) null else SavedAddr(p[0], port, p.getOrNull(2)?.ifBlank { null })
            }

        private fun writeSaved(ctx: Context, l: List<SavedAddr>) {
            ctx.getSharedPreferences("edge", Context.MODE_PRIVATE).edit()
                .putString("wan", l.joinToString("\n") { "${it.host}|${it.port}|${it.peerId ?: ""}" }).apply()
            EdgeState.savedWan.value = l
        }

        fun removeSaved(ctx: Context, a: SavedAddr) = writeSaved(ctx, saved(ctx).filterNot { it.host == a.host && it.port == a.port })

        /** [wan] = over the internet: remembered, dialled outbound, and re-dialled forever. */
        fun connect(ctx: Context, ip: String, port: Int, wan: Boolean) {
            val host = ip.trim()
            if (wan) {
                val cur = saved(ctx)
                if (cur.none { it.host == host && it.port == port }) writeSaved(ctx, cur + SavedAddr(host, port))
                EdgeState.status.value = "Connecting to $host…"
                redialer?.kick(0)
            } else io.execute {
                try { node?.connectTo(host, port); EdgeState.status.value = "Connected to $host" }
                catch (e: Exception) { EdgeState.status.value = "Couldn't connect: ${e.message}" }
            }
        }

        fun rename(name: String) { node?.displayName = name }

        fun setPic(dataUrl: String?) = io.execute { node?.setProfilePic(dataUrl) }

        fun chat(peerId: String, text: String, reply: ChatMsg? = null) = io.execute {
            try {
                val r = reply?.let { Reply(it.id, it.text.take(200), if (it.mine) "me" else "them") }
                val id = node!!.sendChat(peerId, text, r)
                val q = reply?.let { QuoteRef(it.id, it.text.take(200), it.mine) }
                EdgeState.messages.update { it + ChatMsg(id, peerId, text, true, replyTo = q) }
            } catch (e: Exception) { EdgeState.status.value = "Send failed: ${e.message}" }
        }

        fun react(peerId: String, msgId: String, emoji: String) = io.execute {
            val removed = EdgeState.toggleReaction(peerId, msgId, emoji)
            runCatching { node?.sendReaction(peerId, msgId, emoji, removed) }
        }

        fun sendFile(ctx: Context, peerId: String, uri: Uri) = io.execute {
            var name = "file"; var size = -1L
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            if (size < 0) { EdgeState.status.value = "Can't send $name: unknown size"; return@execute }
            val tmpId = EdgeNode.randomHex(4)
            EdgeState.upsert(Transfer(tmpId, peerId, name, 0, size, sending = true, uri = uri.toString()))
            try {
                ctx.contentResolver.openInputStream(uri)!!.use { node!!.sendFile(peerId, name, size, mime, it, fileId = tmpId) }
                EdgeState.upsert(Transfer(tmpId, peerId, name, size, size, true, finished = true))
                EdgeState.streakSent(peerId)
            } catch (e: Exception) {
                val cancelled = e.message?.contains("cancel", true) == true
                EdgeState.upsert(Transfer(tmpId, peerId, name, 0, size, true, error = if (cancelled) "cancelled" else e.message ?: "failed"))
            }
        }

        fun sendFolder(ctx: Context, peerId: String, tree: Uri) = io.execute {
            val root = DocumentFile.fromTreeUri(ctx, tree)
            if (root == null || !root.isDirectory) { EdgeState.status.value = "Can't read that folder"; return@execute }
            val entries = ArrayList<FolderEntry>()
            fun walk(dir: DocumentFile, prefix: String) {
                for (f in dir.listFiles()) {
                    val n = f.name ?: continue
                    if (f.isDirectory) {
                        if (f.listFiles().isEmpty()) entries.add(FolderEntry(prefix + n, 0, isDir = true) { java.io.ByteArrayInputStream(ByteArray(0)) })
                        else walk(f, "$prefix$n/")
                    }
                    else if (f.isFile) {
                        val u = f.uri
                        entries.add(FolderEntry(prefix + n, f.length()) { ctx.contentResolver.openInputStream(u)!! })
                    }
                }
            }
            walk(root, "")
            val name = root.name ?: "folder"
            if (entries.isEmpty()) { EdgeState.status.value = "That folder is empty"; return@execute }
            val id = EdgeNode.randomHex(4)
            val total = entries.sumOf { it.size }
            val nFiles = entries.count { !it.isDir }
            EdgeState.upsert(Transfer(id, peerId, name, 0, total, true, isFolder = true, fileCount = nFiles))
            try {
                node!!.sendFolder(peerId, name, entries, id)
                EdgeState.upsert(Transfer(id, peerId, name, total, total, true, finished = true, isFolder = true, fileCount = nFiles))
                EdgeState.streakSent(peerId)
            } catch (e: Exception) {
                val cancelled = e.message?.contains("cancel", true) == true
                EdgeState.upsert(Transfer(id, peerId, name, 0, total, true, error = if (cancelled) "cancelled" else e.message ?: "failed", isFolder = true, fileCount = nFiles))
            }
        }

        fun pause(t: Transfer) = io.execute {
            if (t.sending) node?.pauseSend(t.peerId, t.fileId) else node?.pauseReceive(t.peerId, t.fileId)
            EdgeState.setPaused(t.fileId, true)
        }

        fun resume(t: Transfer) = io.execute {
            if (t.sending) node?.resumeSend(t.peerId, t.fileId) else node?.resumeReceive(t.peerId, t.fileId)
            EdgeState.setPaused(t.fileId, false)
        }

        /** Only the sender can cancel (the protocol has no receiver-side cancel). */
        fun cancel(t: Transfer) = io.execute { if (t.sending) node?.cancelSend(t.fileId) }

        fun respond(req: FileRequest, accept: Boolean) {
            EdgeState.requests.update { it - req }
            io.execute {
                val n = node ?: return@execute
                if (req.isFolder) { if (accept) n.acceptFolder(req.peerId, req.fileId) else n.rejectFolder(req.peerId, req.fileId) }
                else { if (accept) n.acceptFile(req.peerId, req.fileId) else n.rejectFile(req.peerId, req.fileId) }
            }
        }

        /** Used by the Accept / Decline buttons in a notification. */
        fun respondById(ctx: Context, peerId: String, id: String, accept: Boolean) {
            Notifier.cancelRequest(ctx, id)
            val req = EdgeState.requests.value.firstOrNull { it.peerId == peerId && it.fileId == id } ?: return
            respond(req, accept)
        }
    }

    private var multicast: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.init(this)
        val n = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Edge is running")
            .setContentText("Ready to send and receive on your network")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()
        val type = if (Build.VERSION.SDK_INT >= 34)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(1, n, type)

        if (node != null) return START_STICKY

        val prefs = getSharedPreferences("edge", MODE_PRIVATE)
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Locks are an optimisation; never let a failure here take the service down.
        multicast = runCatching { wifi.createMulticastLock("edge").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        wifiLock = runCatching { wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "edge").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        // Keeps the CPU awake with the screen off so sockets keep being serviced.
        if (prefs.getBoolean("keepalive", true)) {
            wakeLock = runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "edge:conn").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }

        // When the phone moves to another Wi-Fi network its old sockets are dead; re-dial everything.
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            var first = true
            netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (first) { first = false; return }
                    EdgeState.status.value = "Network changed, reconnecting…"
                    node?.resetConnections()
                    redialer?.kick(2000)
                }
            }
            cm.registerDefaultNetworkCallback(netCallback!!)
        }

        EdgeState.savedWan.value = saved(this)
        val id = prefs.getString("id", null) ?: EdgeNode.randomHex(6).also { prefs.edit().putString("id", it).apply() }
        val ctx = applicationContext
        node = EdgeNode(
            displayName = prefs.getString("name", null) ?: Build.MODEL,
            myId = id,
            listener = Listener(ctx),
            sinks = SinkFactory { name, _, mime -> MediaStoreSink(ctx, name, mime, prefs.getBoolean("gallery", true)) },
            folderSinks = FolderSinkFactory { name, _, _ -> MediaStoreFolderSink(ctx, name) },
        ).also { nn -> nn.myProfilePic = prefs.getString("pic", null); Thread { runCatching { nn.start() }.onFailure { EdgeState.status.value = "Start failed: ${it.message}" } }.start() }
        node?.let { nn ->
            redialer = Redialer(nn, { saved(ctx) }, { a, pid ->
                writeSaved(ctx, saved(ctx).map { if (it.host == a.host && it.port == a.port) it.copy(peerId = pid) else it })
            }, { EdgeState.status.value = it }).also { it.start() }
        }
        EdgeState.status.value = "Looking for devices…"
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { netCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        redialer?.stop(); redialer = null
        node?.stop(); node = null
        runCatching { multicast?.release() }; runCatching { wifiLock?.release() }; runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private class Listener(val ctx: Context) : EdgeListener {
        private fun refresh() {
            EdgeState.peers.value = node?.peers()?.map { UiPeer(it.id, it.name, it.ip, it.connected, it.fingerprint, it.profilePic, it.lan) } ?: emptyList()
        }
        private fun nameOf(peerId: String) = EdgeState.peers.value.firstOrNull { it.id == peerId }?.name ?: "Someone"
        private val hidden get() = !EdgeState.appVisible.value

        override fun onPeerConnected(p: Peer) { refresh(); EdgeState.status.value = "Connected to ${p.name}" }
        override fun onPeerDisconnected(peerId: String) = refresh()
        override fun onPeerUpdated(p: Peer) = refresh()

        override fun onChat(peerId: String, msgId: String, text: String, timestamp: Long, replyTo: Reply?) {
            // "from" is relative to the sender: "them" means the sender quoted one of MY messages.
            val q = replyTo?.let { QuoteRef(it.id, it.text, byMe = it.from == "them") }
            EdgeState.messages.update { it + ChatMsg(msgId, peerId, text, false, timestamp, q) }
            if (Notifier.shouldNotify(peerId)) Notifier.message(ctx, peerId, nameOf(peerId), text)
        }

        override fun onReaction(peerId: String, msgId: String, emoji: String, remove: Boolean) =
            EdgeState.theirReaction(peerId, msgId, emoji, remove)

        override fun onFileRequest(peerId: String, fileId: String, name: String, size: Long, mime: String) {
            val r = FileRequest(peerId, fileId, name, size)
            EdgeState.requests.update { it + r }
            if (hidden) Notifier.fileRequest(ctx, r, nameOf(peerId))
        }

        override fun onFolderRequest(peerId: String, folderId: String, name: String, totalSize: Long, fileCount: Int) {
            val r = FileRequest(peerId, folderId, name, totalSize, isFolder = true, fileCount = fileCount)
            EdgeState.requests.update { it + r }
            if (hidden) Notifier.fileRequest(ctx, r, nameOf(peerId))
        }

        override fun onProgress(peerId: String, fileId: String, name: String, done: Long, total: Long, sending: Boolean) =
            EdgeState.progress(peerId, fileId, name, done, total, sending)

        override fun onFolderProgress(peerId: String, folderId: String, name: String, done: Long, total: Long, currentFile: String, sending: Boolean) =
            EdgeState.progress(peerId, folderId, name, done, total, sending, isFolder = true, detail = currentFile)

        override fun onFileReceived(peerId: String, fileId: String, name: String, size: Long) {
            EdgeState.streakReceived(peerId)
            MediaStoreSink.saved.remove(name)?.let { EdgeState.setUri(fileId, it.toString()) }
            EdgeState.status.value = "Saved $name"
            if (Notifier.shouldNotify(peerId)) Notifier.received(ctx, peerId, nameOf(peerId), name)
        }

        override fun onFolderReceived(peerId: String, folderId: String, name: String, totalSize: Long, fileCount: Int) {
            EdgeState.streakReceived(peerId)
            EdgeState.upsert(Transfer(folderId, peerId, name, totalSize, totalSize, false, finished = true, isFolder = true, fileCount = fileCount, detail = "Saved in Downloads/Edge/$name"))
            EdgeState.status.value = "Saved folder $name"
            if (Notifier.shouldNotify(peerId)) Notifier.received(ctx, peerId, nameOf(peerId), "$name ($fileCount files)")
        }

        override fun onTransferPaused(peerId: String, transferId: String, paused: Boolean) = EdgeState.setPaused(transferId, paused)

        override fun onFileCancelled(peerId: String, fileId: String, name: String) {
            EdgeState.upsert(Transfer(fileId, peerId, name, 0, 0, false, error = "cancelled"))
        }
        override fun onLog(msg: String) { EdgeState.status.value = msg }
    }
}
