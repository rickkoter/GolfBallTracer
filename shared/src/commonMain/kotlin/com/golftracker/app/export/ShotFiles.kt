package com.golftracker.app.export

import com.golftracker.app.model.ShotRecord
import com.golftracker.app.replay.ZipArchive
import com.golftracker.app.replay.ZipWriter
import kotlinx.serialization.json.Json

/**
 * How a saved shot is kept, the same in both apps: its record as JSON (shot_data/<id>.json), and its
 * recording in shot_media/<id>/ (the video, tracking.json and the analysis frames as capture.zip). A shot
 * shared for analysis is all of that in one golf-shot_*.zip.
 */
object ShotFiles {
    const val VIDEO = "video.mp4"
    const val TRACKING = "tracking.json"
    const val CAPTURE = "capture.zip"
    const val RECORD = "record.json"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun encodeRecord(shot: ShotRecord): String = json.encodeToString(ShotRecord.serializer(), shot)

    /** The shot in [text], or null if it isn't one. */
    fun decodeRecord(text: String): ShotRecord? = try {
        json.decodeFromString(ShotRecord.serializer(), text)
    } catch (e: Exception) {
        null
    }

    /**
     * A shot's whole recording in one zip, as the Android app shares it: the record, the tracking, the
     * analysis frames (the capture's entries, so the bundle replays as a shot recording) and the video.
     */
    fun bundle(shot: ShotRecord, tracking: ByteArray?, capture: ByteArray?, video: ByteArray?): ByteArray {
        val zip = ZipWriter()
        zip.add(RECORD, encodeRecord(shot).encodeToByteArray())
        tracking?.let { zip.add(TRACKING, it) }
        capture?.let { bytes ->
            val inner = ZipArchive(bytes)
            for (entry in inner.entries) zip.add(entry.name, inner.read(entry))
        }
        // Video is already compressed; squeezing it again only takes time.
        video?.let { zip.add(VIDEO, it, compress = false) }
        return zip.finish()
    }
}
