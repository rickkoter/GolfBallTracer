package com.golftracker.app.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.ShotDebugArchive
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves a shot's diagnostic recording to Downloads/GolfBallTracer so it can be shared for debugging. */
object ShotDebugExporter {

    private const val FOLDER = "GolfBallTracer"

    /** Returns where the file was saved, for showing to the user, or null if saving failed. */
    fun save(context: Context, shot: CapturedShot, tracer: List<ScreenPoint>): String? {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val filename = "shot-debug_$stamp.zip"
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { ShotDebugArchive.write(shot, tracer, device, it) } ?: return null
                "Downloads/$FOLDER/$filename"
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
                val file = File(dir, filename)
                FileOutputStream(file).use { ShotDebugArchive.write(shot, tracer, device, it) }
                file.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
