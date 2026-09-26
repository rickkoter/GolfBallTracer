package com.golftracker.app.export

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.model.TracerStyle
import com.golftracker.app.tracker.TrajectoryMath
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object SnapshotExporter {

    /**
     * Bakes a high-resolution snapshot with camera image background, neon golf ball tracer path,
     * and a stylish telemetry HUD overlay card.
     */
    fun createAnnotatedSnapshot(
        context: Context,
        baseBitmap: Bitmap,
        shotRecord: ShotRecord
    ): Uri? {
        val width = baseBitmap.width
        val height = baseBitmap.height

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // 1. Draw base camera image
        canvas.drawBitmap(baseBitmap, 0f, 0f, null)

        // 2. Resolve tracer style color
        val activeTracerStyle = try {
            TracerStyle.valueOf(shotRecord.tracerStyle)
        } catch (e: Exception) {
            TracerStyle.PRO_LIME
        }

        val tracerColor = activeTracerStyle.colorHex.toInt()
        val glowColor = activeTracerStyle.secondaryHex.toInt()

        // 3. Draw tracer curve
        val points = if (shotRecord.points.isNotEmpty()) {
            shotRecord.points
        } else {
            TrajectoryMath.generateDefaultTracer(width.toFloat(), height.toFloat())
        }

        if (points.size >= 2) {
            // Setup outer glow paint
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = glowColor
                this.style = Paint.Style.STROKE
                this.strokeWidth = width * 0.025f
                this.strokeCap = Paint.Cap.ROUND
                this.strokeJoin = Paint.Join.ROUND
                this.maskFilter = BlurMaskFilter(width * 0.015f, BlurMaskFilter.Blur.NORMAL)
            }

            // Setup main core paint
            val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = tracerColor
                this.style = Paint.Style.STROKE
                this.strokeWidth = width * 0.012f
                this.strokeCap = Paint.Cap.ROUND
                this.strokeJoin = Paint.Join.ROUND
            }

            val path = Path()
            val p0 = points.first()
            val startX = if (p0.x <= 1.0f) p0.x * width else p0.x
            val startY = if (p0.y <= 1.0f) p0.y * height else p0.y
            path.moveTo(startX, startY)

            for (i in 1 until points.size) {
                val pt = points[i]
                val px = if (pt.x <= 1.0f) pt.x * width else pt.x
                val py = if (pt.y <= 1.0f) pt.y * height else pt.y
                path.lineTo(px, py)
            }

            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, corePaint)

            // Draw impact target indicator on last point
            val lastPt = points.last()
            val endX = if (lastPt.x <= 1.0f) lastPt.x * width else lastPt.x
            val endY = if (lastPt.y <= 1.0f) lastPt.y * height else lastPt.y

            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = tracerColor
                this.style = Paint.Style.STROKE
                this.strokeWidth = width * 0.005f
            }
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                this.style = Paint.Style.FILL
            }

            canvas.drawCircle(endX, endY, width * 0.02f, ringPaint)
            canvas.drawCircle(endX, endY, width * 0.01f, dotPaint)
        }

        // 4. Draw Telemetry HUD Badge at the bottom left
        drawTelemetryCard(canvas, width, height, shotRecord, activeTracerStyle)

        // 5. Save bitmap to storage
        return saveBitmapToStorage(context, resultBitmap)
    }

    private fun drawTelemetryCard(
        canvas: Canvas,
        width: Int,
        height: Int,
        shot: ShotRecord,
        tracerStyle: TracerStyle
    ) {
        val padding = width * 0.04f
        val cardWidth = width * 0.55f
        val cardHeight = height * 0.22f
        val cardLeft = padding
        val cardTop = height - cardHeight - padding

        val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(200, 15, 23, 42) // Dark glassmorphism slate
            this.style = Paint.Style.FILL
        }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = tracerStyle.colorHex.toInt()
            this.style = Paint.Style.STROKE
            this.strokeWidth = width * 0.004f
        }

        val rect = RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)
        canvas.drawRoundRect(rect, 24f, 24f, cardBgPaint)
        canvas.drawRoundRect(rect, 24f, 24f, cardBorderPaint)

        // Text paints
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tracerStyle.colorHex.toInt()
            textSize = width * 0.032f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width * 0.065f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textSize = width * 0.026f
        }

        var textY = cardTop + (cardHeight * 0.25f)
        canvas.drawText("⚡ GolfBallTracer", cardLeft + 24f, textY, titlePaint)

        textY += (cardHeight * 0.35f)
        val distString = "%.0f YDS".format(shot.distanceYards)
        canvas.drawText(distString, cardLeft + 24f, textY, distPaint)

        textY += (cardHeight * 0.22f)
        val clubDateString = "${shot.club.uppercase()} • ${formatTimestamp(shot.timestampMs)}"
        canvas.drawText(clubDateString, cardLeft + 24f, textY, subTextPaint)

        val gpsString = if (shot.launchLocation != null) {
            "GPS: %.4f, %.4f".format(shot.launchLocation.latitude, shot.launchLocation.longitude)
        } else {
            "GPS Telemetry Active"
        }
        textY += (cardHeight * 0.16f)
        canvas.drawText(gpsString, cardLeft + 24f, textY, subTextPaint)
    }

    private fun formatTimestamp(timeMs: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US)
        return sdf.format(Date(timeMs))
    }

    private fun saveBitmapToStorage(context: Context, bitmap: Bitmap): Uri? {
        val filename = "GOLF_SHOT_${System.currentTimeMillis()}.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GolfTracker")
            }

            val resolver = context.contentResolver
            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (imageUri != null) {
                resolver.openOutputStream(imageUri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                return imageUri
            }
        }

        // Fallback to internal storage
        val picturesDir = File(context.getExternalFilesDir(null), "GolfTracker")
        if (!picturesDir.exists()) picturesDir.mkdirs()

        val file = File(picturesDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
