package com.golftracker.app.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import com.golftracker.app.export.ShotBundle
import com.golftracker.app.export.ShotDebugExporter
import com.golftracker.app.export.ShotTracking
import com.golftracker.app.export.write
import com.golftracker.app.export.SnapshotExporter
import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.ShotDebugArchive
import com.golftracker.app.video.ShotVideoRecorder
import com.golftracker.app.video.VideoFrameReader
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
        val stillShift: Pair<Float, Float>,
        /** The camera's pose while filming, for estimating a flight seen only briefly. */
        val camera: LaunchPhysics.CameraPose?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // One video at a time: decoding and re-tracing is heavy, and shots come one after another anyway.
    private val retraceLock = Mutex()
    private val _updates = MutableSharedFlow<Update>(extraBufferCapacity = 16)
    val updates: SharedFlow<Update> = _updates

    /** What each baked snapshot shows, so later edits know whether to bake it again. */
    private val bakedLooks = HashMap<String, List<Any?>>()

    // Shots still being worked on, and those of them the user has since thrown away.
    private val running = HashSet<String>()
    private val discarded = HashSet<String>()

    fun process(new: NewShot) {
        val id = new.record.id
        synchronized(running) { running += id }
        scope.launch {
            try {
                run(new)
            } finally {
                val drop = synchronized(running) {
                    running -= id
                    discarded.remove(id)
                }
                if (drop) delete(id)
            }
        }
    }

    /**
     * Throws a shot away with its recording and snapshot: one the user said wasn't worth keeping. A shot
     * still being saved or re-traced is thrown away once that's done.
     */
    fun discard(shotId: String) {
        val now = synchronized(running) {
            if (shotId in running) {
                discarded += shotId
                false
            } else {
                true
            }
        }
        if (now) scope.launch { delete(shotId) }
    }

    private fun isDiscarded(shotId: String) = synchronized(running) { shotId in discarded }

    private suspend fun delete(shotId: String) {
        repository.find(shotId)?.snapshotUri?.let { deleteImage(it) }
        repository.deleteShot(shotId)
        synchronized(bakedLooks) { bakedLooks.remove(shotId) }
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
        var tracking = ShotTracking.of(shot, new.liveFlight).copy(videoStartNs = videoStartNs)
        ShotTracking.write(tracking, trackingFile)

        // 2. Into the history straight away, with where the ball should have landed for the ball locator.
        val liveLanding = landingOf(new.liveFlight, shot, new.camera, new.record.launchLocation)
        repository.saveShot(
            new.record.copy(
                estimatedLanding = liveLanding,
                videoPath = video?.path,
                trackingPath = trackingFile.path,
                videoStartMs = videoStartNs?.let { it / 1_000_000L },
                frameAspect = shot.aspect,
                tracedFrom = ShotRecord.TRACED_FROM_CAMERA
            )
        )
        val debugExport = ShotDebugExporter.save(context, shot, liveTracer)
        _updates.emit(Update.Saved(id, debugExport, video != null))
        // Thrown away already: no need to trace it again.
        if (isDiscarded(id)) return

        // 3. Trace again from the video.
        var points = new.record.points
        var tracedFrom = ShotRecord.TRACED_FROM_CAMERA
        var estimated = new.liveFlight?.estimated ?: false
        var startNs = videoStartNs
        val note: String
        var usedFlight: BallFlightFitter.Flight? = null
        if (video == null) {
            note = "No video was recorded"
        } else {
            val result = retraceLock.withLock {
                try {
                    VideoRetracer.retrace(VideoFrameReader(video), videoStartNs, shot, new.camera)
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
                estimated = videoFlight.estimated
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
                videoEstimated = result?.flight?.estimated ?: false,
                videoSightings = result?.flight?.sightings ?: emptyList(),
                videoTrackedSeconds = result?.flight?.trackedSeconds,
                videoNote = result?.note
            )
            ShotTracking.write(tracking, trackingFile)
        }

        // 4. Bake the snapshot with the final tracer, unless the curve was tuned by hand meanwhile.
        if (isDiscarded(id)) return
        val current = repository.find(id) ?: return
        val edited = current.points != new.record.points
        val final = if (edited) current else current.copy(points = points, tracedFrom = tracedFrom, tracerEstimated = estimated)
        val snapshot = new.still?.let { SnapshotExporter.createAnnotatedSnapshot(context, it, final) }
        val landing = usedFlight?.let { landingOf(it, shot, new.camera, current.launchLocation) } ?: liveLanding
        val saved = repository.update(id) {
            it.copy(
                estimatedLanding = landing,
                points = final.points,
                tracedFrom = final.tracedFrom,
                tracerEstimated = final.tracerEstimated,
                videoStartMs = startNs?.let { ns -> ns / 1_000_000L },
                snapshotUri = snapshot?.toString() ?: it.snapshotUri
            )
        }
        if (saved != null) synchronized(bakedLooks) { bakedLooks[id] = lookOf(saved) }
        _updates.emit(Update.Retraced(id, usedFlight, if (edited || usedFlight == null) null else points, note))
    }

    /**
     * Saves the frames around a strike that was heard during a swing but didn't start a shot, to
     * Downloads/GolfBallTracer/missed-strike_*.zip, so ShotReplayTest can show why. At most one every
     * [MISSED_STRIKE_GAP_MS], so a noisy range doesn't fill the phone.
     */
    fun saveMissedStrike(frames: List<LumaFrame>, impactNs: Long, onSaved: (String?) -> Unit) {
        if (frames.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastMissedSaveMs < MISSED_STRIKE_GAP_MS) return
            lastMissedSaveMs = now
        }
        scope.launch {
            val shot = CapturedShot(
                candidates = emptyList(), teeX = 0.5f, teeY = 0.5f, ballRadius = null, launchTimestampNs = null,
                aspect = frames.first().width.toFloat() / frames.first().height, framesProcessed = frames.size,
                firstFrames = frames, impactTimestampNs = impactNs, heardImpact = true
            )
            onSaved(ShotDebugExporter.save(context, shot, emptyList(), kind = "missed-strike"))
        }
    }

    private var lastMissedSaveMs = Long.MIN_VALUE / 2

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
                tracerEstimated = current.tracerEstimated,
                snapshotUri = current.snapshotUri,
                estimatedLanding = current.estimatedLanding
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

    /**
     * Where the ball should have come down, from its launch on camera, the camera's pose while filming and
     * the tee's GPS position; null when any of those is missing or the launch isn't a believable one.
     */
    private fun landingOf(
        flight: BallFlightFitter.Flight?, shot: CapturedShot, camera: LaunchPhysics.CameraPose?, tee: GpsCoordinate?
    ): LaunchPhysics.Landing? {
        if (flight == null || camera == null || tee == null) return null
        val radius = shot.ballRadius ?: return null
        return try {
            val declination = android.hardware.GeomagneticField(
                tee.latitude.toFloat(), tee.longitude.toFloat(), tee.altitude.toFloat(), System.currentTimeMillis()
            ).declination.toDouble()
            LaunchPhysics.estimateLanding(flight.launch, radius, camera.verticalFovRadians, camera.deviceToWorld, declination, tee)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
        const val MISSED_STRIKE_GAP_MS = 15_000L
    }
}
