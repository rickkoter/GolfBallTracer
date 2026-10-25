package com.golftracker.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.golftracker.app.export.ShotTracking
import com.golftracker.app.export.ShotVideoExporter
import com.golftracker.app.model.ShotRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Exports a real shot's video with its tracer drawn in and checks the result: cropped to the traced view,
 * as long as the recording, and the tracer there after the strike but not before it. Frames are saved as
 * PNGs to look at.
 *
 * Copy a shot's recording (video.mp4 and tracking.json from its shot_media folder) into the app's files,
 * then run without uninstalling the app (so shot history is kept):
 *   ./gradlew assembleDebug assembleDebugAndroidTest
 *   adb install -r app/build/outputs/apk/debug/golfballtracer-debug.apk
 *   adb install -r app/build/outputs/apk/androidTest/debug/golfballtracer-debug-androidTest.apk
 *   adb push video.mp4 tracking.json /data/local/tmp/
 *   adb shell run-as com.golftracker.app sh -c 'mkdir -p files/export-test && cp /data/local/tmp/video.mp4 /data/local/tmp/tracking.json files/export-test/'
 *   adb shell am instrument -w -e class com.golftracker.app.ShotVideoExportTest com.golftracker.app.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s ShotVideoExport
 *   adb exec-out run-as com.golftracker.app cat files/export-test/after.png > after.png
 */
@RunWith(AndroidJUnit4::class)
class ShotVideoExportTest {

    @Test
    fun exportsTheVideoWithItsTracer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "export-test")
        val video = File(dir, "video.mp4")
        val trackingFile = File(dir, "tracking.json")
        if (!video.exists() || !trackingFile.exists()) {
            Log.w(TAG, "No recording in ${dir.path}; skipped")
            return@runBlocking
        }
        val tracking = ShotTracking.read(trackingFile.readText())
        val points = tracking.videoTracer?.takeIf { it.size > 1 } ?: tracking.liveTracer
        val shot = ShotRecord(
            club = "7-Iron",
            golfer = 2,
            points = points,
            videoPath = video.path,
            videoStartMs = tracking.videoStartNs?.let { it / 1_000_000 },
            frameAspect = tracking.aspect
        )

        val out = File(dir, "exported.mp4")
        val started = System.nanoTime()
        ShotVideoExporter.render(context, shot, video, out)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("no video written", out.length() > 0)

        val input = MediaMetadataRetriever().apply { setDataSource(video.path) }
        val output = MediaMetadataRetriever().apply { setDataSource(out.path) }
        try {
            val w = output.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = output.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rotation = output.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (uw, uh) = if (rotation == 90 || rotation == 270) h to w else w to h
            val inMs = input.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val outMs = output.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            Log.i(TAG, "made in %.1f s: %dx%d (rotation %d, upright %dx%d, aspect %.3f vs traced %.3f), %d ms of %d ms, %d KB".format(
                seconds, w, h, rotation, uw, uh, uw.toFloat() / uh, tracking.aspect, outMs, inMs, out.length() / 1024))
            assertEquals("cropped to the traced view", tracking.aspect, uw.toFloat() / uh, 0.02f)
            assertTrue("as long as the recording: $outMs vs $inMs ms", abs(outMs - inMs) < 400)

            // The tracer: lime around its points once the ball has flown, not before the strike.
            val startMs = shot.videoStartMs!!
            val strikeMs = points.first().timestampMs - startMs
            val endMs = (points.last().timestampMs - startMs).coerceAtMost(outMs - 100)
            val before = output.getFrameAtTime((strikeMs - 800).coerceAtLeast(0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val after = output.getFrameAtTime(endMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            before.save(File(dir, "before.png"))
            after.save(File(dir, "after.png"))
            val limeBefore = limeAround(before, points)
            val limeAfter = limeAround(after, points)
            Log.i(TAG, "tracer pixels: %d before the strike, %d at %.1f s".format(limeBefore, limeAfter, endMs / 1000.0))
            assertTrue("tracer drawn after the strike ($limeAfter) and not before ($limeBefore)", limeAfter > 200 && limeBefore * 5 < limeAfter)
        } finally {
            input.release()
            output.release()
        }

        // Saving to Movies/GolfTracker works too; the test's copy is removed again.
        val outcome = ShotVideoExporter.export(context, listOf(shot))
        Log.i(TAG, outcome.summary)
        assertEquals(1, outcome.saved.size)
        outcome.saved.forEach { context.contentResolver.delete(it, null, null) }
    }

    /** Pixels of the tracer's lime near where its points are drawn. */
    private fun limeAround(frame: Bitmap, points: List<com.golftracker.app.model.ScreenPoint>): Int {
        val r = (frame.height * 0.012f).toInt()
        val seen = HashSet<Long>()
        for (p in points) {
            val cx = (p.x * frame.width).toInt()
            val cy = (p.y * frame.height).toInt()
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                if (x !in 0 until frame.width || y !in 0 until frame.height) continue
                val c = frame.getPixel(x, y)
                val red = (c shr 16) and 0xFF
                val green = (c shr 8) and 0xFF
                val blue = c and 0xFF
                if (green > 200 && red < 120 && blue in 40..190) seen += y.toLong() * frame.width + x
            }
        }
        return seen.size
    }

    private fun Bitmap.save(file: File) = file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }

    private companion object {
        const val TAG = "ShotVideoExport"
    }
}
