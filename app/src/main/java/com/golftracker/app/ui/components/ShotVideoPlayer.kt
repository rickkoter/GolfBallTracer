package com.golftracker.app.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.model.TracerStyle
import java.io.File

/**
 * Plays a shot's recording on a loop with its tracer drawn over it, growing in step with the ball when
 * the video's timing is known. The video is cropped to the view the tracer was traced in ([frameAspect],
 * width / height), the same way the camera preview was. Tapping pauses and resumes.
 */
@Composable
fun ShotVideoPlayer(
    videoPath: String,
    points: List<ScreenPoint>,
    style: TracerStyle,
    videoStartMs: Long?,
    frameAspect: Float,
    modifier: Modifier = Modifier
) {
    // The video's shape once turned upright.
    val videoAspect = remember(videoPath) { uprightAspect(videoPath) ?: (9f / 16f) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(videoPath) {
        onDispose {
            player?.release()
            player = null
        }
    }
    // Follow playback every frame, to grow the tracer with the ball.
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            positionMs = try { p.currentPosition.toLong() } catch (e: IllegalStateException) { break }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .clickable { player?.let { if (it.isPlaying) it.pause() else it.start() } },
        contentAlignment = Alignment.Center
    ) {
        // Cover the box with the video, cropping whichever way it's too big, like the preview does.
        val boxAspect = maxWidth / maxHeight
        val (w, h) = if (videoAspect > boxAspect) (maxHeight * videoAspect) to maxHeight else maxWidth to (maxWidth / videoAspect)
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            if (!File(videoPath).exists()) return
                            player = MediaPlayer().apply {
                                setDataSource(videoPath)
                                setSurface(Surface(texture))
                                isLooping = true
                                setOnPreparedListener { it.start() }
                                prepareAsync()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            player?.release()
                            player = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.requiredSize(w, h)
        )

        // The tracer is drawn in the view's own crop, centered the same way.
        val density = LocalDensity.current
        Canvas(modifier = Modifier.fillMaxSize()) {
            val viewW = size.width
            val viewH = size.height
            val shown = if (videoStartMs == null) points else points.filter { it.timestampMs <= videoStartMs + positionMs }
            if (shown.size < 2) return@Canvas
            // The traced view, scaled to cover the box like the video.
            val (tw, th) = if (frameAspect > viewW / viewH) (viewH * frameAspect) to viewH else viewW to (viewW / frameAspect)
            val ox = (viewW - tw) / 2
            val oy = (viewH - th) / 2
            fun at(p: ScreenPoint) = Offset(ox + p.x * tw, oy + p.y * th)
            val path = Path().apply {
                moveTo(at(shown.first()).x, at(shown.first()).y)
                shown.drop(1).forEach { lineTo(at(it).x, at(it).y) }
            }
            val stroke = with(density) { 6f * this.density }
            drawPath(path, Color(style.secondaryHex), style = Stroke(stroke * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Color(style.colorHex), style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Color.White, radius = stroke * 0.8f, center = at(shown.last()))
        }
    }
}

private fun uprightAspect(path: String): Float? = try {
    MediaMetadataRetriever().run {
        try {
            setDataSource(path)
            val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
            val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
            val rotation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0f || h <= 0f) null
            else if (rotation == 90 || rotation == 270) h / w else w / h
        } finally {
            release()
        }
    }
} catch (e: Exception) {
    null
}
