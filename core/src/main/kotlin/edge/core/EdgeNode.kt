package edge.core

import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.*

const val DISCOVERY_PORT = 42068
const val DEFAULT_DATA_PORT = 42069
const val DISCOVERY_INTERVAL_MS = 3000L
const val CHUNK_SIZE = 512 * 1024
const val HANDSHAKE_TIMEOUT_MS = 5000
const val FILE_ACCEPT_TIMEOUT_MIN = 10L

class Peer(
    val id: String, var name: String, var ip: String, var port: Int, var lan: Boolean,
    @Volatile var framed: Framed?, var fingerprint: String,
) {
    val connected get() = framed != null
    /** data: URL (jpeg/png/gif/webp) or null. */
    @Volatile var profilePic: String? = null
}

/** Where incoming file bytes go. On Android this is backed by MediaStore / SAF. */
interface IncomingSink {
    val out: OutputStream
    fun commit()
    fun abort()
}

fun interface SinkFactory { fun open(name: String, size: Long, mime: String): IncomingSink }

/** A quoted message. [from] is "me"/"them" from the SENDER's point of view, as the desktop sends it. */
data class Reply(val id: String, val text: String, val from: String)

interface EdgeListener {
    fun onPeerConnected(p: Peer) {}
    fun onPeerDisconnected(peerId: String) {}
    fun onPeerUpdated(p: Peer) {}
    fun onChat(peerId: String, msgId: String, text: String, timestamp: Long, replyTo: Reply?) {}
    fun onReaction(peerId: String, msgId: String, emoji: String, remove: Boolean) {}
    /** Ask the user, then call [EdgeNode.acceptFolder] or [EdgeNode.rejectFolder]. */
    fun onFolderRequest(peerId: String, folderId: String, name: String, totalSize: Long, fileCount: Int) {}
    fun onFolderProgress(peerId: String, folderId: String, name: String, done: Long, total: Long, currentFile: String, sending: Boolean) {}
    fun onFolderReceived(peerId: String, folderId: String, name: String, totalSize: Long, fileCount: Int) {}
    /** The other side (or we) paused/resumed a transfer. */
    fun onTransferPaused(peerId: String, transferId: String, paused: Boolean) {}
    /** Ask the user, then call [EdgeNode.acceptFile] or [EdgeNode.rejectFile]. */
    fun onFileRequest(peerId: String, fileId: String, name: String, size: Long, mime: String) {}
    fun onProgress(peerId: String, fileId: String, name: String, done: Long, total: Long, sending: Boolean) {}
    fun onFileReceived(peerId: String, fileId: String, name: String, size: Long) {}
    fun onFileCancelled(peerId: String, fileId: String, name: String) {}
    fun onLog(msg: String) {}
}

class EdgeNode(
    var displayName: String,
    val myId: String = randomHex(6),
    private val listener: EdgeListener,
    private val sinks: SinkFactory,
    private val discoveryPort: Int = DISCOVERY_PORT,
    private val preferredDataPort: Int = DEFAULT_DATA_PORT,
    private val folderSinks: FolderSinkFactory? = null,
) {
    companion object {
        private val rnd = SecureRandom()
        fun randomHex(bytes: Int) = ByteArray(bytes).also { rnd.nextBytes(it) }.hex()
    }

    @Volatile var dataPort = preferredDataPort; private set
    /** Our avatar as a data: URL; sent in the handshake and via profile_update. */
    @Volatile var myProfilePic: String? = null
    private val peers = ConcurrentHashMap<String, Peer>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null

    private class Incoming(val peerId: String, val name: String, val size: Long, val sink: IncomingSink, var received: Long = 0, var lastReport: Long = 0)
    private val inbound = ConcurrentHashMap<String, Incoming>()
    private class FolderIn(val peerId: String, val name: String, val total: Long, val count: Int, val sink: IncomingFolderSink, val reader: TarReader) {
        @Volatile var received = 0L; @Volatile var current = ""; var lastReport = 0L; @Volatile var failed = false
    }
    private val inboundFolders = ConcurrentHashMap<String, FolderIn>()
    private val pending = ConcurrentHashMap<String, Pair<String, CompletableFuture<Boolean>>>()  // fileId -> (peerId, future)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val paused = ConcurrentHashMap.newKeySet<String>()

    fun peers(): List<Peer> = peers.values.toList()

    // ───────────── lifecycle ─────────────

    fun start(discovery: Boolean = true) {
        running = true
        var port = preferredDataPort
        while (true) {
            try { server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }; break }
            catch (e: BindException) { if (++port > preferredDataPort + 20) throw e }
        }
        dataPort = port
        listener.onLog("TCP :$port")
        pool.execute { acceptLoop() }
        if (discovery) startDiscovery()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        peers.values.forEach { it.framed?.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        val s = server ?: return
        while (running) {
            val sock = try { s.accept() } catch (_: Exception) { return }
            pool.execute { handleIncoming(sock) }
        }
    }

    // ───────────── connections ─────────────

    private fun newFramed(sock: Socket) = Framed(sock.apply { tcpNoDelay = true; keepAlive = true; soTimeout = HANDSHAKE_TIMEOUT_MS })

    private fun handleIncoming(sock: Socket) {
        val f = try { newFramed(sock) } catch (_: Exception) { return }
        try {
            val first = f.read() ?: return
            val msg = Json.parse(String(first.payload, Charsets.UTF_8))
            if (first.type != FRAME_JSON || msg["type"] != "handshake") { f.close(); return }
            val hs = Handshake()
            f.sendJson(handshakeMsg(hs, null))                          // reply is plaintext...
            val (key, fp) = hs.finish(msg["ecdhPubKey"] as String, num(msg["kdf"]).toInt())
            f.aes = Aes(key)                                            // ...everything after is encrypted
            sock.soTimeout = 0
            val id = msg["id"] as String
            val ip = sock.inetAddress.hostAddress.removePrefix("::ffff:")
            val peer = Peer(id, msg["name"] as? String ?: id, ip, num(msg["listenPort"]).toInt(), (msg["lan"] as? Boolean) ?: true, f, fp)
            peer.profilePic = msg["profilePic"] as? String
            if (register(peer, incoming = true)) readLoop(peer, f) else f.close()
        } catch (e: Exception) { f.close() }
    }

    /** Connect to ip:port (discovery or manual entry). Blocks until the handshake completes. */
    fun connectTo(ip: String, port: Int, lan: Boolean = true): Peer {
        val sock = Socket()
        sock.connect(InetSocketAddress(ip, port), HANDSHAKE_TIMEOUT_MS)
        val f = newFramed(sock)
        try {
            val hs = Handshake()
            f.sendJson(handshakeMsg(hs, lan))
            val reply = f.read() ?: throw java.io.IOException("closed during handshake")
            val msg = Json.parse(String(reply.payload, Charsets.UTF_8))
            if (msg["type"] != "handshake") throw java.io.IOException("bad handshake")
            val (key, fp) = hs.finish(msg["ecdhPubKey"] as String, num(msg["kdf"]).toInt())
            f.aes = Aes(key)
            sock.soTimeout = 0
            val id = msg["id"] as String
            val peer = Peer(id, msg["name"] as? String ?: id, ip, port, lan, f, fp)
            peer.profilePic = msg["profilePic"] as? String
            if (register(peer, incoming = false)) pool.execute { readLoop(peer, f) } else f.close()
            return peers[id] ?: peer
        } catch (e: Exception) { f.close(); throw e }
    }

    private fun handshakeMsg(hs: Handshake, lan: Boolean?): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["type"] = "handshake"; m["id"] = myId; m["name"] = displayName; m["listenPort"] = dataPort
        if (lan != null) m["lan"] = lan
        m["ecdhPubKey"] = hs.pubKeyHex; m["kdf"] = 2; m["profilePic"] = myProfilePic
        return m
    }

    private fun num(v: Any?): Long = when (v) { is Long -> v; is Double -> v.toLong(); is Int -> v.toLong(); else -> 0L }

    /** Duplicate-connection rule from the desktop client; both ends evaluate it identically. */
    @Synchronized
    private fun register(p: Peer, incoming: Boolean): Boolean {
        val ex = peers[p.id]
        if (ex != null && ex.connected) {
            val iShouldBeConnector = myId > p.id
            val keepNew = if (iShouldBeConnector) !incoming else incoming
            if (!keepNew) return false
            val old = ex.framed
            ex.framed = p.framed; ex.ip = p.ip; ex.fingerprint = p.fingerprint; ex.profilePic = p.profilePic
            old?.close()
            return true
        }
        if (ex != null) {
            ex.framed = p.framed; ex.ip = p.ip; ex.port = p.port; ex.lan = p.lan; ex.fingerprint = p.fingerprint; ex.name = p.name; ex.profilePic = p.profilePic
            listener.onPeerConnected(ex)
        } else {
            peers[p.id] = p
            listener.onPeerConnected(p)
        }
        return true
    }

    private fun readLoop(peer: Peer, f: Framed) {
        var reason = "closed by the other side"
        try {
            while (true) {
                val fr = f.read() ?: break
                if (fr.type == FRAME_BINARY) onBinary(peer, fr.payload) else onJson(peer, Json.parse(String(fr.payload, Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            reason = e.message ?: e.javaClass.simpleName
        } finally {
            f.close()
            val p = peers[peer.id]
            if (p != null && p.framed === f) {
                p.framed = null
                pending.entries.removeIf { (_, v) -> if (v.first == peer.id) { v.second.completeExceptionally(java.io.IOException("Peer disconnected")); true } else false }
                inbound.entries.removeIf { (_, v) -> if (v.peerId == peer.id) { runCatching { v.sink.abort() }; true } else false }
                inboundFolders.entries.removeIf { (_, v) -> if (v.peerId == peer.id) { runCatching { v.sink.abort() }; true } else false }
                listener.onLog("Disconnected from ${p.name}: $reason")
                listener.onPeerDisconnected(peer.id)
                scheduleReconnect(peer.id)
            }
        }
    }

    /** After an unexpected drop, redial the last known address with a short backoff. */
    private fun scheduleReconnect(id: String) {
        if (!running || !connecting.add(id)) return
        pool.execute {
            try {
                var delay = 1000L
                repeat(8) {
                    if (!running) return@execute
                    try { Thread.sleep(delay) } catch (_: InterruptedException) { return@execute }
                    val p = peers[id] ?: return@execute
                    if (p.connected) return@execute
                    try { connectTo(p.ip, p.port, p.lan); return@execute } catch (_: Exception) {}
                    delay = minOf(delay * 2, 8000L)
                }
            } finally { connecting.remove(id) }
        }
    }

    /** The connection to [peerId], dialling once more if it just dropped. */
    private fun framedFor(peerId: String): Framed {
        val p = peers[peerId] ?: throw java.io.IOException("Unknown device")
        p.framed?.let { return it }
        try { connectTo(p.ip, p.port, p.lan) }
        catch (e: Exception) { throw java.io.IOException("${p.name} is offline (${e.message ?: "unreachable"})") }
        return peers[peerId]?.framed ?: throw java.io.IOException("${p.name} is offline")
    }

    /** Drop every connection so they are re-established, e.g. after the phone switches Wi-Fi networks. */
    fun resetConnections() { peers.values.forEach { it.framed?.close() } }

    // ───────────── receiving ─────────────

    private fun onJson(peer: Peer, m: Map<String, Any?>) {
        val f = peer.framed ?: return
        when (m["type"]) {
            "chat" -> {
                val r = (m["replyTo"] as? Map<*, *>)?.let { Reply(it["id"] as? String ?: "", it["text"] as? String ?: "", it["from"] as? String ?: "them") }
                listener.onChat(peer.id, m["id"] as? String ?: "", m["text"] as? String ?: "", num(m["timestamp"]), r)
            }
            "reaction" -> listener.onReaction(peer.id, m["msgId"] as? String ?: return, m["emoji"] as? String ?: return, m["remove"] == true)
            "profile_update" -> { peer.profilePic = (m["profilePic"] as? String)?.takeIf { it.length < 8_000_000 }; listener.onPeerUpdated(peer) }
            "file_request" -> listener.onFileRequest(peer.id, m["fileId"] as String, m["name"] as String, num(m["size"]), m["mime"] as? String ?: "application/octet-stream")
            "file_accept" -> pending.remove(m["fileId"])?.second?.complete(true)
            "file_reject" -> pending.remove(m["fileId"])?.second?.complete(false)
            "file_start" -> {
                val id = m["fileId"] as String
                val name = m["name"] as String
                val size = num(m["size"])
                val sink = sinks.open(name, size, m["mime"] as? String ?: "application/octet-stream")
                inbound[id] = Incoming(peer.id, name, size, sink)
                listener.onProgress(peer.id, id, name, 0, size, false)
            }
            "file_end" -> inbound.remove(m["fileId"])?.let { t ->
                t.sink.commit()
                listener.onProgress(peer.id, m["fileId"] as String, t.name, t.size, t.size, false)
                listener.onFileReceived(peer.id, m["fileId"] as String, t.name, t.size)
            }
            "transfer_cancel" -> {
                val id = m["transferId"] as String
                inbound.remove(id)?.let { t -> runCatching { t.sink.abort() }; listener.onFileCancelled(peer.id, id, t.name) }
                inboundFolders.remove(id)?.let { t -> runCatching { t.sink.abort() }; listener.onFileCancelled(peer.id, id, t.name) }
            }
            // receiver -> sender: stop / start sending chunks
            "transfer_pause" -> { val id = m["transferId"] as String; paused.add(id); listener.onTransferPaused(peer.id, id, true) }
            "transfer_resume" -> { val id = m["transferId"] as String; paused.remove(id); listener.onTransferPaused(peer.id, id, false) }
            // sender -> receiver: display hints
            "transfer_paused_by_sender" -> listener.onTransferPaused(peer.id, m["transferId"] as String, true)
            "transfer_resumed_by_sender" -> listener.onTransferPaused(peer.id, m["transferId"] as String, false)

            "folder_request" ->
                if (folderSinks == null) f.sendJson(mapOf("type" to "folder_reject", "folderId" to m["folderId"]))
                else listener.onFolderRequest(peer.id, m["folderId"] as String, m["name"] as? String ?: "folder", num(m["totalSize"]), num(m["fileCount"]).toInt())
            "folder_accept" -> pending.remove(m["folderId"])?.second?.complete(true)
            "folder_reject" -> pending.remove(m["folderId"])?.second?.complete(false)
            "folder_start" -> {
                val factory = folderSinks ?: return
                val id = m["folderId"] as String
                val name = m["name"] as? String ?: "folder"
                val total = num(m["totalSize"]); val count = num(m["fileCount"]).toInt()
                val sink = factory.open(name, total, count)
                lateinit var fin: FolderIn
                fin = FolderIn(peer.id, name, total, count, sink, TarReader(sink) { fin.current = it })
                inboundFolders[id] = fin
                listener.onFolderProgress(peer.id, id, name, 0, total, "", false)
            }
            "folder_end" -> inboundFolders.remove(m["folderId"])?.let { t ->
                val id = m["folderId"] as String
                if (t.failed) { runCatching { t.sink.abort() }; listener.onFileCancelled(peer.id, id, t.name) }
                else {
                    t.sink.commit()
                    listener.onFolderProgress(peer.id, id, t.name, t.total, t.total, "", false)
                    listener.onFolderReceived(peer.id, id, t.name, t.total, t.count)
                }
            }
            else -> {}   // profile_update handled above; unknown types are ignored like the desktop does
        }
    }

    private fun onBinary(peer: Peer, p: ByteArray) {
        if (p.size < 4) return
        val id = p.copyOfRange(0, 4).hex()
        val t = inbound[id]
        if (t == null) { inboundFolders[id]?.let { onFolderBytes(peer, id, it, p) }; return }
        t.sink.out.write(p, 4, p.size - 4)
        t.received += p.size - 4
        val now = System.nanoTime()
        if (now - t.lastReport > 100_000_000L) {      // ~10 UI updates / s
            t.lastReport = now
            listener.onProgress(peer.id, id, t.name, t.received, t.size, false)
        }
    }

    private fun onFolderBytes(peer: Peer, id: String, t: FolderIn, p: ByteArray) {
        if (t.failed) return
        try { t.reader.feed(p, 4, p.size - 4) }
        catch (e: Exception) { t.failed = true; listener.onLog("Folder ${t.name} failed: ${e.message}"); return }
        t.received += p.size - 4
        val now = System.nanoTime()
        if (now - t.lastReport > 100_000_000L) {
            t.lastReport = now
            listener.onFolderProgress(peer.id, id, t.name, minOf(t.received, t.total), t.total, t.current, false)
        }
    }

    /** Change our avatar and tell everyone who is connected. Pass null to remove it. */
    fun setProfilePic(dataUrl: String?) {
        myProfilePic = dataUrl
        for (p in peers.values) try { p.framed?.sendJson(mapOf("type" to "profile_update", "profilePic" to dataUrl)) } catch (_: Exception) {}
    }

    fun acceptFile(peerId: String, fileId: String) { peers[peerId]?.framed?.sendJson(mapOf("type" to "file_accept", "fileId" to fileId)) }
    fun rejectFile(peerId: String, fileId: String) { peers[peerId]?.framed?.sendJson(mapOf("type" to "file_reject", "fileId" to fileId)) }

    fun acceptFolder(peerId: String, folderId: String) { peers[peerId]?.framed?.sendJson(mapOf("type" to "folder_accept", "folderId" to folderId)) }
    fun rejectFolder(peerId: String, folderId: String) { peers[peerId]?.framed?.sendJson(mapOf("type" to "folder_reject", "folderId" to folderId)) }

    fun sendReaction(peerId: String, msgId: String, emoji: String, remove: Boolean) {
        framedFor(peerId).sendJson(mapOf("type" to "reaction", "msgId" to msgId, "emoji" to emoji, "remove" to remove))
    }

    // Pause / resume. As in the desktop: the RECEIVER asks the sender to stop (transfer_pause) and the SENDER
    // can pause itself (and tells the receiver with a *_by_sender hint so its UI updates).
    fun pauseReceive(peerId: String, id: String) { peers[peerId]?.framed?.sendJson(mapOf("type" to "transfer_pause", "transferId" to id)) }
    fun resumeReceive(peerId: String, id: String) { peers[peerId]?.framed?.sendJson(mapOf("type" to "transfer_resume", "transferId" to id)) }
    fun pauseSend(peerId: String, id: String) { paused.add(id); peers[peerId]?.framed?.sendJson(mapOf("type" to "transfer_paused_by_sender", "transferId" to id)) }
    fun resumeSend(peerId: String, id: String) { paused.remove(id); peers[peerId]?.framed?.sendJson(mapOf("type" to "transfer_resumed_by_sender", "transferId" to id)) }

    // ───────────── sending ─────────────

    fun sendChat(peerId: String, text: String, replyTo: Reply? = null): String {
        val f = framedFor(peerId)
        val id = randomHex(4)
        val m = linkedMapOf<String, Any?>("type" to "chat", "id" to id, "text" to text, "timestamp" to System.currentTimeMillis())
        if (replyTo != null) m["replyTo"] = linkedMapOf("id" to replyTo.id, "text" to replyTo.text, "from" to replyTo.from)
        f.sendJson(m)
        return id
    }

    /** Cancel an outgoing file or folder, including while it is still waiting for the other side to accept. */
    fun cancelSend(id: String) {
        cancelled.add(id)
        pending.remove(id)?.let { (pid, fut) ->
            peers[pid]?.framed?.sendJson(mapOf("type" to "transfer_cancel", "transferId" to id))
            cancelled.remove(id)
            fut.completeExceptionally(java.io.IOException("Cancelled"))
        }
    }

    /** Honours pause and cancel at a chunk boundary. */
    private fun checkpoint(peerId: String, f: Framed, id: String) {
        while (paused.contains(id) && !cancelled.contains(id) && peers[peerId]?.framed === f) Thread.sleep(200)
        if (cancelled.remove(id)) {
            f.sendJson(mapOf("type" to "transfer_cancel", "transferId" to id))
            throw java.io.IOException("Transfer cancelled")
        }
    }

    private fun awaitAccept(id: String, fut: CompletableFuture<Boolean>, what: String): Boolean =
        try { fut.get(FILE_ACCEPT_TIMEOUT_MIN, TimeUnit.MINUTES) }
        catch (e: TimeoutException) { pending.remove(id); throw java.io.IOException("Receiver did not respond") }
        catch (e: ExecutionException) { throw (e.cause ?: e) }

    /**
     * Blocking: request -> wait for accept -> stream -> end. Run it off the UI thread.
     * Throws if rejected, cancelled, timed out or disconnected.
     */
    fun sendFile(peerId: String, name: String, size: Long, mime: String, input: InputStream, fileId: String = randomHex(4)): String {
        val f = framedFor(peerId)
        val fut = CompletableFuture<Boolean>()
        pending[fileId] = peerId to fut
        f.sendJson(mapOf("type" to "file_request", "fileId" to fileId, "name" to name, "size" to size, "mime" to mime))
        if (!awaitAccept(fileId, fut, "file")) throw java.io.IOException("File rejected by receiver")

        f.sendJson(mapOf("type" to "file_start", "fileId" to fileId, "name" to name, "size" to size, "mime" to mime))
        val buf = ByteArray(CHUNK_SIZE)
        var sent = 0L
        var lastReport = 0L
        try {
            while (sent < size) {
                checkpoint(peerId, f, fileId)
                var n = 0
                val want = minOf(CHUNK_SIZE.toLong(), size - sent).toInt()
                while (n < want) { val r = input.read(buf, n, want - n); if (r < 0) break; n += r }
                if (n <= 0) throw java.io.IOException("Source ended early")
                f.sendBinary(fileId, buf, 0, n)
                sent += n
                val now = System.nanoTime()
                if (now - lastReport > 100_000_000L) { lastReport = now; listener.onProgress(peerId, fileId, name, sent, size, true) }
            }
            f.sendJson(mapOf("type" to "file_end", "fileId" to fileId))
            listener.onProgress(peerId, fileId, name, size, size, true)
        } catch (e: java.io.IOException) {
            throw if (peers[peerId]?.framed !== f) java.io.IOException("Connection lost while sending") else e
        } finally { paused.remove(fileId); cancelled.remove(fileId) }
        return fileId
    }

    /**
     * Blocking: folder_request -> wait for accept -> stream a tar archive (like the desktop) -> folder_end.
     * [entries] must list every file; each entry's size must be exact because the tar header carries it.
     */
    fun sendFolder(peerId: String, name: String, entries: List<FolderEntry>, folderId: String = randomHex(4)): String {
        val f = framedFor(peerId)
        val total = entries.sumOf { it.size }
        val fut = CompletableFuture<Boolean>()
        pending[folderId] = peerId to fut
        f.sendJson(mapOf("type" to "folder_request", "folderId" to folderId, "name" to name, "totalSize" to total, "fileCount" to entries.count { !it.isDir }))
        if (!awaitAccept(folderId, fut, "folder")) throw java.io.IOException("Folder rejected by receiver")

        f.sendJson(mapOf("type" to "folder_start", "folderId" to folderId, "name" to name, "totalSize" to total, "fileCount" to entries.count { !it.isDir }))
        val chunk = ByteArray(CHUNK_SIZE)
        var fill = 0
        fun flush() {
            if (fill == 0) return
            checkpoint(peerId, f, folderId)
            f.sendBinary(folderId, chunk, 0, fill)
            fill = 0
        }
        fun put(b: ByteArray, off: Int, len: Int) {
            var o = off; var l = len
            while (l > 0) {
                val n = minOf(l, CHUNK_SIZE - fill)
                System.arraycopy(b, o, chunk, fill, n)
                fill += n; o += n; l -= n
                if (fill == CHUNK_SIZE) flush()
            }
        }
        var done = 0L
        var lastReport = 0L
        val buf = ByteArray(256 * 1024)
        try {
            for (e in entries) {
                checkpoint(peerId, f, folderId)
                val hdr = Tar.header(e.relPath, if (e.isDir) 0 else e.size, e.isDir)
                put(hdr, 0, hdr.size)
                if (e.isDir) continue
                e.open().use { input ->
                    var left = e.size
                    while (left > 0) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) throw java.io.IOException("${e.relPath} changed while sending")
                        put(buf, 0, r); left -= r; done += r
                        val now = System.nanoTime()
                        if (now - lastReport > 100_000_000L) { lastReport = now; listener.onFolderProgress(peerId, folderId, name, done, total, e.relPath, true) }
                    }
                }
                val pad = ((512 - e.size % 512) % 512).toInt()
                if (pad > 0) put(ByteArray(pad), 0, pad)
            }
            val end = ByteArray(1024)
            put(end, 0, end.size)
            flush()
            f.sendJson(mapOf("type" to "folder_end", "folderId" to folderId))
            listener.onFolderProgress(peerId, folderId, name, total, total, "", true)
        } catch (e: java.io.IOException) {
            throw if (peers[peerId]?.framed !== f) java.io.IOException("Connection lost while sending") else e
        } finally { paused.remove(folderId); cancelled.remove(folderId) }
        return folderId
    }

    // ───────────── LAN discovery ─────────────

    private fun startDiscovery() {
        val s = try { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(discoveryPort)) } }
                catch (e: Exception) { listener.onLog("UDP bind failed: ${e.message}"); return }
        udp = s
        pool.execute {
            val buf = ByteArray(2048)
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                try { s.receive(pkt) } catch (_: Exception) { return@execute }
                try {
                    val d = Json.parse(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                    val id = d["id"] as? String ?: continue
                    if (d["service"] != "edge-p2p" || id == myId) continue
                    if (peers[id]?.connected == true || !connecting.add(id)) continue
                    val ip = pkt.address.hostAddress.removePrefix("::ffff:")
                    val port = num(d["port"]).toInt()
                    pool.execute { try { connectTo(ip, port, true) } catch (_: Exception) {} finally { connecting.remove(id) } }
                } catch (_: Exception) {}
            }
        }
        pool.execute {
            while (running) {
                broadcast(s)
                try { Thread.sleep(DISCOVERY_INTERVAL_MS) } catch (_: InterruptedException) { return@execute }
            }
        }
    }

    private fun broadcast(s: DatagramSocket) {
        val msg = Json.stringify(mapOf("service" to "edge-p2p", "id" to myId, "name" to displayName, "port" to dataPort)).toByteArray()
        val targets = linkedSetOf("255.255.255.255")
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { targets.add(it.hostAddress) }
            }
        } catch (_: Exception) {}
        for (t in targets) try { s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(t), discoveryPort)) } catch (_: Exception) {}
    }
}
