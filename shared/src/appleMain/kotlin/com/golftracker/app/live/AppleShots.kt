@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.golftracker.app.live

import com.golftracker.app.debug.DebugSession
import com.golftracker.app.export.ShotFiles
import com.golftracker.app.export.ShotTracking
import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.replay.ShotArchive
import com.golftracker.app.replay.toByteArray
import com.golftracker.app.replay.toNSData
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.RecordingCoverage
import com.golftracker.app.video.VideoRetracer
import com.golftracker.app.video.VideoFrameSource
import platform.Foundation.NSData

// Helpers for the iOS app: Kotlin's default arguments and data-class copies don't reach Swift, so the
// shot changes the app makes are spelled out here, along with calls that must not throw into Swift.

/** A new shot, tagged with the club and golfer, as the tracer screen saves it. */
fun makeShotRecord(
    club: String, golfer: Int, tracerStyle: String,
    launch: GpsCoordinate?, landing: GpsCoordinate?, distanceYards: Double, distanceMeters: Double,
    points: List<ScreenPoint>, tracerEstimated: Boolean
): ShotRecord = ShotRecord(
    club = club, golfer = golfer, tracerStyle = tracerStyle,
    launchLocation = launch, landingLocation = landing,
    distanceYards = distanceYards, distanceMeters = distanceMeters,
    points = points, tracerEstimated = tracerEstimated
)

/** The shot with its recording kept: video, tracking file, timing and where the ball should have landed. */
fun ShotRecord.withRecording(
    videoPath: String?, trackingPath: String?, videoStartMs: Long?, frameAspect: Float, estimatedLanding: LaunchPhysics.Landing?
): ShotRecord = copy(
    videoPath = videoPath, trackingPath = trackingPath, videoStartMs = videoStartMs, frameAspect = frameAspect,
    estimatedLanding = estimatedLanding, tracedFrom = ShotRecord.TRACED_FROM_CAMERA
)

/** The shot once traced again from its video: the tracer used, where it came from, and its snapshot. */
fun ShotRecord.withFinalTrace(
    points: List<ScreenPoint>, tracedFrom: String?, tracerEstimated: Boolean, estimatedLanding: LaunchPhysics.Landing?,
    videoStartMs: Long?, snapshotUri: String?
): ShotRecord = copy(
    points = points, tracedFrom = tracedFrom, tracerEstimated = tracerEstimated, estimatedLanding = estimatedLanding,
    videoStartMs = videoStartMs, snapshotUri = snapshotUri ?: this.snapshotUri
)

/** Changes made on the tracer screen after the shot was taken: tracer style, GPS and a tuned curve. */
fun ShotRecord.withEdits(
    tracerStyle: String, launch: GpsCoordinate?, landing: GpsCoordinate?, distanceYards: Double, distanceMeters: Double,
    points: List<ScreenPoint>
): ShotRecord = copy(
    tracerStyle = tracerStyle, launchLocation = launch, landingLocation = landing,
    distanceYards = distanceYards, distanceMeters = distanceMeters, points = points
)

fun ShotRecord.withSnapshot(snapshotUri: String?): ShotRecord = copy(snapshotUri = snapshotUri)

/** The shot with its ball pinned on GPS, and the distance that gives. */
fun ShotRecord.withPinnedBall(landing: GpsCoordinate, distanceYards: Double, distanceMeters: Double): ShotRecord =
    copy(landingLocation = landing, distanceYards = distanceYards, distanceMeters = distanceMeters)

/** The shot without its recording (its snapshot and distances stay), to make room for more. */
fun ShotRecord.withoutRecording(): ShotRecord = copy(videoPath = null, trackingPath = null, videoStartMs = null)

/** Where the ball should have come down, from its launch on camera; null if anything's missing or it isn't believable. */
fun estimateLanding(
    flight: BallFlightFitter.Flight?, shot: CapturedShot, camera: LaunchPhysics.CameraPose?,
    declinationDegrees: Double, tee: GpsCoordinate?
): LaunchPhysics.Landing? {
    if (flight == null || camera == null || tee == null) return null
    val radius = shot.ballRadius ?: return null
    return try {
        LaunchPhysics.estimateLanding(flight.launch, radius, camera.verticalFovRadians, camera.deviceToWorld, declinationDegrees, tee)
    } catch (e: Throwable) {
        println("estimateLanding: $e")
        null
    }
}

/** A landing for a ball pinned on GPS, so the ball locator can point to it. */
fun pinnedLanding(ball: GpsCoordinate, tee: GpsCoordinate?): LaunchPhysics.Landing {
    val meters = tee?.let { com.golftracker.app.tracker.TrajectoryMath.calculateGpsDistanceMeters(it, ball) } ?: 0.0
    return LaunchPhysics.Landing(ball, tee?.let { LaunchPhysics.bearing(it, ball) } ?: 0.0, meters, meters, 0.0, 0.0)
}

/** What wasn't recorded of a shot (frames missing, the video starting late), as on Android. */
fun coverageProblems(shot: CapturedShot, videoStartNs: Long?, videoExpected: Boolean): List<String> = try {
    RecordingCoverage.problems(shot.impactTimestampNs ?: shot.launchTimestampNs, shot.thumbnails.map { it.timestampNs }, videoStartNs, videoExpected)
} catch (e: Throwable) {
    emptyList()
}

/** The frames around a strike heard during a swing that didn't start a shot, as a missed-strike recording. */
fun encodeMissedStrike(frames: List<LumaFrame>, impactNs: Long, device: String): NSData? = try {
    if (frames.isEmpty()) null else {
        val shot = CapturedShot(
            candidates = emptyList(), teeX = 0.5f, teeY = 0.5f, ballRadius = null, launchTimestampNs = null,
            aspect = frames.first().width.toFloat() / frames.first().height, framesProcessed = frames.size,
            firstFrames = frames, impactTimestampNs = impactNs, heardImpact = true
        )
        ShotArchive.encode(shot, emptyList(), device).toNSData()
    }
} catch (e: Throwable) {
    null
}

/** tracking.json for a shot just captured. */
fun trackingOf(shot: CapturedShot, live: BallFlightFitter.Flight?, videoStartNs: Long?): ShotTracking =
    ShotTracking.of(shot, live).copy(videoStartNs = videoStartNs)

/** tracking.json once the video has been traced. */
fun ShotTracking.withVideoTrace(videoStartNs: Long?, result: VideoRetracer.Result?, tracedFrom: String): ShotTracking = copy(
    videoStartNs = videoStartNs,
    videoFrameRate = result?.frameRate,
    tracedFrom = tracedFrom,
    videoTracer = result?.flight?.tracer,
    videoEstimated = result?.flight?.estimated ?: false,
    videoSightings = result?.flight?.sightings ?: emptyList(),
    videoTrackedSeconds = result?.flight?.trackedSeconds,
    videoNote = result?.note
)

fun encodeTracking(tracking: ShotTracking): String = ShotTracking.encode(tracking)

/** Traces a shot again from its video, catching anything thrown (it would end the app in Swift). */
fun retraceVideo(reader: VideoFrameSource, startGuessNs: Long?, live: CapturedShot, camera: LaunchPhysics.CameraPose?): VideoRetracer.Result? = try {
    VideoRetracer.retrace(reader, startGuessNs, live, camera)
} catch (e: Throwable) {
    println("VideoRetracer: $e")
    null
}

/** The analysis frames and detections of a shot, as capture.zip (and shot-debug zips). */
fun encodeCapture(shot: CapturedShot, tracer: List<ScreenPoint>, device: String): NSData? = try {
    ShotArchive.encode(shot, tracer, device).toNSData()
} catch (e: Throwable) {
    null
}

/** A shot's whole recording in one golf-shot zip, for sharing. */
fun encodeShotBundle(shot: ShotRecord, tracking: NSData?, capture: NSData?, video: NSData?): NSData? = try {
    ShotFiles.bundle(shot, tracking?.toByteArray(), capture?.toByteArray(), video?.toByteArray()).toNSData()
} catch (e: Throwable) {
    println("ShotFiles.bundle: $e")
    null
}

/** session.json for a debug recording. */
fun debugSessionJson(
    device: String, startedNs: Long, endedNs: Long, frameCount: Int, framesDropped: Int,
    audioSampleRate: Int, audioStartNs: Long, audioSamples: Long, videoStartNs: Long?,
    micEnabled: Boolean, events: List<DebugSession.Event>, camera: LaunchPhysics.CameraPose?
): String = DebugSession.json.encodeToString(
    DebugSession.Info.serializer(),
    DebugSession.Info(
        device = device, startedNs = startedNs, endedNs = endedNs, frameCount = frameCount, framesDropped = framesDropped,
        audioSampleRate = audioSampleRate.takeIf { it > 0 }, audioStartNs = audioStartNs.takeIf { audioSampleRate > 0 },
        audioSamples = audioSamples, videoStartNs = videoStartNs, micEnabled = micEnabled, events = events,
        verticalFovRadians = camera?.verticalFovRadians, deviceToWorld = camera?.deviceToWorld?.toList()
    )
)

fun wavHeader(sampleRate: Int, dataBytes: Long): NSData = DebugSession.wavHeader(sampleRate, dataBytes).toNSData()

/**
 * A shot as captured, read back from its capture.zip (shot.json and frames.bin), for checking the video
 * re-trace on iOS against Android's. Every saved frame stands in for the live thumbnails it's lined up with.
 */
fun capturedShotFromCapture(capture: NSData): CapturedShot? = try {
    val zip = com.golftracker.app.replay.ZipArchive(capture.toByteArray())
    val info = kotlinx.serialization.json.Json.parseToJsonElement(zip.read("shot.json")!!.decodeToString()) as kotlinx.serialization.json.JsonObject
    fun num(key: String) = (info[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" }
    val frames = com.golftracker.app.replay.RecordingReplay.readFrames(zip.read("frames.bin")!!)
    val first = num("firstFrameCount")?.toInt() ?: 0
    val candidates = (info["candidates"] as kotlinx.serialization.json.JsonArray).mapIndexed { i, c ->
        val v = (c as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content.toFloat() }
        val t = ((info["candidateTimestampsNs"] as kotlinx.serialization.json.JsonArray)[i] as kotlinx.serialization.json.JsonPrimitive).content.toLong()
        com.golftracker.app.tracker.BallCandidate(v[0], v[1], t, v[2])
    }
    CapturedShot(
        candidates = candidates,
        teeX = num("teeX")!!.toFloat(), teeY = num("teeY")!!.toFloat(),
        ballRadius = num("ballRadius")?.toFloat(), launchTimestampNs = num("launchTimestampNs")?.toLong(),
        aspect = num("aspect")!!.toFloat(), tapX = num("tapX")!!.toFloat(), tapY = num("tapY")!!.toFloat(),
        framesProcessed = num("framesProcessed")?.toInt() ?: frames.size,
        firstFrames = frames.take(first), recentFrames = frames.drop(first),
        impactTimestampNs = num("impactTimestampNs")?.toLong(), heardImpact = num("heardImpact") == "true",
        thumbnails = frames.map { com.golftracker.app.tracker.Thumbnails.of(it) }
    )
} catch (e: Throwable) {
    println("capturedShotFromCapture: $e")
    null
}

/** A shot's tracking.json read back. */
fun readTracking(text: String): ShotTracking? = try { ShotTracking.read(text) } catch (e: Throwable) { null }
