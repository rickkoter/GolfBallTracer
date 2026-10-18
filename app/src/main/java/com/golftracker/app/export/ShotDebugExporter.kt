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
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves diagnostic recordings to Downloads/GolfBallTracer so they can be shared for debugging. */
object ShotDebugExporter {

    private const val FOLDER = "GolfBallTracer"

    /**
     * Returns where the file was saved, for showing to the user, or null if saving failed. [kind] starts
     * the file's name: shot-debug for shots, missed-strike for strikes heard without a shot found.
     */
    fun save(context: Context, shot: CapturedShot, tracer: List<ScreenPoint>, kind: String = "shot-debug"): String? {
        return writeToDownloads(context, "${kind}_${stamp()}.zip") { ShotDebugArchive.write(shot, tracer, device(), it) }
    }

    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    fun device() = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

    /** Writes a zip into Downloads/GolfBallTracer; returns where, for showing to the user, or null if it failed. */
    fun writeToDownloads(context: Context, filename: String, write: (OutputStream) -> Unit): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null || resolver.openOutputStream(uri)?.use(write) == null) null else "Downloads/$FOLDER/$filename"
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
            val file = File(dir, filename)
            FileOutputStream(file).use(write)
            file.absolutePath
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
