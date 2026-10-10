import edge.core.*
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.*

/**
 * Talks to a real desktop Edge on 127.0.0.1:42069. Usage: <srcDir> <recvDir>
 * Exercises: chat+reply, reaction, folder both ways, pause/resume both ways, cancel.
 */
fun sha(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).hex().take(16)

fun main(args: Array<String>) {
    val src = File(args[0]); val recv = File(args[1]).also { it.mkdirs() }
    val nodeFolderDone = CountDownLatch(1); val nodeFileDone = CountDownLatch(1)
    lateinit var node: EdgeNode

    val l = object : EdgeListener {
        override fun onPeerConnected(p: Peer) = println("KT connected ${p.name}")
        override fun onChat(peerId: String, msgId: String, text: String, timestamp: Long, replyTo: Reply?) = println("KT chat: $text reply=$replyTo id=$msgId")
        override fun onReaction(peerId: String, msgId: String, emoji: String, remove: Boolean) = println("KT reaction $msgId $emoji remove=$remove")
        override fun onFileRequest(peerId: String, fileId: String, name: String, size: Long, mime: String) { node.acceptFile(peerId, fileId) }
        override fun onFolderRequest(peerId: String, folderId: String, name: String, totalSize: Long, fileCount: Int) {
            println("KT folder-request $name $totalSize $fileCount"); node.acceptFolder(peerId, folderId)
        }
        override fun onProgress(peerId: String, fileId: String, name: String, done: Long, total: Long, sending: Boolean) {
            if (!sending) curRecvId = fileId
            lastDone = done; progressHook?.invoke(done, sending)
        }
        override fun onFileReceived(peerId: String, fileId: String, name: String, size: Long) {
            println("KT received-file $name $size ${sha(File(recv, name))}"); nodeFileDone.countDown()
        }
        override fun onFolderReceived(peerId: String, folderId: String, name: String, totalSize: Long, fileCount: Int) {
            println("KT received-folder $name $fileCount")
            val root = File(recv, name)
            root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path to it }.sortedBy { it.first }.forEach { (rel, f) -> println("KT F ${f.length()} ${sha(f)} ./$rel") }
            nodeFolderDone.countDown()
        }
        override fun onTransferPaused(peerId: String, transferId: String, paused: Boolean) = println("KT sees paused=$paused")
        override fun onLog(msg: String) = println("KT log: $msg")
    }
    node = EdgeNode("AndroidTest", listener = l, preferredDataPort = 42090,
        sinks = { name, _, _ ->
            val fos = FileOutputStream(File(recv, name))
            object : IncomingSink { override val out: OutputStream = fos; override fun commit() = fos.close(); override fun abort() = fos.close() }
        },
        folderSinks = { folderName, _, _ ->
            val root = File(recv, folderName); var cur: OutputStream? = null
            object : IncomingFolderSink {
                override fun openFile(relPath: String, size: Long): OutputStream { val f = File(root, relPath); f.parentFile.mkdirs(); cur = FileOutputStream(f); return cur!! }
                override fun closeFile() { cur?.close(); cur = null }
                override fun commit() {}
                override fun abort() { cur?.close() }
            }
        })
    node.start(discovery = false)
    val peer = node.connectTo("127.0.0.1", 42069)
    Thread.sleep(500)

    // 1. chat with reply + reaction
    node.sendChat(peer.id, "hello with reply ✓", Reply("abcd1234", "the original", "me"))
    node.sendReaction(peer.id, "node-msg-1", "👍", false)

    // 2. folder phone -> desktop
    val tree = File(src, "kt_tree")
    val entries = tree.walkTopDown().filter { it.isFile }.map { f -> FolderEntry(f.relativeTo(tree).path.replace('\\', '/'), f.length()) { f.inputStream() } }.toList()
    node.sendFolder(peer.id, "kt_tree", entries); println("KT sent folder")

    fun send(name: String) { val f = File(src.parentFile, name); f.inputStream().use { node.sendFile(peer.id, f.name, f.length(), "application/octet-stream", it) } }

    // 3. desktop pauses us (handled by the Node script) -> should take >= 2.5 s
    var t0 = System.currentTimeMillis(); send("pause_recv.bin"); println("KT pause_recv took ${System.currentTimeMillis() - t0} ms")

    // 4. we pause ourselves
    var armed = true
    var p1 = -1L; var p2 = -1L
    progressHook = { done, sending ->
        if (sending && armed && done > 5_000_000) {
            armed = false
            Thread {
                node.pauseSend(peer.id, curId); Thread.sleep(400); p1 = lastDone; Thread.sleep(1500); p2 = lastDone
                println("KT self-pause: progress at +0.4s=$p1  at +1.9s=$p2  stalled=${p1 == p2}")
                node.resumeSend(peer.id, curId)
            }.start()
        }
    }
    curId = EdgeNode.randomHex(4)
    t0 = System.currentTimeMillis()
    File(src.parentFile, "pause_send.bin").let { f -> f.inputStream().use { node.sendFile(peer.id, f.name, f.length(), "application/octet-stream", it, fileId = curId) } }
    println("KT pause_send took ${System.currentTimeMillis() - t0} ms")
    progressHook = null

    // 5. cancel mid-transfer
    armed = true
    curId = EdgeNode.randomHex(4)
    progressHook = { done, sending -> if (sending && armed && done > 3_000_000) { armed = false; node.cancelSend(curId) } }
    try { File(src.parentFile, "cancel.bin").let { f -> f.inputStream().use { node.sendFile(peer.id, f.name, f.length(), "application/octet-stream", it, fileId = curId) } }; println("KT cancel FAILED: completed") }
    catch (e: IOException) { println("KT cancel -> '${e.message}'") }
    progressHook = null

    // 6. desktop sends folder + big file; we pause it as receiver for 2 s
    var pausedOnce = false; var seenAtPause = 0L; var seenLater = 0L
    progressHook = { done, sending ->
        if (!sending && done > 10_000_000 && !pausedOnce && nodeFolderDone.count == 0L) {
            pausedOnce = true
            Thread {
                println("KT pausing receive at $done"); node.pauseReceive(peer.id, curRecvId)
                Thread.sleep(600); seenAtPause = lastDone; Thread.sleep(1500); seenLater = lastDone
                println("KT receive-pause: progress at +0.6s=$seenAtPause at +2.1s=$seenLater stalled=${seenAtPause == seenLater}")
                node.resumeReceive(peer.id, curRecvId)
            }.start()
        }
    }
    node.sendChat(peer.id, "RUN_NODE_SEND")
    nodeFolderDone.await(60, TimeUnit.SECONDS)
    nodeFileDone.await(90, TimeUnit.SECONDS)
    Thread.sleep(1000)
    node.stop()
}
@Volatile var lastDone = 0L
@Volatile var progressHook: ((Long, Boolean) -> Unit)? = null
@Volatile var curId = ""
@Volatile var curRecvId = ""
