package com.golftracker.app.video

import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.ImpactBallLocator
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.ShotCaptureSession
import com.golftracker.app.tracker.TeeBall
import com.golftracker.app.tracker.Thumbnails
import com.golftracker.app.tracker.VideoAlignment
import java.io.File
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

    /** Whether the video's trace is better than the live one: it followed the ball at least as far. */
    fun isBetter(live: BallFlightFitter.Flight?, video: BallFlightFitter.Flight?): Boolean = when {
        video == null -> false
        live == null -> true
        else -> video.trackedSeconds >= live.trackedSeconds - 0.05
    }

    /** [startGuessNs] is roughly when the video's first frame was taken on the live clock, if known. */
    fun retrace(file: File, startGuessNs: Long?, live: CapturedShot): Result {
        val impact = live.impactTimestampNs ?: return Result(null, null, null, null, "no impact time")
        val thumbs = live.thumbnails
        if (thumbs.size < 3) return Result(null, null, null, null, "no live frames to line up with")
        val reader = VideoFrameReader(file)
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
        val flight = BallFlightFitter.fitFlight(shot)
        val note = "%.0f fps video, %s".format(fps ?: 0.0, if (flight == null) "no flight found" else "followed %.1f s".format(flight.trackedSeconds))
        return Result(shot, flight, offset, fps, note)
    }

    private fun ns(seconds: Double) = (seconds * 1e9).toLong()
}
