package edge.core

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** An internet (WAN) device the user asked to keep connected: `host` may be an IP or a dynamic-DNS name. */
data class SavedAddr(val host: String, val port: Int, val peerId: String? = null)

/**
 * Keeps saved WAN addresses connected. LAN peers are found by discovery and redialled by [EdgeNode];
 * a device on the internet can't be discovered, so we dial it ourselves, forever, with a gentle backoff.
 * Dialling is outbound only, which is what makes it work from a phone on mobile data behind carrier NAT.
 */
class Redialer(
    private val node: EdgeNode,
    private val entries: () -> List<SavedAddr>,
    /** Called after a successful dial so the caller can remember which peer id lives at that address. */
    private val learned: (SavedAddr, String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val ex: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "edge-redial").apply { isDaemon = true } }
    private val failures = HashMap<String, Int>()
    @Volatile private var running = false
    private var scheduled = false

    fun start() { running = true; kick(0) }
    fun stop() { running = false; ex.shutdownNow() }

    /** Try soon (after a network change, after the user adds an address…). Failure counters reset. */
    fun kick(delayMs: Long = 1000) {
        synchronized(this) { failures.clear(); if (scheduled) return; scheduled = true }
        schedule(delayMs)
    }

    private fun schedule(ms: Long) { if (running) try { ex.schedule({ tick() }, ms, TimeUnit.MILLISECONDS) } catch (_: Exception) {} }

    private fun tick() {
        synchronized(this) { scheduled = false }
        if (!running) return
        var next = 60_000L
        for (a in entries()) {
            val key = "${a.host}:${a.port}"
            if (a.peerId != null && node.peers().any { it.id == a.peerId && it.connected }) { failures.remove(key); continue }
            val n = failures[key] ?: 0
            // 5 s, 10 s, 20 s, 40 s, then once a minute.
            val wait = minOf(5_000L shl minOf(n, 4), 60_000L)
            if (n > 0 && lastTry[key]?.let { System.currentTimeMillis() - it < wait } == true) { next = minOf(next, wait); continue }
            lastTry[key] = System.currentTimeMillis()
            try {
                val p = node.connectTo(a.host, a.port, lan = false)
                failures.remove(key)
                if (a.peerId != p.id) learned(a, p.id)
            } catch (e: Exception) {
                failures[key] = n + 1
                log("Can't reach ${a.host}:${a.port} (${e.message})")
                next = minOf(next, 5_000L shl minOf(n + 1, 4))
            }
        }
        synchronized(this) { if (!scheduled) { scheduled = true; schedule(minOf(next, 30_000L)) } }
    }

    private val lastTry = HashMap<String, Long>()
}
