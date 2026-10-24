package com.golftracker.app.model

import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.TrajectoryMath
import kotlinx.serialization.Serializable
import java.util.UUID

enum class ClubType(val displayName: String, val category: String) {
    DRIVER("Driver", "Wood"),
    WOOD_3("3-Wood", "Wood"),
    HYBRID_3("3-Hybrid", "Hybrid"),
    IRON_4("4-Iron", "Iron"),
    IRON_5("5-Iron", "Iron"),
    IRON_6("6-Iron", "Iron"),
    IRON_7("7-Iron", "Iron"),
    IRON_8("8-Iron", "Iron"),
    IRON_9("9-Iron", "Iron"),
    PITCHING_WEDGE("PW", "Wedge"),
    GAP_WEDGE("GW", "Wedge"),
    SAND_WEDGE("SW", "Wedge"),
    LOB_WEDGE("LW", "Wedge"),
    PUTTER("Putter", "Putter")
}

enum class TracerStyle(val displayName: String, val colorHex: Long, val secondaryHex: Long) {
    PRO_LIME("Pro Lime", 0xFF00FF66, 0x8800E676),
    CYAN_SPARK("Cyan Spark", 0xFF00E5FF, 0x8800B0FF),
    FIRE_RED("Fire Red", 0xFFFF3D00, 0x88FF9100),
    GOLD_GLOW("Gold Glow", 0xFFFFD700, 0x88FFAB00),
    NEON_PURPLE("Neon Purple", 0xFFE040FB, 0x88D500F9)
}

@Serializable
data class GpsCoordinate(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracyMeters: Float = 0f
)

@Serializable
data class ScreenPoint(
    val x: Float,
    val y: Float,
    val timestampMs: Long = System.currentTimeMillis(),
    val confidence: Float = 1.0f
)

@Serializable
data class ShotRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val club: String = ClubType.DRIVER.displayName,
    /** Which golfer (1 to [MAX_GOLFERS]) hit it; null for shots from before golfers were tagged. */
    val golfer: Int? = null,
    val tracerStyle: String = TracerStyle.PRO_LIME.name,
    
    // GPS Telemetry
    val launchLocation: GpsCoordinate? = null,
    val landingLocation: GpsCoordinate? = null,
    val distanceYards: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val elevationDeltaMeters: Double = 0.0,
    
    // Optical & Ballistic Telemetry
    val launchAngleDegrees: Float = 14.5f,
    val estimatedApexYards: Float = 32f,
    val flightTimeSeconds: Float = 5.2f,
    
    // Screen Trajectory Points
    val points: List<ScreenPoint> = emptyList(),
    
    // Media & Assets
    val snapshotUri: String? = null,
    val notes: String = "",

    // The shot's recording: its video and tracking data, kept for the most recent shots only
    val videoPath: String? = null,
    val trackingPath: String? = null,
    /** Time of the video's first frame on the clock the tracer points are timed with, to play them in step. */
    val videoStartMs: Long? = null,
    /** Width / height of the view the tracer points are fractions of. */
    val frameAspect: Float? = null,
    /** Where the tracer came from: [TRACED_FROM_CAMERA] (live analysis) or [TRACED_FROM_VIDEO] (the recording). */
    val tracedFrom: String? = null,
    /** The ball was only seen leaving; most of the tracer is estimated from its launch (drawn dashed). */
    val tracerEstimated: Boolean = false,
    /** Where the ball is estimated to have landed, worked out from its launch on camera, for the ball locator. */
    val estimatedLanding: LaunchPhysics.Landing? = null
) {
    val hasRecording: Boolean get() = videoPath != null || trackingPath != null

    companion object {
        const val MAX_GOLFERS = 4
        const val TRACED_FROM_CAMERA = "camera"
        const val TRACED_FROM_VIDEO = "video"
    }
}

/** Part of a tracer worked out rather than seen: drawn dashed. */
val ScreenPoint.isEstimated: Boolean get() = confidence < 0.5f

/**
 * A tracer cut into stretches that were seen and stretches that were estimated, in order, as
 * (points, estimated); each stretch starts where the one before ended, so they join up when drawn.
 */
fun tracerRuns(points: List<ScreenPoint>): List<Pair<List<ScreenPoint>, Boolean>> {
    val runs = ArrayList<Pair<List<ScreenPoint>, Boolean>>()
    var current = ArrayList<ScreenPoint>()
    var estimated = points.firstOrNull()?.isEstimated ?: return runs
    for (p in points) {
        if (p.isEstimated != estimated && current.isNotEmpty()) {
            runs += current to estimated
            current = arrayListOf(current.last())
            estimated = p.isEstimated
        }
        current += p
    }
    if (current.size >= 2) runs += current to estimated
    return runs
}

/** Whether a tracer is in pixels (the demo flight) rather than fractions of the screen. */
fun isPixelTracer(points: List<ScreenPoint>): Boolean = points.any { it.x > 5f || it.y > 5f }

/** How far a shot went, in yards, and whether that's an estimate rather than measured on GPS. */
data class ShotDistance(val yards: Double, val estimated: Boolean) {
    /** "142 YDS", or "~142 YDS" for an estimate. */
    val label: String get() = (if (estimated) "~" else "") + "%.0f YDS".format(yards)
}

/**
 * The shot's distance: from the tee to where its ball was pinned on GPS; otherwise from the landing
 * estimated from its launch on camera; otherwise unknown. The distance recorded live while walking to
 * the ball isn't used, since until the ball is pinned it's only how far the phone has moved.
 */
val ShotRecord.distance: ShotDistance?
    get() {
        val tee = launchLocation
        val ball = landingLocation
        if (tee != null && ball != null) {
            return ShotDistance(TrajectoryMath.metersToYards(TrajectoryMath.calculateGpsDistanceMeters(tee, ball)), estimated = false)
        }
        return estimatedLanding?.let { ShotDistance(TrajectoryMath.metersToYards(it.totalMeters), estimated = true) }
    }
