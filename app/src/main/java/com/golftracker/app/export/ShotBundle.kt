package com.golftracker.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.tracker.CapturedShot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What was tracked for a shot, saved next to its video as tracking.json: where the ball was, when it was
 * struck, both tracers (live and from the video) and every spot the detector saw.
 */
@Serializable
data class ShotTracking(
    val version: Int = 1,
    val teeX: Float,
    val teeY: Float,
    val ballRadius: Float?,
    val aspect: Float,
    val impactTimestampNs: Long?,
    val heardImpact: Boolean,
    val launchTimestampNs: Long?,
    /** When the video's first frame was taken, on the clock all the timestamps here use. */
    val videoStartNs: Long? = null,
    val videoFrameRate: Double? = null,
    val tracedFrom: String,
    val liveTracer: List<ScreenPoint>,
    val liveTrackedSeconds: Double? = null,
    val videoTracer: List<ScreenPoint>? = null,
    val videoTrackedSeconds: Double? = null,
    val videoNote: String? = null,
    /** Spots the live detector saw, as [x, y, score, ms since impact]. */
    val liveCandidates: List<List<Float>> = emptyList()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun of(shot: CapturedShot, liveTracer: List<ScreenPoint>, liveTrackedSeconds: Double?) = ShotTracking(
            teeX = shot.teeX,
            teeY = shot.teeY,
            ballRadius = shot.ballRadius,
            aspect = shot.aspect,
            impactTimestampNs = shot.impactTimestampNs,
            heardImpact = shot.heardImpact,
            launchTimestampNs = shot.launchTimestampNs,
            tracedFrom = ShotRecord.TRACED_FROM_CAMERA,
            liveTracer = liveTracer,
            liveTrackedSeconds = liveTrackedSeconds,
            liveCandidates = shot.candidates.map {
                listOf(it.x, it.y, it.score, ((it.timestampNs - (shot.impactTimestampNs ?: 0L)) / 1e6).toFloat())
            }
        )

        fun write(tracking: ShotTracking, file: File) = file.writeText(json.encodeToString(serializer(), tracking))

        fun read(text: String): ShotTracking = json.decodeFromString(serializer(), text)
    }
}

/**
 * A shot's whole recording in one zip, to share for testing and analysis later: the video, the
 * tracking data, the shot's details and the analysis frames. The analysis part (shot.json and
 * frames.bin) is a ShotDebugArchive, so ShotReplayTest can replay a bundle as it is.
 */
object ShotBundle {

    const val VIDEO = "video.mp4"
    const val TRACKING = "tracking.json"
    const val CAPTURE = "capture.zip"
    const val RECORD = "record.json"

    private val json = Json { prettyPrint = true }

    fun fileName(shot: ShotRecord): String =
        "golf-shot_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(shot.timestampMs)) + ".zip"

    /** Writes the bundle for [shot], whose recording is in [dir]. */
    fun write(shot: ShotRecord, dir: File, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(RECORD))
            zip.write(json.encodeToString(ShotRecord.serializer(), shot).toByteArray())
            zip.closeEntry()
            File(dir, TRACKING).takeIf { it.exists() }?.let { copyInto(zip, TRACKING, it.inputStream()) }
            // The analysis frames go in as they are, so the bundle reads as a debug archive too.
            File(dir, CAPTURE).takeIf { it.exists() }?.let { capture ->
                ZipInputStream(capture.inputStream().buffered()).use { inner ->
                    while (true) {
                        val entry = inner.nextEntry ?: break
                        copyInto(zip, entry.name, inner, close = false)
                    }
                }
            }
            // Video is already compressed; squeezing it again only takes time.
            File(dir, VIDEO).takeIf { it.exists() }?.let {
                zip.setLevel(Deflater.NO_COMPRESSION)
                copyInto(zip, VIDEO, it.inputStream())
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            }
        }
    }

    private fun copyInto(zip: ZipOutputStream, name: String, input: InputStream, close: Boolean = true) {
        zip.putNextEntry(ZipEntry(name))
        input.copyTo(zip)
        if (close) input.close()
        zip.closeEntry()
    }

    /**
     * Bundles each shot's recording into the app's cache and returns a share intent for them, ready for
     * the share sheet (Google Drive, email, …). Null if none of the shots has a recording.
     */
    fun shareIntent(context: Context, shots: List<ShotRecord>, recordingDir: (String) -> File): Intent? {
        val outDir = File(context.cacheDir, "shared_shots").apply {
            deleteRecursively()
            mkdirs()
        }
        val uris = ArrayList<Uri>()
        for (shot in shots) {
            val dir = recordingDir(shot.id)
            if (!dir.exists()) continue
            val file = File(outDir, fileName(shot))
            file.outputStream().use { write(shot, dir, it) }
            uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        if (uris.isEmpty()) return null
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = "application/zip"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, if (uris.size == 1) "Share shot recording" else "Share ${uris.size} shot recordings")
    }
}
