package com.edge.mobile

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

enum class MediaKind { IMAGE, VIDEO, OTHER }

fun mediaKind(name: String): MediaKind = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> MediaKind.IMAGE
    "mp4", "mkv", "mov", "webm", "avi", "m4v", "3gp" -> MediaKind.VIDEO
    else -> MediaKind.OTHER
}

object Thumbnails {
    private val cache = LruCache<String, ImageBitmap>(48)

    /** Blocking; call from a background dispatcher. Returns null if no preview can be made. */
    fun load(ctx: Context, uriStr: String, kind: MediaKind): ImageBitmap? {
        cache.get(uriStr)?.let { return it }
        val uri = Uri.parse(uriStr)
        val bmp = try {
            // Works for MediaStore and for providers that support thumbnails.
            ctx.contentResolver.loadThumbnail(uri, Size(640, 640), null)
        } catch (_: Exception) {
            try {
                when (kind) {
                    MediaKind.VIDEO -> MediaMetadataRetriever().run {
                        try { setDataSource(ctx, uri); getFrameAtTime(0) } finally { release() }
                    }
                    MediaKind.IMAGE -> {
                        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, b) }
                        var sample = 1
                        while (b.outWidth / (sample * 2) >= 640 && b.outHeight / (sample * 2) >= 640) sample *= 2
                        ctx.contentResolver.openInputStream(uri)!!.use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }
                    MediaKind.OTHER -> null
                }
            } catch (_: Throwable) { null }
        }
        return bmp?.asImageBitmap()?.also { cache.put(uriStr, it) }
    }
}
