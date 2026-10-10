package com.edge.mobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import edge.core.IncomingSink
import java.io.OutputStream

/**
 * Saves received files with MediaStore (no storage permission needed on API 29+).
 *  - photos  -> Pictures/Edge  (shows up in the Gallery)   } when [toGallery] is on
 *  - videos  -> Movies/Edge    (shows up in the Gallery)   }
 *  - anything else, or gallery off -> Downloads/Edge
 */
class MediaStoreSink(ctx: Context, name: String, mime: String, toGallery: Boolean) : IncomingSink {
    companion object {
        /** display name -> saved uri, read by EdgeService once the file is complete. */
        val saved = java.util.concurrent.ConcurrentHashMap<String, Uri>()
    }

    private val key = name
    private val resolver = ctx.contentResolver
    private val uri: Uri
    override val out: OutputStream

    init {
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        val kind = mediaKind(safe)
        val ext = safe.substringAfterLast('.', "").lowercase()
        // The collection insists on a matching mime type, so fix up generic ones from the extension.
        val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        val realMime = when {
            kind == MediaKind.IMAGE && mime.startsWith("image/") -> mime
            kind == MediaKind.VIDEO && mime.startsWith("video/") -> mime
            else -> guessed ?: mime
        }
        val usable = toGallery &&
            ((kind == MediaKind.IMAGE && realMime.startsWith("image/")) || (kind == MediaKind.VIDEO && realMime.startsWith("video/")))

        val (collection, folder) = when {
            usable && kind == MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES + "/Edge"
            usable && kind == MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES + "/Edge"
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS + "/Edge"
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
            put(MediaStore.MediaColumns.MIME_TYPE, if (usable) realMime else mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        uri = resolver.insert(collection, v) ?: error("MediaStore insert failed")
        out = resolver.openOutputStream(uri)!!.buffered(256 * 1024)
    }

    override fun commit() {
        out.close()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        saved[key] = uri
    }

    override fun abort() {
        runCatching { out.close() }
        runCatching { resolver.delete(uri, null, null) }
    }
}
