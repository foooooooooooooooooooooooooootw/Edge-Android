package edge.core

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Where a received folder is written. One call to [openFile] per file in the archive. */
interface IncomingFolderSink {
    fun openFile(relPath: String, size: Long): OutputStream
    fun closeFile()
    fun commit()
    fun abort()
    /** An empty directory in the archive. Sinks that can't represent empty folders may ignore it. */
    fun makeDir(relPath: String) {}
}

fun interface FolderSinkFactory { fun open(folderName: String, totalSize: Long, fileCount: Int): IncomingFolderSink }

/** One file to send as part of a folder. [open] is called once, when the file's turn comes. */
class FolderEntry(val relPath: String, val size: Long, val isDir: Boolean = false, val open: () -> InputStream)

/** The same bare-bones ustar the desktop writes: 512-byte header, data, pad to 512, two zero blocks at the end. */
object Tar {
    private const val NAME_MAX = 99      // keeps name+NUL inside the 100-byte field

    /** Names that don't fit in 99 bytes are flattened to long/<hash>-<name>, keeping the extension. */
    fun fitName(rel: String): String {
        if (rel.toByteArray(Charsets.UTF_8).size <= NAME_MAX) return rel
        val base = rel.substringAfterLast('/')
        val ext = base.substringAfterLast('.', "").take(12).let { if (it.isEmpty()) "" else ".$it" }
        val stem = if (ext.isEmpty()) base else base.removeSuffix("." + base.substringAfterLast('.'))
        val h = MessageDigest.getInstance("SHA-1").digest(rel.toByteArray()).hex().take(8)
        for (k in stem.length downTo 0) {
            val cand = "long/$h-${stem.take(k)}$ext"
            if (cand.toByteArray(Charsets.UTF_8).size <= NAME_MAX) return cand
        }
        return "long/$h"
    }

    /** ustar split: long paths go partly into the 155-byte prefix field, divided at a '/'. */
    fun splitName(rel: String): Pair<String, String> {
        fun n(x: String) = x.toByteArray(Charsets.UTF_8).size
        if (n(rel) <= NAME_MAX) return "" to rel
        var i = rel.lastIndexOf('/')
        while (i > 0) {
            val pre = rel.substring(0, i); val nm = rel.substring(i + 1)
            if (n(pre) <= 155 && n(nm) <= NAME_MAX) return pre to nm
            i = rel.lastIndexOf('/', i - 1)
        }
        return "" to fitName(rel)
    }

    fun header(relPath: String, size: Long, isDir: Boolean = false): ByteArray {
        val h = ByteArray(512)
        val (prefix, name) = splitName(relPath)
        val nb = (if (isDir) "$name/" else name).toByteArray(Charsets.UTF_8)
        System.arraycopy(nb, 0, h, 0, minOf(nb.size, NAME_MAX + 1))
        if (prefix.isNotEmpty()) { val pb = prefix.toByteArray(Charsets.UTF_8); System.arraycopy(pb, 0, h, 345, pb.size) }
        octal(h, 100, 8, 420L)                                   // 0644
        octal(h, 108, 8, 0); octal(h, 116, 8, 0)
        octal(h, 124, 12, size)
        octal(h, 136, 12, System.currentTimeMillis() / 1000)
        for (i in 148 until 156) h[i] = 0x20                     // checksum placeholder
        h[156] = (if (isDir) '5' else '0').code.toByte()
        val magic = "ustar  \u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, h, 257, magic.size)
        var sum = 0L
        for (b in h) sum += (b.toInt() and 0xff)
        octal(h, 148, 8, sum)
        return h
    }

    private fun octal(h: ByteArray, off: Int, len: Int, v: Long) {
        val s = v.toString(8).padStart(len - 1, '0')
        for (i in s.indices) h[off + i] = s[i].code.toByte()
    }
}

/** Streaming tar parser: feed it the bytes of the binary frames in order. */
class TarReader(private val sink: IncomingFolderSink, private val onFile: (String) -> Unit = {}) {
    private val hdr = ByteArray(512)
    private var hdrFill = 0
    private var dataLeft = 0L
    private var padLeft = 0L
    private var cur: OutputStream? = null
    private var curSize = 0L

    fun feed(b: ByteArray, off0: Int, len0: Int) {
        var off = off0
        var left = len0
        while (left > 0) {
            if (dataLeft > 0) {
                val n = minOf(left.toLong(), dataLeft).toInt()
                cur?.write(b, off, n)
                off += n; left -= n; dataLeft -= n
                if (dataLeft == 0L) endFile()
                continue
            }
            if (padLeft > 0) {
                val n = minOf(left.toLong(), padLeft).toInt()
                off += n; left -= n; padLeft -= n
                continue
            }
            val n = minOf(left, 512 - hdrFill)
            System.arraycopy(b, off, hdr, hdrFill, n)
            hdrFill += n; off += n; left -= n
            if (hdrFill == 512) { hdrFill = 0; header() }
        }
    }

    private fun str(off: Int, len: Int): String {
        var end = off
        while (end < off + len && hdr[end].toInt() != 0) end++
        return String(hdr, off, end - off, Charsets.UTF_8)
    }

    private fun header() {
        if (hdr.all { it.toInt() == 0 }) return                  // end-of-archive block
        var name = str(0, 100)
        if (str(257, 5) == "ustar") str(345, 155).let { if (it.isNotEmpty()) name = "$it/$name" }
        val size = str(124, 12).trim().ifEmpty { "0" }.toLongOrNull(8) ?: 0L
        val type = hdr[156].toInt().toChar()
        val regular = type == '0' || type.code == 0
        if (regular && !name.endsWith("/")) {
            val safe = name.replace('\\', '_').split('/').map { it.replace("..", "_") }.filter { it.isNotEmpty() && it != "." }.joinToString("/").ifEmpty { "file" }
            onFile(safe)
            curSize = size
            cur = sink.openFile(safe, size)
            if (size == 0L) endFile() else dataLeft = size
        } else {
            if (type == '5' || name.endsWith("/")) {
                val safe = name.replace('\\', '_').split('/').map { it.replace("..", "_") }.filter { it.isNotEmpty() && it != "." }.joinToString("/")
                if (safe.isNotEmpty()) sink.makeDir(safe)
            }
            dataLeft = 0                                          // directories carry no data; other entry kinds are skipped
            padLeft = size + ((512 - size % 512) % 512)
        }
    }

    /** Closes the current file, then the next bytes are its padding up to the 512-byte boundary. */
    private fun endFile() {
        sink.closeFile(); cur = null
        padLeft = (512 - curSize % 512) % 512
    }
}
