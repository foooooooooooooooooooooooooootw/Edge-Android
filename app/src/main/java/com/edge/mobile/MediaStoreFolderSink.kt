package com.edge.mobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import edge.core.IncomingFolderSink
import java.io.OutputStream

/** Writes a received folder to Downloads/Edge/<folder>/..., keeping the sub-folder structure. */
class MediaStoreFolderSink(ctx: Context, folderName: String) : IncomingFolderSink {
    private val resolver = ctx.contentResolver
    private val root = Environment.DIRECTORY_DOWNLOADS + "/Edge/" + folderName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "folder" }
    private val created = ArrayList<Uri>()
    private var cur: OutputStream? = null
    private var curUri: Uri? = null

    override fun openFile(relPath: String, size: Long): OutputStream {
        val dir = relPath.substringBeforeLast('/', "")
        val base = relPath.substringAfterLast('/')
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(base.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, base)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (dir.isEmpty()) root else "$root/$dir")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("MediaStore insert failed for $relPath")
        created.add(uri); curUri = uri
        return resolver.openOutputStream(uri)!!.buffered(256 * 1024).also { cur = it }
    }

    override fun closeFile() {
        runCatching { cur?.close() }; cur = null
        curUri?.let { resolver.update(it, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
        curUri = null
    }

    override fun commit() { closeFile() }

    override fun abort() {
        runCatching { cur?.close() }; cur = null
        created.forEach { runCatching { resolver.delete(it, null, null) } }
    }
}
