package com.golftracker.app.export

import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    /** Whether each tracer is mostly estimated from a brief sighting, and where the ball was actually seen. */
    val liveEstimated: Boolean = false,
    val liveSightings: List<ScreenPoint> = emptyList(),
    val videoEstimated: Boolean = false,
    val videoSightings: List<ScreenPoint> = emptyList(),
    /** Spots the live detector saw, as [x, y, score, ms since impact]. */
    val liveCandidates: List<List<Float>> = emptyList()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun of(shot: CapturedShot, live: BallFlightFitter.Flight?) = ShotTracking(
            teeX = shot.teeX,
            teeY = shot.teeY,
            ballRadius = shot.ballRadius,
            aspect = shot.aspect,
            impactTimestampNs = shot.impactTimestampNs,
            heardImpact = shot.heardImpact,
            launchTimestampNs = shot.launchTimestampNs,
            tracedFrom = ShotRecord.TRACED_FROM_CAMERA,
            liveTracer = live?.tracer ?: emptyList(),
            liveTrackedSeconds = live?.trackedSeconds,
            liveEstimated = live?.estimated ?: false,
            liveSightings = live?.sightings ?: emptyList(),
            liveCandidates = shot.candidates.map {
                listOf(it.x, it.y, it.score, ((it.timestampNs - (shot.impactTimestampNs ?: 0L)) / 1e6).toFloat())
            }
        )

        fun encode(tracking: ShotTracking): String = json.encodeToString(serializer(), tracking)

        fun read(text: String): ShotTracking = json.decodeFromString(serializer(), text)
    }
}
