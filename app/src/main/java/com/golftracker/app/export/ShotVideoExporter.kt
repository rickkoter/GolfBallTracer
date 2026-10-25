package com.golftracker.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.model.TracerStyle
import com.golftracker.app.model.distance
import com.golftracker.app.model.tracerRuns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Exports shot videos with the tracer drawn in, ready to post or send: each shot's recording is cropped to
 * the view the tracer was traced in (as Shot Review shows it), the tracer grows along with the ball, and a
 * caption gives the club, golfer and distance. The videos are saved to the phone's Movies/GolfTracker folder.
 */
@OptIn(UnstableApi::class)
object ShotVideoExporter {

    const val FOLDER = "Movies/GolfTracker"

    /** What an export did: the videos saved, and the shots left out (no video, or it couldn't be made). */
    class Outcome(val saved: List<Uri>, val withoutVideo: List<ShotRecord>, val failed: List<ShotRecord>) {
        val summary: String get() = buildList {
            add(if (saved.size == 1) "Saved 1 video to $FOLDER" else "Saved ${saved.size} videos to $FOLDER")
            if (withoutVideo.isNotEmpty()) add("${withoutVideo.size} without a video")
            if (failed.isNotEmpty()) add("${failed.size} couldn't be made")
        }.joinToString(" • ")
    }

    fun hasVideo(shot: ShotRecord): Boolean = shot.videoPath?.let { File(it).exists() } == true

    fun fileName(shot: ShotRecord): String =
        "golf-shot_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(shot.timestampMs)) + ".mp4"

    /**
     * Makes and saves the video of each shot in turn. [onProgress] gets the fraction of the whole export done,
     * on the main thread.
     */
    suspend fun export(context: Context, shots: List<ShotRecord>, onProgress: (Float) -> Unit = {}): Outcome {
        val saved = ArrayList<Uri>()
        val withoutVideo = ArrayList<ShotRecord>()
        val failed = ArrayList<ShotRecord>()
        val work = File(context.cacheDir, "exported_videos").apply { mkdirs() }
        shots.forEachIndexed { i, shot ->
            val video = shot.videoPath?.let(::File)?.takeIf { it.exists() }
            if (video == null) {
                withoutVideo += shot
                return@forEachIndexed
            }
            val out = File(work, fileName(shot))
            try {
                render(context, shot, video, out) { p -> onProgress((i + p) / shots.size) }
                val uri = withContext(Dispatchers.IO) { saveToMovies(context, out, fileName(shot)) }
                if (uri != null) saved += uri else failed += shot
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                failed += shot
            } finally {
                out.delete()
            }
            onProgress((i + 1f) / shots.size)
        }
        return Outcome(saved, withoutVideo, failed)
    }

    /** Re-encodes [video] into [out] (mp4, H.264) with [shot]'s tracer and caption drawn in. */
    suspend fun render(context: Context, shot: ShotRecord, video: File, out: File, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.Main) {
            out.parentFile?.mkdirs()
            out.delete()
            val aspect = shot.frameAspect ?: (9f / 16f)
            val effects = Effects(
                emptyList(),
                listOf(
                    // Cropped like the preview the tracer was traced in, so the tracer lines up.
                    Presentation.createForAspectRatio(aspect, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                    OverlayEffect(listOf<TextureOverlay>(TracerOverlay(shot)))
                )
            )
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(video))).setEffects(effects).build()
            coroutineScope {
                var transformer: Transformer? = null
                val ticker = launch {
                    val progress = ProgressHolder()
                    while (isActive) {
                        if (transformer?.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(progress.progress / 100f)
                        delay(200)
                    }
                }
                try {
                    suspendCancellableCoroutine { cont ->
                        val t = Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (cont.isActive) cont.resume(Unit)
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (cont.isActive) cont.resumeWithException(exportException)
                                }
                            })
                            .build()
                        transformer = t
                        t.start(item, out.absolutePath)
                        // The transformer is only touched on the thread it was made on.
                        cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { t.cancel() } }
                    }
                } finally {
                    ticker.cancel()
                }
            }
        }

    /** Copies a finished video into Movies/GolfTracker, where the gallery and other apps find it. */
    private fun saveToMovies(context: Context, file: File, name: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no output")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                e.printStackTrace()
                resolver.delete(uri, null, null)
                null
            }
        }
        // Before Android 10: the app's own Movies folder, shared through the file provider.
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "GolfTracker").apply { mkdirs() }
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    /** A share sheet for exported videos (messages, email, Drive, social apps). */
    fun shareIntent(uris: List<Uri>): Intent {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = "video/mp4"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, if (uris.size == 1) "Share shot video" else "Share ${uris.size} shot videos")
    }

    /**
     * Draws the tracer over each frame as far as the ball had got by then (all of it when the video's timing
     * isn't known), solid where the ball was seen and dashed where estimated, plus the shot's caption.
     */
    private class TracerOverlay(private val shot: ShotRecord) : CanvasOverlay(/* useInputFrameSize = */ true) {
        private val tracerStyle = runCatching { TracerStyle.valueOf(shot.tracerStyle) }.getOrDefault(TracerStyle.PRO_LIME)
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = tracerStyle.colorHex.toInt()
        }
        private val glow = Paint(core).apply { color = tracerStyle.secondaryHex.toInt() }
        private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val captionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val captionBack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 15, 23, 42) }
        private val caption = listOfNotNull(
            shot.club.uppercase(),
            shot.golfer?.let { "GOLFER $it" },
            shot.distance?.label
        ).joinToString("  •  ")

        override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            drawCaption(canvas, w, h)

            val startMs = shot.videoStartMs
            val shown = if (startMs == null) shot.points else shot.points.filter { it.timestampMs <= startMs + presentationTimeUs / 1000 }
            if (shown.size < 2) return
            val width = h * 0.011f
            core.strokeWidth = width
            glow.strokeWidth = width * 2.2f
            val dash = DashPathEffect(floatArrayOf(width * 3, width * 2.5f), 0f)
            fun x(p: ScreenPoint) = p.x * w
            fun y(p: ScreenPoint) = p.y * h
            for ((run, estimated) in tracerRuns(shown)) {
                val path = Path().apply {
                    moveTo(x(run.first()), y(run.first()))
                    run.drop(1).forEach { lineTo(x(it), y(it)) }
                }
                core.pathEffect = if (estimated) dash else null
                glow.pathEffect = core.pathEffect
                canvas.drawPath(path, glow)
                canvas.drawPath(path, core)
            }
            canvas.drawCircle(x(shown.last()), y(shown.last()), width * 0.8f, head)
        }

        private fun drawCaption(canvas: Canvas, w: Float, h: Float) {
            captionText.textSize = h * 0.022f
            val title = "GolfBallTracer"
            val pad = captionText.textSize * 0.6f
            val lineGap = captionText.textSize * 1.3f
            val textWidth = maxOf(captionText.measureText(title), if (caption.isEmpty()) 0f else captionText.measureText(caption))
            val lines = if (caption.isEmpty()) 1 else 2
            val left = w * 0.04f
            val top = h * 0.03f
            canvas.drawRoundRect(RectF(left, top, left + textWidth + 2 * pad, top + lines * lineGap + pad), pad, pad, captionBack)
            captionText.color = tracerStyle.colorHex.toInt()
            canvas.drawText(title, left + pad, top + pad + captionText.textSize, captionText)
            if (caption.isNotEmpty()) {
                captionText.color = Color.WHITE
                canvas.drawText(caption, left + pad, top + pad + captionText.textSize + lineGap, captionText)
            }
        }
    }
}
