package com.golftracker.app.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.golftracker.app.export.ShotBundle
import com.golftracker.app.export.ShotDebugExporter
import com.golftracker.app.export.ShotTracking
import com.golftracker.app.export.SnapshotExporter
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.ShotDebugArchive
import com.golftracker.app.video.ShotVideoRecorder
import com.golftracker.app.video.VideoRetracer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Looks after a shot once it has been captured, in the background so the next shot can be taken
 * meanwhile, and so it carries on after leaving the tracker screen:
 *  1. keeps its recording: the stretch of video around the shot, the analysis frames and tracking.json;
 *  2. saves the shot to the history with the live tracer;
 *  3. traces it again from the video and switches to that tracer if it's better;
 *  4. bakes the snapshot image with the final tracer.
 */
class ShotProcessor(private val context: Context, private val repository: ShotRepository) {

    sealed interface Update {
        val shotId: String

        /** Saved with its recording. [debugExport] is where the analysis frames were also saved for debugging. */
        data class Saved(override val shotId: String, val debugExport: String?, val hasVideo: Boolean) : Update

        /** Traced again from the video; [flight] and [points] (as shown on screen) are set if the video's trace was used. */
        data class Retraced(
            override val shotId: String,
            val flight: BallFlightFitter.Flight?,
            val points: List<ScreenPoint>?,
            val note: String
        ) : Update
    }

    /** A shot just captured, with what's needed to save it. [record] holds the live tracer, shifted onto [still]. */
    class NewShot(
        val record: ShotRecord,
        val shot: CapturedShot,
        val liveFlight: BallFlightFitter.Flight?,
        val clip: ShotVideoRecorder.Clip?,
        val still: Bitmap?,
        val stillShift: Pair<Float, Float>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // One video at a time: decoding and re-tracing is heavy, and shots come one after another anyway.
    private val retraceLock = Mutex()
    private val _updates = MutableSharedFlow<Update>(extraBufferCapacity = 16)
    val updates: SharedFlow<Update> = _updates

    /** What each baked snapshot shows, so later edits know whether to bake it again. */
    private val bakedLooks = HashMap<String, List<Any?>>()

    fun process(new: NewShot) {
        scope.launch { run(new) }
    }

    private suspend fun run(new: NewShot) {
        val shot = new.shot
        val id = new.record.id
        val dir = repository.recordingDir(id).apply { mkdirs() }

        // 1. The recording: video from a little before the swing, the analysis frames, the tracking.
        val (video, videoStartNs) = keepVideo(new.clip, shot, File(dir, ShotBundle.VIDEO))
        val liveTracer = new.liveFlight?.tracer ?: emptyList()
        try {
            File(dir, ShotBundle.CAPTURE).outputStream().use { ShotDebugArchive.write(shot, liveTracer, device(), it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val trackingFile = File(dir, ShotBundle.TRACKING)
        var tracking = ShotTracking.of(shot, liveTracer, new.liveFlight?.trackedSeconds).copy(videoStartNs = videoStartNs)
        ShotTracking.write(tracking, trackingFile)

        // 2. Into the history straight away.
        repository.saveShot(
            new.record.copy(
                videoPath = video?.path,
                trackingPath = trackingFile.path,
                videoStartMs = videoStartNs?.let { it / 1_000_000L },
                frameAspect = shot.aspect,
                tracedFrom = ShotRecord.TRACED_FROM_CAMERA
            )
        )
        val debugExport = ShotDebugExporter.save(context, shot, liveTracer)
        _updates.emit(Update.Saved(id, debugExport, video != null))

        // 3. Trace again from the video.
        var points = new.record.points
        var tracedFrom = ShotRecord.TRACED_FROM_CAMERA
        var startNs = videoStartNs
        val note: String
        var usedFlight: BallFlightFitter.Flight? = null
        if (video == null) {
            note = "No video was recorded"
        } else {
            val result = retraceLock.withLock {
                try {
                    VideoRetracer.retrace(video, videoStartNs, shot)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (result?.offsetNs != null) startNs = result.offsetNs
            val videoFlight = result?.flight
            val better = videoFlight != null && VideoRetracer.isBetter(new.liveFlight, videoFlight)
            if (better) {
                val (dx, dy) = new.stillShift
                usedFlight = videoFlight
                points = videoFlight.tracer.map { it.copy(x = it.x + dx, y = it.y + dy) }
                tracedFrom = ShotRecord.TRACED_FROM_VIDEO
            }
            note = when {
                result == null -> "Couldn't trace the video"
                better -> "Traced from the full video (${result.note})"
                else -> "Kept the live trace (video: ${result.note})"
            }
            tracking = tracking.copy(
                videoStartNs = startNs,
                videoFrameRate = result?.frameRate,
                tracedFrom = tracedFrom,
                videoTracer = result?.flight?.tracer,
                videoTrackedSeconds = result?.flight?.trackedSeconds,
                videoNote = result?.note
            )
            ShotTracking.write(tracking, trackingFile)
        }

        // 4. Bake the snapshot with the final tracer, unless the curve was tuned by hand meanwhile.
        val current = repository.find(id) ?: return
        val edited = current.points != new.record.points
        val final = if (edited) current else current.copy(points = points, tracedFrom = tracedFrom)
        val snapshot = new.still?.let { SnapshotExporter.createAnnotatedSnapshot(context, it, final) }
        val saved = repository.update(id) {
            it.copy(
                points = final.points,
                tracedFrom = final.tracedFrom,
                videoStartMs = startNs?.let { ns -> ns / 1_000_000L },
                snapshotUri = snapshot?.toString() ?: it.snapshotUri
            )
        }
        if (saved != null) synchronized(bakedLooks) { bakedLooks[id] = lookOf(saved) }
        _updates.emit(Update.Retraced(id, usedFlight, if (edited || usedFlight == null) null else points, note))
    }

    /** Cuts the shot out of the rolling recording. Returns the video and when its first frame was taken. */
    private fun keepVideo(clip: ShotVideoRecorder.Clip?, shot: CapturedShot, target: File): Pair<File?, Long?> {
        clip ?: return null to null
        try {
            val impact = shot.impactTimestampNs
            val fromUs = if (clip.startNs != null && impact != null) (impact - PRE_ROLL_NS - clip.startNs) / 1000 else 0L
            val cut = ShotVideoRecorder.trim(clip.file, target, fromUs)
            return if (cut != null) target to clip.startNs?.let { it + cut * 1000 } else {
                // Keep it whole rather than lose it.
                if (clip.file.renameTo(target) || clip.file.copyTo(target, overwrite = true).exists()) target to clip.startNs else null to null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null to null
        } finally {
            clip.file.delete()
        }
    }

    /**
     * Saves changes made after the shot was captured (club, distance, tracer tuning). Once the snapshot
     * has been baked, it is baked again over [still] if what it shows changed.
     */
    suspend fun saveEdits(edited: ShotRecord, still: Bitmap?): ShotRecord {
        val saved = repository.update(edited.id) { current ->
            // The recording and snapshot belong to the background work; take the user's changes only.
            edited.copy(
                videoPath = current.videoPath,
                trackingPath = current.trackingPath,
                videoStartMs = current.videoStartMs,
                frameAspect = current.frameAspect,
                tracedFrom = current.tracedFrom,
                snapshotUri = current.snapshotUri
            )
        } ?: return edited
        val look = lookOf(saved)
        val bakedLook = synchronized(bakedLooks) { bakedLooks[saved.id] } ?: return saved
        if (still == null || look == bakedLook) return saved
        val uri = SnapshotExporter.createAnnotatedSnapshot(context, still, saved) ?: return saved
        saved.snapshotUri?.let { deleteImage(it) }
        synchronized(bakedLooks) { bakedLooks[saved.id] = look }
        return repository.update(saved.id) { it.copy(snapshotUri = uri.toString()) } ?: saved
    }

    /** What a snapshot shows of the shot. */
    private fun lookOf(r: ShotRecord): List<Any?> = listOf(r.club, r.tracerStyle, r.distanceYards, r.points)

    private fun deleteImage(uri: String) {
        try {
            context.contentResolver.delete(Uri.parse(uri), null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun device() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})"

    private companion object {
        /** Video kept from before the impact: the whole swing. */
        const val PRE_ROLL_NS = 2_500_000_000L
    }
}
