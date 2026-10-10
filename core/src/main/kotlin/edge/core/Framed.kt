package edge.core

import java.io.DataInputStream
import java.io.IOException
import java.net.Socket

const val FRAME_JSON = 0
const val FRAME_BINARY = 1
const val MAX_FRAME = 68 * 1024 * 1024

/** Length-prefixed frames over a socket; optionally AES-GCM encrypted once [aes] is set. */
class Framed(val socket: Socket) {
    @Volatile var aes: Aes? = null
    private val din = DataInputStream(socket.getInputStream().buffered(256 * 1024))
    private val out = socket.getOutputStream()
    private val lock = Any()

    /** Blocks for the next frame. Returns null on clean EOF. */
    fun read(): Frame? {
        val len = try { din.readInt() } catch (e: java.io.EOFException) { return null }
        if (len < 1 || len > MAX_FRAME) throw IOException("bad frame length $len")
        val type = din.readUnsignedByte()
        val inner = ByteArray(len - 1)
        din.readFully(inner)
        val a = aes
        val payload = if (a != null) a.open(inner, 0, inner.size) else inner
        return Frame(type, payload)
    }

    fun sendJson(m: Map<String, Any?>) = write(FRAME_JSON, Json.stringify(m).toByteArray(Charsets.UTF_8))

    fun sendBinary(fileIdHex: String, data: ByteArray, off: Int = 0, len: Int = data.size) {
        val p = ByteArray(4 + len)
        System.arraycopy(fileIdHex.unhex(), 0, p, 0, 4)
        System.arraycopy(data, off, p, 4, len)
        write(FRAME_BINARY, p)
    }

    private fun write(type: Int, payload: ByteArray) {
        val a = aes
        val inner = if (a != null) a.seal(payload) else payload
        val buf = ByteArray(5 + inner.size)
        val l = 1 + inner.size
        buf[0] = (l ushr 24).toByte(); buf[1] = (l ushr 16).toByte(); buf[2] = (l ushr 8).toByte(); buf[3] = l.toByte()
        buf[4] = type.toByte()
        System.arraycopy(inner, 0, buf, 5, inner.size)
        synchronized(lock) { out.write(buf); out.flush() }   // blocking write = natural backpressure
    }

    fun close() { try { socket.close() } catch (_: Exception) {} }
}

class Frame(val type: Int, val payload: ByteArray)
