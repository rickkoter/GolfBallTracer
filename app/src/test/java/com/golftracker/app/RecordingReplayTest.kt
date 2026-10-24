package com.golftracker.app

import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.replay.RecordingReplay
import com.golftracker.app.replay.ShotArchive
import com.golftracker.app.tracker.BallCandidate
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.ShotDebugArchive
import com.golftracker.app.replay.ZipArchive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The shared replay the iOS app uses: its zip reader against Java's zip writer, and (with
 * GOLF_SHOTS_DIR) every recording replayed, to compare with ShotReplayTest and DebugSessionReplayTest.
 */
class RecordingReplayTest {

    @Test
    fun readsStoredAndDeflatedEntries() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val small = "{\"impactTimestampNs\":5}".toByteArray()
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("frames.bin"))
                z.write(big)
                z.closeEntry()
                val stored = ZipEntry("shot.json").apply {
                    method = ZipEntry.STORED
                    size = small.size.toLong()
                    crc = CRC32().apply { update(small) }.value
                }
                z.putNextEntry(stored)
                z.write(small)
                z.closeEntry()
            }
        }.toByteArray()
        val archive = ZipArchive(zip)
        assertEquals(listOf("frames.bin", "shot.json"), archive.entries.map { it.name })
        assertArrayEquals(big, archive.read("frames.bin"))
        assertArrayEquals(small, archive.read("shot.json"))
        assertNull(archive.read("video.mp4"))
    }

    @Test
    fun savedShotReadsBackInTheAndroidFormat() {
        val frames = List(5) { n -> LumaFrame(40, 30, ByteArray(1200) { (it * 7 + n).toByte() }, 1_000_000_000L + n * 33_000_000L) }
        val shot = CapturedShot(
            candidates = listOf(BallCandidate(0.5f, 0.6f, 1_066_000_000L, 0.9f)),
            teeX = 0.51f, teeY = 0.8f, ballRadius = 0.004f, launchTimestampNs = 1_033_000_000L, aspect = 0.46f,
            framesProcessed = 5, firstFrames = frames.take(2), recentFrames = frames.drop(2),
            impactTimestampNs = 1_030_000_000L, heardImpact = true
        )
        val zip = ShotArchive.encode(shot, listOf(ScreenPoint(0.5f, 0.7f), ScreenPoint(0.52f, 0.4f)), "test", "Driver", 2)
        // Java's zip reader checks every entry's checksum and size.
        val back = ShotDebugArchive.read(zip.inputStream())
        assertEquals(0.51f, back.info.teeX)
        assertEquals(1_030_000_000L, back.info.impactTimestampNs)
        assertTrue(back.info.heardImpact)
        assertEquals(2, back.info.firstFrameCount)
        assertEquals(listOf(listOf(0.5f, 0.7f), listOf(0.52f, 0.4f)), back.info.tracer)
        assertEquals(listOf(listOf(0.5f, 0.6f, 0.9f)), back.info.candidates)
        assertEquals(frames.map { it.timestampNs }, back.frames.map { it.timestampNs })
        frames.zip(back.frames).forEach { (a, b) -> assertArrayEquals(a.data, b.data) }
        assertEquals(listOf("shot.json", "frames.bin"), ZipArchive(zip).entries.map { it.name })
    }

    /** Each saved shot with a strike, written again as the iOS app saves shots, replays the same as the original. */
    @Test
    fun rewrittenShotsReplayTheSame() {
        val dir = File(System.getenv("GOLF_SHOTS_DIR") ?: "shots")
        val files = dir.listFiles { f -> f.name.endsWith(".zip") && !f.name.startsWith("debug-session") }?.sortedBy { it.name } ?: return
        for (file in files) {
            val bytes = file.readBytes()
            val archive = ShotDebugArchive.read(bytes.inputStream())
            // Shots from before strikes were timed (tapped on the ball) replay by another path.
            if (archive.info.impactTimestampNs == null) continue
            val tracer = archive.info.tracer.map { ScreenPoint(it[0], it[1]) }
            val rewritten = ShotArchive.encode(archive.toCapturedShot(), tracer, archive.info.device)
            val before = RecordingReplay.replay(bytes).shots.map { it.summary }
            val after = RecordingReplay.replay(rewritten).shots.map { it.summary }
            assertEquals(file.name, before, after)
            println("${file.name}: ${bytes.size / 1024} KB as saved, ${rewritten.size / 1024} KB rewritten, ${after.size} shot(s) the same")
        }
    }

    @Test
    fun replaySavedRecordings() {
        val dir = File(System.getenv("GOLF_SHOTS_DIR") ?: "shots")
        val files = dir.listFiles { f -> f.name.endsWith(".zip") }?.sortedBy { it.name } ?: return
        for (file in files) {
            val result = RecordingReplay.replay(file.readBytes())
            println("=== ${file.name}: ${result.kind}, ${result.frameCount} frames over ${"%.1f".format(result.seconds)} s, ${result.strikes} strikes")
            for (shot in result.shots) {
                println("   " + (shot.ball?.let { "ball at (%.3f, %.3f): ".format(it.x, it.y) } ?: "") + shot.summary)
            }
            result.notes.forEach { println("   note at %.1f s: %s".format(it.atSeconds, it.text)) }
        }
    }
}
