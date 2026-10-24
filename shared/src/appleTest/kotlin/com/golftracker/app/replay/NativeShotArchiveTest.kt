package com.golftracker.app.replay

import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.tracker.BallCandidate
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.LumaFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shots saved on iOS (zlib's deflate, the shared zip writer) read back whole with the shared reader. */
class NativeShotArchiveTest {
    @Test
    fun savedShotReadsBack() {
        val frames = List(6) { n -> LumaFrame(64, 48, ByteArray(64 * 48) { ((it / 7 + n * 3) % 200).toByte() }, 5_000_000_000L + n * 33_333_333L) }
        val shot = CapturedShot(
            candidates = listOf(BallCandidate(0.4f, 0.5f, 5_066_000_000L, 0.8f)),
            teeX = 0.45f, teeY = 0.82f, ballRadius = 0.005f, launchTimestampNs = 5_033_000_000L, aspect = 0.75f,
            framesProcessed = 6, firstFrames = frames.take(3), recentFrames = frames.drop(3),
            impactTimestampNs = 5_030_000_000L, heardImpact = true
        )
        val zip = ShotArchive.encode(shot, listOf(ScreenPoint(0.45f, 0.8f)), "test", "7-Iron", 3)
        val archive = ZipArchive(zip)
        val back = RecordingReplay.readFrames(archive.read("frames.bin")!!)
        assertEquals(frames.map { it.timestampNs }, back.map { it.timestampNs })
        frames.zip(back).forEach { (a, b) -> assertContentEquals(a.data, b.data) }
        val json = archive.read("shot.json")!!.decodeToString()
        assertTrue("\"heardImpact\":true" in json && "\"club\":\"7-Iron\"" in json && "\"golfer\":3" in json, json)
        // Compressed: grey frames shrink a lot.
        assertTrue(zip.size < frames.sumOf { it.data.size } / 2, "zip is ${zip.size} bytes")
    }
}
