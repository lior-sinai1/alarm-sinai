package com.alarmsinai.ui.screens.cameras

import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.alarmsinai.data.CameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Size of a w×h box after rotating it by [deg] degrees. */
internal fun rotatedBounds(w: Float, h: Float, deg: Float): Pair<Float, Float> {
    val r = Math.toRadians(deg.toDouble())
    val c = abs(cos(r)).toFloat()
    val s = abs(sin(r)).toFloat()
    return (w * c + h * s) to (w * s + h * c)
}

/**
 * Loads an image through the authenticated client. [url] is resolved lazily because the
 * route (home / Tailscale / ngrok) is picked at runtime. refreshMs > 0 reloads in a loop.
 */
@Composable
fun rememberAuthBitmap(
    repo: CameraRepository,
    key: Any,
    refreshMs: Long = 0,
    url: suspend () -> String
): Pair<ImageBitmap?, Boolean> {
    var bmp by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, refreshMs) {
        while (true) {
            try {
                val bytes = repo.bytes(url())
                val decoded = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
                if (decoded != null) { bmp = decoded; failed = false } else failed = true
            } catch (_: Exception) {
                failed = true
            }
            if (refreshMs <= 0) break
            delay(if (failed) maxOf(refreshMs, 5_000) else refreshMs)
        }
    }
    return bmp to failed
}

/** Draws [bitmap] rotated by rotation + tilt, scaled to fit the box. */
@Composable
fun RotatedImage(bitmap: ImageBitmap, rotation: Int, tilt: Double, modifier: Modifier = Modifier) {
    Canvas(modifier.clipToBounds()) {
        val deg = rotation + tilt.toFloat()
        val (bw, bh) = rotatedBounds(bitmap.width.toFloat(), bitmap.height.toFloat(), deg)
        val scale = min(size.width / bw, size.height / bh)
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            rotate(deg, pivot = Offset.Zero)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawImage(bitmap, topLeft = Offset(-bitmap.width / 2f, -bitmap.height / 2f))
        }
    }
}

/** Width / height of the image as shown after rotation (for card aspect ratios). */
fun shownAspect(w: Int, h: Int, rotation: Int): Float =
    if (rotation == 90 || rotation == 270) h.toFloat() / w else w.toFloat() / h

/**
 * Video through ExoPlayer on a TextureView (so it can rotate), with pinch-zoom,
 * pan and double-tap. Works for the live fMP4 stream and for recorded clips.
 */
@OptIn(UnstableApi::class)
@Composable
fun ZoomableVideo(
    repo: CameraRepository,
    url: String,
    live: Boolean,
    rotation: Int,
    tilt: Double,
    modifier: Modifier = Modifier,
    onPlayer: (ExoPlayer) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var buffering by remember { mutableStateOf(true) }
    var zoom by remember(url) { mutableFloatStateOf(1f) }
    var pan by remember(url) { mutableStateOf(Offset.Zero) }

    val player = remember(url) {
        val loadControl = if (live) DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 5_000, 300, 500)   // start fast, stay close to live
            .build() else DefaultLoadControl()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(repo.http)))
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onPlayerError(error: PlaybackException) { onError(error.errorCodeName) }
        }
        player.addListener(listener)
        onPlayer(player)
        onDispose { player.removeListener(listener); player.release() }
    }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .background(Color.Black)
            .pointerInput(url) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                    pan = if (zoom == 1f) Offset.Zero else pan + panChange
                }
            }
            .pointerInput(url) {
                detectTapGestures(onDoubleTap = {
                    if (zoom > 1f) { zoom = 1f; pan = Offset.Zero } else zoom = 2.5f
                })
            },
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val vw = if (videoSize.width > 0) videoSize.width * videoSize.pixelWidthHeightRatio else 16f
        val vh = if (videoSize.height > 0) videoSize.height.toFloat() else 9f
        val deg = rotation + tilt.toFloat()
        val (bw, bh) = rotatedBounds(vw, vh, deg)
        val fit = min(boxW / bw, boxH / bh)
        val w = with(density) { (vw * fit).toDp() }
        val h = with(density) { (vh * fit).toDp() }
        // Keep panning inside the zoomed picture.
        val maxX = (bw * fit * zoom - boxW).coerceAtLeast(0f) / 2f
        val maxY = (bh * fit * zoom - boxH).coerceAtLeast(0f) / 2f
        val px = pan.x.coerceIn(-maxX, maxX)
        val py = pan.y.coerceIn(-maxY, maxY)

        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            update = { player.setVideoTextureView(it) },
            modifier = Modifier
                .size(w, h)
                .graphicsLayer {
                    rotationZ = deg
                    scaleX = zoom; scaleY = zoom
                    translationX = px; translationY = py
                }
        )
        if (buffering) CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
        if (zoom > 1f) Text(
            "×%.1f".format(zoom), color = Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopStart).background(Color(0x88000000))
        )
    }
}
