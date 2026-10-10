package com.edge.mobile

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ViewerItem(val uri: String, val name: String, val kind: MediaKind)

fun openExternally(ctx: Context, uriStr: String) {
    try {
        val uri = Uri.parse(uriStr)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, ctx.contentResolver.getType(uri) ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(ctx, "No app can open this file. It's in Downloads/Edge.", Toast.LENGTH_LONG).show()
    }
}

/** Full-screen preview: pinch / double-tap zoom for images, built-in player for videos. */
@Composable
fun MediaViewer(item: ViewerItem, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (item.kind == MediaKind.VIDEO) VideoPane(item) else ImagePane(item)

            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp).align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Color.White, fontSize = 17.sp) }
                Spacer(Modifier.width(12.dp))
                Text(item.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ImagePane(item: ViewerItem) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                // ImageDecoder applies the EXIF rotation for us; cap the size so huge photos don't exhaust memory.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, Uri.parse(item.uri))) { dec, info, _ ->
                    val m = maxOf(info.size.width, info.size.height)
                    if (m > 2400) {
                        val s = 2400f / m
                        dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                    }
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            } catch (_: Throwable) {
                Thumbnails.load(ctx, item.uri, MediaKind.IMAGE)    // fall back to the preview we already have
            }
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale <= 1f) Offset.Zero else clamp(offset + pan, scale)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b == null) Text("Loading…", color = Color.White.copy(alpha = 0.6f))
        else Image(
            b, null,
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun VideoPane(item: ViewerItem) {
    val ctx = LocalContext.current
    var failed by remember(item.uri) { mutableStateOf(false) }
    if (failed) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This video can't be played here.", color = Color.White)
            TextButton({ openExternally(ctx, item.uri) }) { Text("Open in another app", color = Color(0xFF4D9FFF)) }
        }
        return
    }
    var view by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(item.uri) { onDispose { view?.stopPlayback() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            VideoView(c).also { v ->
                view = v
                v.setMediaController(MediaController(c).also { it.setAnchorView(v) })
                v.setOnPreparedListener { it.isLooping = false; v.start() }
                v.setOnErrorListener { _, _, _ -> failed = true; true }
                v.setVideoURI(Uri.parse(item.uri))
            }
        },
    )
}
