package com.golftracker.app

import com.golftracker.app.export.ShotBundle
import com.golftracker.app.export.ShotTracking
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.tracker.BallCandidate
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.ShotCaptureSession
import com.golftracker.app.tracker.ShotDebugArchive
import com.golftracker.app.tracker.ShotTrigger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Replays shot recordings saved by the app: the debug zips in Downloads/GolfBallTracer/shot-debug_*.zip
 * on the phone, or the golf-shot_*.zip recordings shared from the app (which add the video and
 * tracking.json). Put them in app/shots/ or point GOLF_SHOTS_DIR at a folder, then run:
 *   ./gradlew testDebugUnitTest --tests '*ShotReplayTest*' -i
 */
class ShotReplayTest {

    @Test
    fun archiveRoundTrips() {
        val frames = List(3) { i -> LumaFrame(4, 3, ByteArray(12) { (it * 7 + i).toByte() }, 1_000L + i) }
        val shot = CapturedShot(
            candidates = listOf(BallCandidate(0.1f, 0.2f, 1_001L, 30f)),
            teeX = 0.5f, teeY = 0.8f, ballRadius = 0.01f, launchTimestampNs = 1_001L, aspect = 0.45f,
            tapX = 0.51f, tapY = 0.79f, framesProcessed = 3,
            firstFrames = frames.take(1), recentFrames = frames.drop(1)
        )
        val bytes = ByteArrayOutputStream().also {
            ShotDebugArchive.write(shot, listOf(ScreenPoint(0.5f, 0.8f)), "test", it)
        }.toByteArray()
        val back = ShotDebugArchive.read(ByteArrayInputStream(bytes)).toCapturedShot()
        assertEquals(shot.copy(firstFrames = emptyList(), recentFrames = emptyList()),
            back.copy(firstFrames = emptyList(), recentFrames = emptyList()))
        assertEquals(1, back.firstFrames.size)
        assertEquals(2, back.recentFrames.size)
        assertArrayEquals(frames[2].data, back.recentFrames[1].data)
    }

    @Test
    fun replaySavedShots() {
        val dir = File(System.getenv("GOLF_SHOTS_DIR") ?: "shots")
        val files = dir.listFiles { f -> f.name.endsWith(".zip") && !f.name.startsWith("debug-session_") }?.sortedBy { it.name } ?: return
        for (file in files) {
            val archive = file.inputStream().use { ShotDebugArchive.read(it) }
            val info = archive.info
            val shot = archive.toCapturedShot()
            val frames = archive.frames
            val seconds = if (frames.size > 1) (frames.last().timestampNs - frames.first().timestampNs) / 1e9 else 0.0
            println("=== ${file.name}  (${info.device})")
            println("  grid ${frames.firstOrNull()?.width}x${frames.firstOrNull()?.height}, ${info.framesProcessed} frames processed, ${frames.size} kept over %.2f s".format(seconds))
            println("  tap (%.3f, %.3f) -> tee (%.3f, %.3f), ball radius %s, launch %s".format(
                info.tapX, info.tapY, info.teeX, info.teeY, info.ballRadius?.let { "%.4f".format(it) } ?: "not found",
                info.launchTimestampNs?.let { "at +%.2f s".format((it - frames.first().timestampNs) / 1e9) } ?: "not detected"))
            println("  ${shot.candidates.size} candidates on phone, tracer ${info.tracer.size} points")
            info.impactTimestampNs?.let {
                println("  strike ${if (info.heardImpact) "heard" else "seen"} at +%.2f s".format((it - frames.first().timestampNs) / 1e9))
            }
            trackingIn(file)?.let { t ->
                println("  traced from ${t.tracedFrom}: live followed ${t.liveTrackedSeconds?.let { "%.2f s".format(it) } ?: "nothing"}" +
                    ", video ${t.videoNote ?: "not traced"}")
            }
            val refit = BallFlightFitter.fit(shot)
            val startMs = frames.first().timestampNs / 1_000_000L
            println("  refit on this machine: ${refit.size} tracer points" +
                (if (refit.isEmpty()) "" else ", flight from +%.2f s to +%.2f s".format(
                    (refit.first().timestampMs - startMs) / 1000.0, (refit.last().timestampMs - startMs) / 1000.0)))

            // Re-run detection from the raw frames, so detector changes can be tried on real footage.
            val session = ShotCaptureSession()
            session.arm(info.tapX, info.tapY)
            frames.forEach { session.process(it) }
            val replayed = session.stop().copy(firstFrames = frames.take(info.firstFrameCount), recentFrames = frames.drop(info.firstFrameCount))
            val tracer = BallFlightFitter.fit(replayed)
            println("  re-detected: ${replayed.candidates.size} candidates, ball radius ${replayed.ballRadius}, launch ${replayed.launchTimestampNs != null}, tracer ${tracer.size} points")
            // Find the shot again the way the app does, without being told where the ball is.
            info.impactTimestampNs?.let { impact ->
                val events = ArrayList<ShotTrigger.Event>()
                val trigger = ShotTrigger(keepFrames = false) { events += it }
                trigger.startListening()
                var heard = !info.heardImpact
                for (f in frames) {
                    if (!heard && f.timestampNs >= impact) { trigger.onImpactSound(impact); heard = true }
                    trigger.onFrame(f)
                }
                trigger.finishCapture()
                val started = events.filterIsInstance<ShotTrigger.Event.Started>().firstOrNull()
                val again = events.filterIsInstance<ShotTrigger.Event.Captured>().firstOrNull()?.shot
                println("  auto-detect again: " + (started?.let {
                    "ball at (%.3f, %.3f), tracer %d points".format(it.ball.x, it.ball.y, again?.let { s -> BallFlightFitter.fit(s).size } ?: 0)
                } ?: "no shot found" + if (events.any { it is ShotTrigger.Event.Missed }) " (strike reported as missed)" else ""))
            }
            // Saved alongside so the new detections can be looked at frame by frame.
            File(dir, "replayed").mkdirs()
            File(dir, "replayed/${file.name}").outputStream().use { ShotDebugArchive.write(replayed, tracer, info.device, it) }
        }
    }

    /** tracking.json from a shared shot recording, if this is one. */
    private fun trackingIn(file: File): ShotTracking? = try {
        ZipFile(file).use { zip ->
            zip.getEntry(ShotBundle.TRACKING)?.let { ShotTracking.read(zip.getInputStream(it).readBytes().decodeToString()) }
        }
    } catch (e: Exception) {
        null
    }
}
