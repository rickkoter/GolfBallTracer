package com.golftracker.app.video

import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.ImpactBallLocator
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.ShotCaptureSession
import com.golftracker.app.tracker.TeeBall
import com.golftracker.app.tracker.Thumbnails
import com.golftracker.app.tracker.VideoAlignment
import com.golftracker.app.tracker.fixed
import kotlin.math.roundToInt

/**
 * Traces a shot again from its recorded video.
 *
 * Live analysis has to keep up with the camera, so it looks at small frames and skips some. The video
 * has every frame, often 60 a second, at full resolution. Once the video is lined up with the live
 * frames ([VideoAlignment]), the same detection and fitting runs over it from the same ball on the tee.
 */
object VideoRetracer {

    /** The shot as seen in the video and its flight; [offsetNs] maps video time to the live clock. */
    class Result(
        val shot: CapturedShot?,
        val flight: BallFlightFitter.Flight?,
        val offsetNs: Long?,
        val frameRate: Double?,
        val note: String
    )

    /** Grid size for re-tracing: half again the live analysis, where a distant ball is a few pixels more. */
    private const val LONG_SIDE = 960
    private const val THUMB_SOURCE_LONG_SIDE = 192
    private const val ALIGN_BEFORE_S = 1.0
    private const val ALIGN_AFTER_S = 1.5
    private const val ALIGN_SEARCH_S = 1.0

    /**
     * Whether the video's trace is better than the live one: a followed flight beats an estimated one,
     * and otherwise it saw the ball at least as long.
     */
    fun isBetter(live: BallFlightFitter.Flight?, video: BallFlightFitter.Flight?): Boolean = when {
        video == null -> false
        live == null -> true
        live.estimated != video.estimated -> live.estimated
        else -> video.trackedSeconds >= live.trackedSeconds - 0.05
    }

    /**
     * [reader] decodes the shot's video; [startGuessNs] is roughly when its first frame was taken on the
     * live clock, if known.
     */
    fun retrace(reader: VideoFrameSource, startGuessNs: Long?, live: CapturedShot, camera: LaunchPhysics.CameraPose?): Result {
        val impact = live.impactTimestampNs ?: return Result(null, null, null, null, "no impact time")
        val thumbs = live.thumbnails
        if (thumbs.size < 3) return Result(null, null, null, null, "no live frames to line up with")
        val info = reader.info() ?: return Result(null, null, null, null, "couldn't open the video")

        // Without the recorder's estimate, the video ends about when the live capture did.
        val guess = startGuessNs ?: (thumbs.last().timestampNs - info.durationUs * 1000)
        val search = if (startGuessNs != null) ALIGN_SEARCH_S else 2 * ALIGN_SEARCH_S
        val liveNear = thumbs.filter { it.timestampNs in impact - ns(ALIGN_BEFORE_S)..impact + ns(ALIGN_AFTER_S) }

        // A video saved without its rotation looks sideways; try both quarter turns then.
        val sidewaysStored = info.width > info.height
        val rotations = if (info.rotation == 0 && sidewaysStored && live.aspect < 1f) listOf(90, 270) else listOf(info.rotation)
        var aligned: VideoAlignment.Result? = null
        var rotation = info.rotation
        for (r in rotations) {
            val videoThumbs = ArrayList<LumaFrame>()
            val from = (impact - ns(ALIGN_BEFORE_S + search) - guess) / 1000
            val to = (impact + ns(ALIGN_AFTER_S + search) - guess) / 1000
            reader.read(from, to, THUMB_SOURCE_LONG_SIDE, live.aspect, r) { videoThumbs += Thumbnails.of(it) }
            val result = VideoAlignment.align(liveNear, videoThumbs, guess, ns(search)) ?: continue
            if (aligned == null || result.difference < aligned.difference) {
                aligned = result
                rotation = r
            }
        }
        val offset = aligned?.offsetNs ?: return Result(null, null, null, info.frameRate, "video didn't line up with the camera frames")

        val frames = ArrayList<LumaFrame>()
        var session: ShotCaptureSession? = null
        var fps = info.frameRate
        val from = (impact - ns(ImpactBallLocator.FEED_BEFORE_S) - offset) / 1000
        val to = (thumbs.last().timestampNs - offset) / 1000
        reader.read(from, to, LONG_SIDE, live.aspect, rotation) { f ->
            val frame = LumaFrame(f.width, f.height, f.data, f.timestampNs + offset)
            val s = session ?: run {
                // Measure the frame rate from the first few frames if the file doesn't say.
                frames += frame
                if (fps == null && frames.size < 6) return@read
                val rate = fps ?: (1e9 / VideoAlignment.medianInterval(frames))
                fps = rate
                val far = (ShotCaptureSession.FAR * rate / 30.0).roundToInt().coerceIn(2, 8)
                ShotCaptureSession(far = far).also { created ->
                    created.arm(live.teeX, live.teeY, live.ballRadius?.let { TeeBall(live.teeX, live.teeY, it) }, live.launchTimestampNs)
                    frames.forEach(created::process)
                    frames.clear()
                    session = created
                }
                return@read
            }
            s.process(frame)
        }
        val shot = session?.stop()?.copy(impactTimestampNs = impact, heardImpact = live.heardImpact)
            ?: return Result(null, null, offset, fps, "no video frames around the shot")
        val flight = BallFlightFitter.fitBest(shot.copy(changedArea = live.changedArea), camera)
        val note = "${(fps ?: 0.0).fixed(0)} fps video, " + when {
            flight == null -> "no flight found"
            flight.estimated -> "ball seen ${flight.trackedSeconds.fixed(2)} s, rest estimated"
            else -> "followed ${flight.trackedSeconds.fixed(1)} s"
        }
        return Result(shot, flight, offset, fps, note)
    }

    private fun ns(seconds: Double) = (seconds * 1e9).toLong()
}

/**
 * Decodes a recorded video into [LumaFrame]s like the camera analysis produces: Android's MediaCodec, or
 * AVFoundation on iOS.
 */
interface VideoFrameSource {
    /** [rotation] is how far frames are turned to stand upright; [width]/[height] are as stored. */
    class Info(val durationUs: Long, val rotation: Int, val width: Int, val height: Int, val frameRate: Double?)

    fun info(): Info?

    /**
     * Calls [onFrame] for each frame from [fromUs] to [toUs] (video time), sampled with a long side of
     * [longSide], turned upright by [rotation] (the file's own when null) and center-cropped to
     * [uprightAspect]. Frame timestamps are the video time in nanoseconds. Returns how many were read.
     */
    fun read(fromUs: Long, toUs: Long, longSide: Int, uprightAspect: Float, rotation: Int?, onFrame: (LumaFrame) -> Unit): Int
}
