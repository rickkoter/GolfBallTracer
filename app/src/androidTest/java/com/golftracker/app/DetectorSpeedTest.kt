package com.golftracker.app

import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.golftracker.app.debug.DebugSession
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.ImpactBallLocator
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.ShotTrigger
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How fast the shot detector runs on this phone, replaying the debug recordings in
 * Downloads/GolfBallTracer. The camera delivers a frame about every 33 ms; a detector that takes longer
 * falls behind, and shots are then noticed, saved and recorded late.
 *
 * Install the app build first, then run without uninstalling it (so shot history is kept):
 *   ./gradlew assembleDebug assembleDebugAndroidTest
 *   adb install -r app/build/outputs/apk/debug/golfballtracer-debug.apk
 *   adb install -r app/build/outputs/apk/androidTest/debug/golfballtracer-debug-androidTest.apk
 *   adb shell am instrument -w -e class com.golftracker.app.DetectorSpeedTest com.golftracker.app.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s DetectorSpeed
 */
@RunWith(AndroidJUnit4::class)
class DetectorSpeedTest {

    @Test
    fun replayDebugRecordings() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GolfBallTracer")
        val files = dir.listFiles { f -> f.name.startsWith("debug-session_") }?.sortedBy { it.name }?.takeLast(MAX_FILES) ?: return
        for (file in files) {
            val frames = ArrayList<LumaFrame>()
            val info = file.inputStream().use { DebugSession.read(it) { f -> frames += f } }.info
            val strikes = ArrayDeque(info.events.filter { it.kind == "strike-heard" }.map { it.timestampNs }.sorted())
            var capturing = false
            val fitMs = ArrayList<Double>()
            val trigger = ShotTrigger(keepFrames = false) { e ->
                if (e is ShotTrigger.Event.Started) capturing = true
                if (e is ShotTrigger.Event.Captured) {
                    capturing = false
                    // Working out the flight happens after the capture, while listening for the next shot.
                    val t0 = System.nanoTime()
                    BallFlightFitter.fitBest(e.shot, null)
                    fitMs += (System.nanoTime() - t0) / 1e6
                }
            }
            trigger.startListening()
            val listen = ArrayList<Double>()
            val capture = ArrayList<Double>()
            val started = System.nanoTime()
            for (f in frames) {
                while (strikes.isNotEmpty() && f.timestampNs >= strikes.first() + 20_000_000L) trigger.onImpactSound(strikes.removeFirst())
                val was = capturing
                val t0 = System.nanoTime()
                trigger.onFrame(f)
                (if (was) capture else listen) += (System.nanoTime() - t0) / 1e6
                if (!trigger.wantsFrames) trigger.startListening()
            }
            val spent = (System.nanoTime() - started) / 1e9
            val filmed = (frames.last().timestampNs - frames.first().timestampNs) / 1e9
            fun List<Double>.summary() = if (isEmpty()) "-" else "mean %.1f ms, max %.0f ms (%d frames)".format(average(), max(), size)
            // Where the time goes in finding the ball at each strike.
            for (t in info.events.filter { it.kind == "strike-heard" }.map { it.timestampNs }) {
                val window = frames.filter { it.timestampNs in t - 2_400_000_000L..t + 700_000_000L }
                // The camera's path is followed frame by frame while listening, so it's known already.
                val offsets = ImpactBallLocator.offsetsOf(window)
                val t0 = System.nanoTime()
                val found = ImpactBallLocator.locate(window, t, heardNs = t, offsets = offsets) { line -> if (line.contains("[")) Log.i(TAG, line) }
                Log.i(TAG, "  ball finder at a strike: %.0f ms, %s".format((System.nanoTime() - t0) / 1e6, found?.let { "ball (%.3f, %.3f)".format(it.ball.x, it.ball.y) } ?: "nothing"))
            }
            if (fitMs.isNotEmpty()) Log.i(TAG, "  flight fitting: ${fitMs.joinToString { "%.0f ms".format(it) }}")
            Log.i(TAG, "${file.name} ${frames.firstOrNull()?.width}x${frames.firstOrNull()?.height}: %.1f s of frames took %.1f s. listening %s; capturing %s"
                .format(filmed, spent, listen.summary(), capture.summary()))
        }
    }

    private companion object {
        const val TAG = "DetectorSpeed"
        const val MAX_FILES = 3
    }
}
