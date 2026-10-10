package com.edge.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Notifications for messages and files that arrive while the app isn't in front. */
object Notifier {
    const val CH_SERVICE = "edge"
    const val CH_ALERTS = "edge_alerts"

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Messages & files", NotificationManager.IMPORTANCE_HIGH))
    }

    /** Skip the notification when the user is already looking at that chat. */
    fun shouldNotify(peerId: String) = !EdgeState.appVisible.value || EdgeState.activePeer.value != peerId

    private fun openChat(ctx: Context, peerId: String): PendingIntent =
        PendingIntent.getActivity(
            ctx, peerId.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra("peerId", peerId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun post(ctx: Context, id: Int, n: android.app.Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        try { nm.notify(id, n) } catch (_: SecurityException) {}
    }

    fun message(ctx: Context, peerId: String, name: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(name).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChat(ctx, peerId)).setAutoCancel(true).build()
        post(ctx, peerId.hashCode(), n)
    }

    fun fileRequest(ctx: Context, req: FileRequest, peerName: String) {
        fun action(accept: Boolean): PendingIntent = PendingIntent.getBroadcast(
            ctx, (req.fileId + accept).hashCode(),
            Intent(ctx, NotifyActionReceiver::class.java).setAction("edge.${if (accept) "accept" else "decline"}.${req.fileId}")
                .putExtra("peerId", req.peerId).putExtra("id", req.fileId).putExtra("accept", accept),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = if (req.isFolder) "folder ${req.name} (${req.fileCount} files, ${fmt(req.size)})" else "${req.name} (${fmt(req.size)})"
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("$peerName wants to send you a ${if (req.isFolder) "folder" else "file"}")
            .setContentText(what).setStyle(NotificationCompat.BigTextStyle().bigText(what))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChat(ctx, req.peerId))
            .addAction(0, "Accept", action(true)).addAction(0, "Decline", action(false))
            .setAutoCancel(true).build()
        post(ctx, req.fileId.hashCode(), n)
    }

    fun received(ctx: Context, peerId: String, peerName: String, what: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Received from $peerName").setContentText(what)
            .setContentIntent(openChat(ctx, peerId)).setAutoCancel(true).build()
        post(ctx, ("rcv" + what + peerId).hashCode(), n)
    }

    fun cancelRequest(ctx: Context, id: String) = NotificationManagerCompat.from(ctx).cancel(id.hashCode())
    fun cancelPeer(ctx: Context, peerId: String) = NotificationManagerCompat.from(ctx).cancel(peerId.hashCode())
}

/** Handles the Accept / Decline buttons on a file-request notification. */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        EdgeService.respondById(ctx, intent.getStringExtra("peerId") ?: return, intent.getStringExtra("id") ?: return, intent.getBooleanExtra("accept", false))
    }
}
