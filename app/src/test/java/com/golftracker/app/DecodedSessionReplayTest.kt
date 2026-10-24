package com.golftracker.app

import com.golftracker.app.debug.DebugSession
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.ShotTrigger
import org.junit.Test
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * Replays debug recordings whose frames were decoded from their video on the Mac (the Oct 18 range
 * sessions recorded no analysis frames): a debug-session_* folder holding session.json and frames.raw,
 * the frames as in frames.bin but without the count. Strikes louder than +25 dB in the session's log are
 * fed in as heard. A check that shot detection still finds the ball filmed from a few metres off, at the
 * video's 60 fps, and at 30 (every other frame) for comparison:
 *   GOLF_SHOTS_DIR=app/shots/debug ./gradlew testDebugUnitTest --tests '*DecodedSessionReplayTest*' -i
 */
class DecodedSessionReplayTest {
    @Test
    fun replay() {
        val dir = File(System.getenv("GOLF_SHOTS_DIR") ?: "shots")
        val sessions = dir.walkTopDown().filter { it.isDirectory && File(it, "frames.raw").exists() && File(it, "session.json").exists() }
        for (d in sessions.sortedBy { it.name }) {
            val info = DebugSession.json.decodeFromString(DebugSession.Info.serializer(), File(d, "session.json").readText())
            val strikes = info.events.filter { it.kind == "strike-heard" && it.detail.trim().removePrefix("+").removeSuffix(" dB").toFloat() > 25 }.map { it.timestampNs }
            val frames = ArrayList<LumaFrame>()
            DataInputStream(File(d, "frames.raw").inputStream().buffered()).use { s ->
                try { while (true) { val ts = s.readLong(); val w = s.readInt(); val h = s.readInt(); val b = ByteArray(w * h); s.readFully(b); frames += LumaFrame(w, h, b, ts) } } catch (e: EOFException) {}
            }
            // The frames are from 60 fps video; every other one is what a 30 fps camera gives.
            val ballsAt = HashMap<Int, List<Pair<Float, Float>>>()
            for (step in listOf(1, 2)) {
            val used = frames.filterIndexed { i, _ -> i % step == 0 }
            val events = ArrayList<ShotTrigger.Event>()
            val trigger = ShotTrigger(keepFrames = false) { events += it }
            trigger.startListening()
            val pending = ArrayDeque(strikes.sorted())
            for (f in used) {
                while (pending.isNotEmpty() && f.timestampNs >= pending.first() + 20_000_000L) trigger.onImpactSound(pending.removeFirst())
                trigger.onFrame(f)
                if (!trigger.wantsFrames) trigger.startListening()
            }
            trigger.finishCapture()
            val fps = 60 / step
            ballsAt[fps] = events.filterIsInstance<ShotTrigger.Event.Started>().map { it.ball.x to it.ball.y }
            println("=== ${d.name} at $fps fps: ${used.size} frames ${frames.first().width}x${frames.first().height}, strikes ${strikes.map { "%.2f".format((it - info.startedNs) / 1e9) }}")
            val pose = info.verticalFovRadians?.let { fov -> info.deviceToWorld?.let { LaunchPhysics.CameraPose(fov, it.toFloatArray()) } }
            for (e in events) when (e) {
                is ShotTrigger.Event.Started -> println("   started ball (%.3f, %.3f) r %.4f".format(e.ball.x, e.ball.y, e.ball.radius))
                is ShotTrigger.Event.Captured -> println("   captured: " + (BallFlightFitter.fitBest(e.shot, pose)?.let { "seen %.2f s, %d frames, est %b".format(it.trackedSeconds, it.trackedFrames, it.estimated) + ", launched at about %.0f m/s".format(e.shot.ballRadius?.let { r -> val d = 0.714 * 0.021335 / r; kotlin.math.hypot(kotlin.math.hypot(it.launch.vx, it.launch.vy) * d / 0.714, it.launch.d1 * d) } ?: 0.0) } ?: "no flight"))
                is ShotTrigger.Event.Missed -> println("   missed")
            }
            }
            // The ball found at each rate: 60 fps is what's checked above; 30 has picked a shoe here.
            val same = ballsAt.getValue(60).size == ballsAt.getValue(30).size && ballsAt.getValue(60).zip(ballsAt.getValue(30)).all { (a, b) ->
                kotlin.math.abs(a.first - b.first) < 0.01f && kotlin.math.abs(a.second - b.second) < 0.01f
            }
            println("   ${if (same) "same balls at 60 and 30 fps" else "DIFFERENT at 30 fps: ${ballsAt[60]} vs ${ballsAt[30]}"}")
        }
    }
}
