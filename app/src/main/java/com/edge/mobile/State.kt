package com.edge.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class UiPeer(val id: String, val name: String, val ip: String, val connected: Boolean, val fingerprint: String, val profilePic: String? = null, val lan: Boolean = true)

/** A quoted message. [byMe] = the quoted message was written by me (already translated from the wire's sender-relative "from"). */
data class QuoteRef(val id: String, val text: String, val byMe: Boolean)

data class ChatMsg(
    val id: String, val peerId: String, val text: String, val mine: Boolean,
    val ts: Long = System.currentTimeMillis(), val replyTo: QuoteRef? = null,
)

data class Reaction(val emoji: String, val mine: Boolean)
fun reactionKey(peerId: String, msgId: String) = "$peerId:$msgId"

data class Transfer(
    val fileId: String, val peerId: String, val name: String, val done: Long, val total: Long,
    val sending: Boolean, val finished: Boolean = false, val error: String? = null,
    val ts: Long = System.currentTimeMillis(), val uri: String? = null,
    val paused: Boolean = false, val isFolder: Boolean = false, val fileCount: Int = 0, val detail: String? = null,
)

/** Something shared into Edge from another app, waiting for the user to choose a device. */
data class PendingShare(val uris: List<android.net.Uri>, val text: String?)

/** Mirrors the desktop's per-chat streak: count of my files in a row since they last sent one. */
data class Streak(val count: Int = 0, val lastSender: String? = null)

data class FileRequest(val peerId: String, val fileId: String, val name: String, val size: Long, val isFolder: Boolean = false, val fileCount: Int = 0)

/** Process-wide UI state, written by [EdgeService], read by the Compose UI. */
object EdgeState {
    val peers = MutableStateFlow<List<UiPeer>>(emptyList())
    val messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val requests = MutableStateFlow<List<FileRequest>>(emptyList())
    val status = MutableStateFlow("Starting…")
    val pendingShare = MutableStateFlow<PendingShare?>(null)
    val viewer = MutableStateFlow<ViewerItem?>(null)
    val streaks = MutableStateFlow<Map<String, Streak>>(emptyMap())
    val savedWan = MutableStateFlow<List<edge.core.SavedAddr>>(emptyList())
    val reactions = MutableStateFlow<Map<String, List<Reaction>>>(emptyMap())

    // Used to decide whether a notification is needed, and to open the right chat from one.
    val appVisible = MutableStateFlow(false)
    val activePeer = MutableStateFlow<String?>(null)
    val openPeer = MutableStateFlow<String?>(null)

    /** A file I sent finished. Extends my streak, or restarts it at 1 if they sent last. */
    fun streakSent(peerId: String) = streaks.update {
        val s = it[peerId] ?: Streak()
        it + (peerId to if (s.lastSender == "them") Streak(1, "me") else Streak(s.count + 1, "me"))
    }

    /** A file from them arrived: that always breaks my streak. */
    fun streakReceived(peerId: String) = streaks.update { it + (peerId to Streak(0, "them")) }

    /** Replace a transfer (used for terminal states). Keeps the original timestamp and any saved-file uri. */
    fun upsert(t: Transfer) = transfers.update {
        val old = it[t.fileId]
        it + (t.fileId to t.copy(ts = old?.ts ?: t.ts, uri = t.uri ?: old?.uri, fileCount = if (t.fileCount > 0) t.fileCount else old?.fileCount ?: 0))
    }

    /** Progress tick: updates the numbers but keeps paused/error/uri state. */
    fun progress(
        peerId: String, id: String, name: String, done: Long, total: Long, sending: Boolean,
        isFolder: Boolean = false, fileCount: Int = 0, detail: String? = null,
    ) = transfers.update { m ->
        val old = m[id]
        val t = old?.copy(done = done, total = total, finished = old.finished || (done >= total && total > 0), detail = detail ?: old.detail)
            ?: Transfer(id, peerId, name, done, total, sending, finished = done >= total && total > 0, isFolder = isFolder, fileCount = fileCount, detail = detail)
        m + (id to t)
    }

    fun setPaused(id: String, paused: Boolean) = transfers.update { m -> m[id]?.let { m + (id to it.copy(paused = paused)) } ?: m }

    fun setUri(fileId: String, uri: String) = transfers.update { m -> m[fileId]?.let { m + (fileId to it.copy(uri = uri, finished = true)) } ?: m }

    /** Toggle my reaction like the desktop does. Returns true if it was removed (that is the wire's `remove` flag). */
    fun toggleReaction(peerId: String, msgId: String, emoji: String): Boolean {
        var removed = false
        reactions.update { m ->
            val k = reactionKey(peerId, msgId)
            val cur = m[k].orEmpty()
            removed = cur.any { it.mine && it.emoji == emoji }
            m + (k to if (removed) cur.filterNot { it.mine && it.emoji == emoji } else cur + Reaction(emoji, true))
        }
        return removed
    }

    fun theirReaction(peerId: String, msgId: String, emoji: String, remove: Boolean) = reactions.update { m ->
        val k = reactionKey(peerId, msgId)
        val cur = m[k].orEmpty().filterNot { !it.mine && it.emoji == emoji }
        m + (k to if (remove) cur else cur + Reaction(emoji, false))
    }
}
