package com.golftracker.app

import com.golftracker.app.export.write
import com.golftracker.app.export.ShotBundle
import com.golftracker.app.export.ShotTracking
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.repository.ShotRepository
import com.golftracker.app.tracker.BallCandidate
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.OnsetDetector
import com.golftracker.app.tracker.ShotDebugArchive
import com.golftracker.app.tracker.VideoAlignment
import com.golftracker.app.video.VideoRetracer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The pieces around a shot's recording: hearing the strike, lining up the video, keeping and sharing recordings. */
class ShotRecordingTest {

    @get:Rule
    val temp = TemporaryFolder()

    // --- Hearing the strike ---

    private fun sound(rate: Int, seconds: Double, seed: Int, add: (Int, Double) -> Double): ShortArray {
        val rnd = Random(seed)
        return ShortArray((rate * seconds).toInt()) { i -> (rnd.nextDouble(-20.0, 20.0) + add(i, i.toDouble() / rate)).toInt().toShort() }
    }

    private fun onsets(samples: ShortArray, rate: Int): List<OnsetDetector.Onset> {
        val detector = OnsetDetector(rate)
        val out = ArrayList<OnsetDetector.Onset>()
        var i = 0
        while (i < samples.size) {
            val n = minOf(480, samples.size - i)
            out += detector.feed(samples.copyOfRange(i, i + n), n, i.toLong())
            i += n
        }
        return out
    }

    @Test
    fun hearsAStrikeOverBackgroundNoise() {
        val rate = 48_000
        val strikeAt = (1.3 * rate).toInt()
        // A crack: loud at once, ringing down within a few milliseconds.
        val samples = sound(rate, 2.0, 1) { i, _ ->
            val k = i - strikeAt
            if (k < 0) 0.0 else 3000 * exp(-k / 150.0) * sin(2 * PI * 3000 * k / rate)
        }
        val found = onsets(samples, rate)
        assertEquals(1, found.size)
        assertTrue("at ${found[0].sample}", abs(found[0].sample - strikeAt) <= rate / 400)
    }

    @Test
    fun voicesAndSwellingNoiseAreNotStrikes() {
        val rate = 48_000
        // Something loud that builds up over a fifth of a second, like a voice or a passing car.
        val samples = sound(rate, 2.0, 2) { _, t ->
            val env = ((t - 0.8) / 0.2).coerceIn(0.0, 1.0) * (if (t > 1.6) 0.0 else 1.0)
            2000 * env * sin(2 * PI * 220 * t)
        }
        assertTrue(onsets(samples, rate).isEmpty())
    }

    // --- Lining up the video ---

    /** Thumbnails of a swinging bar at [fps], on the given clock. */
    private fun swingThumbs(fps: Double, fromS: Double, toS: Double, clockNs: Long, seed: Int, shiftX: Int = 0): List<LumaFrame> {
        val w = 43
        val h = 96
        val rnd = Random(seed)
        val bg = IntArray(w * h) { 100 + rnd.nextInt(-30, 31) }
        val frames = ArrayList<LumaFrame>()
        var t = fromS
        while (t < toS) {
            val px = bg.copyOf()
            val x = (21 + 15 * sin(t * 4)).toInt() + shiftX
            val y = (50 + 30 * sin(t * 2.5)).toInt()
            for (yy in y - 4..y + 4) for (xx in x - 3..x + 3) if (xx in 0 until w && yy in 0 until h) px[yy * w + xx] = 230
            frames += LumaFrame(w, h, ByteArray(w * h) { px[it].coerceIn(0, 255).toByte() }, clockNs + (t * 1e9).toLong())
            t += 1 / fps
        }
        return frames
    }

    @Test
    fun linesUpVideoWithLiveFrames() {
        // Live frames on the boot clock at 30 fps; video from 0 at 60 fps, started 1.234 s before the live frames.
        val videoStart = 50_000_000_000L
        val live = swingThumbs(30.0, 1.234, 3.5, videoStart, seed = 3)
        val video = swingThumbs(60.0, 0.0, 4.0, 0L, seed = 3)
        // The recorder's guess is off by a quarter second.
        val result = VideoAlignment.align(live, video, videoStart + 250_000_000L, 1_000_000_000L)
        assertNotNull(result)
        assertTrue("offset off by ${(result!!.offsetNs - videoStart) / 1e6} ms", abs(result.offsetNs - videoStart) <= 10_000_000L)
    }

    @Test
    fun refusesVideoShowingADifferentView() {
        val live = swingThumbs(30.0, 1.0, 3.0, 0L, seed = 4)
        val other = swingThumbs(60.0, 0.0, 4.0, 0L, seed = 5, shiftX = 9)
        assertNull(VideoAlignment.align(live, other, 0L, 1_000_000_000L))
    }

    @Test
    fun videoTraceReplacesTheLiveOneWhenItFollowsTheBallAsFar() {
        val launch = BallFlightFitter.ScreenLaunch(0.0, 0.0, 0.0, -1.0, 0.0, 0.5)
        fun flight(seconds: Double) = BallFlightFitter.Flight(emptyList(), launch, 10, seconds)
        assertTrue(VideoRetracer.isBetter(null, flight(0.5)))
        assertTrue(VideoRetracer.isBetter(flight(1.0), flight(1.6)))
        assertTrue(VideoRetracer.isBetter(flight(1.0), flight(0.98)))
        assertFalse(VideoRetracer.isBetter(flight(1.6), flight(1.0)))
        assertFalse(VideoRetracer.isBetter(flight(1.0), null))
        // A flight followed beats one estimated from a glimpse, however long the glimpse.
        val estimated = BallFlightFitter.Flight(emptyList(), launch, 4, 0.3, estimated = true)
        assertTrue(VideoRetracer.isBetter(estimated, flight(0.2)))
        assertFalse(VideoRetracer.isBetter(flight(0.2), estimated))
    }

    // --- Keeping recordings ---

    @Test
    fun offersToRemoveTheEarliestRecordings() {
        val shots = List(40) { i ->
            ShotRecord(id = "s$i", timestampMs = 1_000L * (100 - i), trackingPath = if (i % 5 == 4) null else "t$i")
        }
        assertEquals(32, ShotRepository.recordingCount(shots))
        val earliest = ShotRepository.earliestRecordings(shots, ShotRepository.RECORDINGS_TO_FREE)
        assertEquals(18, earliest.size)
        assertTrue(earliest.all { it.hasRecording })
        // The oldest shots (largest i here), and none newer than any kept.
        val newestRemoved = earliest.maxOf { it.timestampMs }
        assertTrue(shots.filter { it.hasRecording && it !in earliest }.all { it.timestampMs > newestRemoved })
    }

    @Test
    fun bundleHoldsEverythingAndReplaysAsADebugArchive() {
        val dir = temp.newFolder("shot")
        val frames = List(3) { i -> LumaFrame(4, 3, ByteArray(12) { (it * 5 + i).toByte() }, 1_000L + i) }
        val shot = CapturedShot(
            candidates = listOf(BallCandidate(0.4f, 0.5f, 1_001L, 20f)),
            teeX = 0.5f, teeY = 0.8f, ballRadius = 0.01f, launchTimestampNs = 1_001L, aspect = 0.45f,
            framesProcessed = 3, firstFrames = frames.take(1), recentFrames = frames.drop(1),
            impactTimestampNs = 1_000L, heardImpact = true
        )
        val tracer = listOf(ScreenPoint(0.5f, 0.8f, 1L), ScreenPoint(0.5f, 0.6f, 2L))
        File(dir, ShotBundle.CAPTURE).outputStream().use { ShotDebugArchive.write(shot, tracer, "test", it) }
        ShotTracking.write(
            ShotTracking.of(shot, BallFlightFitter.Flight(tracer, BallFlightFitter.ScreenLaunch(0.27, 0.8, 0.0, -1.0, 0.0, 0.45), 2, 1.2)),
            File(dir, ShotBundle.TRACKING)
        )
        val video = ByteArray(5000) { it.toByte() }
        File(dir, ShotBundle.VIDEO).writeBytes(video)
        val record = ShotRecord(id = "abc", trackingPath = File(dir, ShotBundle.TRACKING).path)

        val bytes = ByteArrayOutputStream().also { ShotBundle.write(record, dir, it) }.toByteArray()

        val names = ArrayList<String>()
        var videoBack: ByteArray? = null
        var trackingBack: ShotTracking? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
                when (e.name) {
                    ShotBundle.VIDEO -> videoBack = zip.readBytes()
                    ShotBundle.TRACKING -> trackingBack = ShotTracking.read(zip.readBytes().decodeToString())
                }
            }
        }
        assertEquals(setOf("record.json", "tracking.json", "shot.json", "frames.bin", "video.mp4"), names.toSet())
        assertArrayEquals(video, videoBack)
        assertEquals(true, trackingBack!!.heardImpact)
        assertEquals(2, trackingBack.liveTracer.size)

        val archive = ShotDebugArchive.read(ByteArrayInputStream(bytes))
        val back = archive.toCapturedShot()
        assertEquals(3, archive.frames.size)
        assertEquals(1_000L, back.impactTimestampNs)
        assertTrue(back.heardImpact)
    }
}
