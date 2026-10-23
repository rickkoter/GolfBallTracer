package com.golftracker.app

import com.golftracker.app.debug.DebugSession
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.OnsetDetector
import com.golftracker.app.tracker.ShotTrigger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.log10

/**
 * Replays debug recordings made with "Start debug recording" in the app's settings
 * (Downloads/GolfBallTracer/debug-session_*.zip on the phone). Put them in app/shots/ or point
 * GOLF_SHOTS_DIR at a folder, then run:
 *   ./gradlew testDebugUnitTest --tests '*DebugSessionReplayTest*' -i
 *
 * For each recording it prints what the app did at the time, then hears the sound again with the
 * current strike detection, lists the sharpest sounds that weren't taken for strikes (to see how far
 * below the thresholds a missed strike was), and runs the shot detection over the frames again.
 */
class DebugSessionReplayTest {

    @Test
    fun readsARecordingBack() {
        val info = DebugSession.Info(
            device = "test", startedNs = 1_000L, endedNs = 2_000L, frameCount = 2, framesDropped = 0,
            audioSampleRate = 48_000, audioStartNs = 1_100L, audioSamples = 4, micEnabled = true,
            events = listOf(DebugSession.Event(1_500L, "strike-heard", "+20.0 dB"))
        )
        val samples = shortArrayOf(0, 1000, -1000, 32767)
        val frames = List(2) { i -> LumaFrame(3, 2, ByteArray(6) { (it + i).toByte() }, 1_200L + i) }
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(DebugSession.INFO))
                zip.write(DebugSession.json.encodeToString(DebugSession.Info.serializer(), info).toByteArray())
                zip.putNextEntry(ZipEntry(DebugSession.AUDIO))
                DebugSession.writeWavHeader(zip, 48_000, samples.size * 2L)
                samples.forEach { zip.write(it.toInt() and 0xFF); zip.write((it.toInt() shr 8) and 0xFF) }
                zip.putNextEntry(ZipEntry(DebugSession.FRAMES))
                val data = DataOutputStream(zip)
                data.writeInt(frames.size)
                for (f in frames) { data.writeLong(f.timestampNs); data.writeInt(f.width); data.writeInt(f.height); data.write(f.data) }
                data.flush()
                zip.closeEntry()
            }
        }.toByteArray()

        val back = ArrayList<LumaFrame>()
        val loaded = DebugSession.read(ByteArrayInputStream(bytes)) { back += it }
        assertEquals(info, loaded.info)
        assertArrayEquals(samples, loaded.audio)
        assertEquals(frames.map { it.timestampNs }, back.map { it.timestampNs })
        assertArrayEquals(frames[1].data, back[1].data)
    }

    @Test
    fun replaySavedSessions() {
        val dir = File(System.getenv("GOLF_SHOTS_DIR") ?: "shots")
        val files = dir.walkTopDown().filter { it.name.startsWith("debug-session_") && it.name.endsWith(".zip") }.sortedBy { it.name }.toList()
        for (file in files) {
            val loaded = file.inputStream().use { DebugSession.read(it) }
            val info = loaded.info
            fun at(ns: Long) = "%6.2f s".format((ns - info.startedNs) / 1e9)
            println("=== ${file.name}  (${info.device})")
            println("  %.1f s, %d frames (%d dropped), sound %s, video %s".format(
                (info.endedNs - info.startedNs) / 1e9, info.frameCount, info.framesDropped,
                info.audioSampleRate?.let { "%d Hz %.1f s".format(it, info.audioSamples.toDouble() / it) } ?: "none",
                if (info.videoStartNs != null) "from ${at(info.videoStartNs)}" else "none"))
            println("  What the app did:")
            info.events.forEach { println("    ${at(it.timestampNs)}  ${it.kind}  ${it.detail}") }

            // Hear it again, keeping every window so near misses can be listed.
            val onsets = ArrayList<Long>()
            val audio = loaded.audio
            val rate = info.audioSampleRate
            val audioStart = info.audioStartNs
            if (audio != null && rate != null && audioStart != null) {
                class Window(val sample: Long, val rms: Float, val background: Float, val previous: Float) {
                    val overBackgroundDb get() = 20 * log10(rms / maxOf(background, 1f))
                    val riseDb get() = 20 * log10(rms / maxOf(previous, 1f))
                }
                val windows = ArrayList<Window>()
                val detector = OnsetDetector(rate) { s, rms, bg, prev -> windows += Window(s, rms, bg, prev) }
                fun time(sample: Long) = audioStart + sample * 1_000_000_000L / rate
                detector.feed(audio, audio.size, 0L).forEach { onsets += time(it.sample) }
                println("  Strikes heard now (needs +%.1f dB over the background, +%.1f dB over the moment before, level ≥ %.0f):".format(
                    20 * log10(OnsetDetector.RATIO), 20 * log10(OnsetDetector.RISE), OnsetDetector.MIN_RMS))
                if (onsets.isEmpty()) println("    none")
                onsets.forEach { println("    ${at(it)}") }
                // The sharpest sounds of all, one per half second: a strike missed should be among them.
                // (Skipping the first moments, which have nothing before them to rise from.)
                val sharpest = windows.filter { it.riseDb > 3 && it.sample > rate / 10 }.sortedByDescending { minOf(it.overBackgroundDb, it.riseDb * 2) }
                val listed = ArrayList<Window>()
                for (w in sharpest) {
                    if (listed.any { kotlin.math.abs(it.sample - w.sample) < rate / 2 }) continue
                    listed += w
                    if (listed.size == 12) break
                }
                println("  Sharpest sounds:")
                listed.sortedBy { it.sample }.forEach { w ->
                    val heard = onsets.any { kotlin.math.abs(it - time(w.sample)) < 20_000_000L }
                    println("    ${at(time(w.sample))}  level %5.0f  %+5.1f dB over background  %+5.1f dB over before%s".format(
                        w.rms, w.overBackgroundDb, w.riseDb, if (heard) "  ← heard" else ""))
                }
            } else {
                println("  No sound in this recording")
            }

            // Run the shot detection again over the frames, with the strikes heard now.
            val events = ArrayList<ShotTrigger.Event>()
            val trigger = ShotTrigger(keepFrames = false, watchWithoutSound = audio == null) { events += it }
            trigger.startListening()
            val pending = ArrayDeque(onsets.sorted())
            var lastFrameNs = 0L
            // How long the detector takes per frame, while listening and while capturing a shot: on the
            // phone it has about 33 ms, and falls behind the camera if it takes longer.
            val listenMs = ArrayList<Double>()
            val captureMs = ArrayList<Double>()
            file.inputStream().use { input ->
                DebugSession.read(input) { frame ->
                    // A strike reaches the detector a little after it happened, as on the phone.
                    while (pending.isNotEmpty() && frame.timestampNs >= pending.first() + 20_000_000L) trigger.onImpactSound(pending.removeFirst())
                    val capturing = trigger.isCapturing
                    val t0 = System.nanoTime()
                    trigger.onFrame(frame)
                    (if (capturing) captureMs else listenMs) += (System.nanoTime() - t0) / 1e6
                    lastFrameNs = frame.timestampNs
                    // Captures end on their own; listen again straight away, as the app does.
                    if (!trigger.wantsFrames) trigger.startListening()
                }
            }
            trigger.finishCapture()
            fun List<Double>.summary() = if (isEmpty()) "-" else "mean %.1f ms, max %.0f ms over %d frames".format(average(), max(), size)
            println("  Detector time per frame on this machine: listening ${listenMs.summary()}; capturing ${captureMs.summary()}")
            println("  Shot detection now:")
            if (events.isEmpty()) println("    nothing")
            for (e in events) when (e) {
                is ShotTrigger.Event.Started -> println("    ${at(e.impactNs)}  shot started: ball at (%.3f, %.3f), %s".format(e.ball.x, e.ball.y, if (e.heard) "heard" else "seen only"))
                is ShotTrigger.Event.Captured -> {
                    val pose = info.verticalFovRadians?.let { fov -> info.deviceToWorld?.let { LaunchPhysics.CameraPose(fov, it.toFloatArray()) } }
                    val flight = BallFlightFitter.fitBest(e.shot, pose)
                    println("    ${at(e.shot.impactTimestampNs ?: lastFrameNs)}  captured: ${when {
                        flight == null -> "no ball flight found"
                        flight.estimated -> "ball seen %.2f s in %d frames, rest estimated".format(flight.trackedSeconds, flight.trackedFrames)
                        else -> "ball followed %.2f s".format(flight.trackedSeconds)
                    }}")
                }
                is ShotTrigger.Event.Missed -> println("    ${at(e.impactNs)}  strike heard, no ball seen leaving${if (e.duringSwing) " (during a swing)" else ""}")
            }
        }
    }
}
