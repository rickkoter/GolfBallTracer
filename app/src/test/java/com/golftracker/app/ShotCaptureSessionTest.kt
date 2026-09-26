package com.golftracker.app

import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.ShotCaptureSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** Runs synthetic camera frames through [ShotCaptureSession] and on into [BallFlightFitter]. */
class ShotCaptureSessionTest {

    private val w = 288
    private val h = 640
    private val frameNs = 33_333_333L
    private val teeX = 0.5f
    private val teeY = 0.8f
    private val ballRadius = 4f

    /** Behind-the-golfer flight in screen-normalized coordinates, t seconds after launch. */
    private fun flight(t: Double) = Pair(
        teeX - 0.02 * t,
        teeY - 0.6 * (1 - exp(-t / 0.7)) + 0.03 * t * t
    )

    private class Scene(val w: Int, val h: Int, seed: Int) {
        private val rnd = Random(seed)
        // Grass below, sky above, with fixed texture so only real motion shows up in frame differences.
        val background = ByteArray(w * h) { i ->
            val y = i / w
            val base = if (y > h * 0.55) 70 else 150
            (base + rnd.nextInt(-12, 13)).toByte()
        }
        private val noise = Random(seed + 1)

        /** Renders the scene with the camera shifted so content moves by (camX, camY). */
        fun render(camX: Int = 0, camY: Int = 0, draw: (IntArray) -> Unit): ByteArray {
            val px = IntArray(w * h) { (background[it].toInt() and 0xFF) + noise.nextInt(-3, 4) }
            draw(px)
            return ByteArray(w * h) { i ->
                val sx = (i % w - camX).coerceIn(0, w - 1)
                val sy = (i / w - camY).coerceIn(0, h - 1)
                px[sy * w + sx].coerceIn(0, 255).toByte()
            }
        }

        fun disk(px: IntArray, cx: Float, cy: Float, r: Float, lum: Int) {
            for (y in (cy - r - 1).toInt()..(cy + r + 1).toInt()) for (x in (cx - r - 1).toInt()..(cx + r + 1).toInt()) {
                if (x !in 0 until w || y !in 0 until h) continue
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                if (sqrt(dx * dx + dy * dy) <= r) px[y * w + x] = lum
            }
        }

        fun rect(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, lum: Int) {
            for (y in maxOf(0, y0)..minOf(h - 1, y1)) for (x in maxOf(0, x0)..minOf(w - 1, x1)) px[y * w + x] = lum
        }
    }

    private fun recordShot(
        session: ShotCaptureSession, seed: Int, launchFrame: Int = 40, totalFrames: Int = 130,
        shake: Int = 0, withBall: Boolean = true
    ) {
        val scene = Scene(w, h, seed)
        val hand = Random(seed + 2)
        // User's tap lands a few pixels off the ball.
        session.arm(teeX + 3f / w, teeY - 2f / h)
        for (f in 0 until totalFrames) {
            val t = (f - launchFrame) / 30.0
            // Hand-held: the whole picture jitters a few pixels every frame.
            val camX = if (f == 0 || shake == 0) 0 else hand.nextInt(-shake, shake + 1)
            val camY = if (f == 0 || shake == 0) 0 else hand.nextInt(-shake, shake + 1)
            val data = scene.render(camX, camY) { px ->
                // Golfer's body swaying beside the ball: a big moving block that must not become a candidate.
                val sway = (6 * kotlin.math.sin(f * 0.2)).toInt()
                scene.rect(px, 40 + sway, 250, 120 + sway, 560, 40)
                if (!withBall) {
                    // Nothing to track.
                } else if (t <= 0) {
                    scene.disk(px, teeX * w, teeY * h, ballRadius, 235)
                } else {
                    val (x, y) = flight(t)
                    val r = maxOf(1.2f, ballRadius * (1 - t / 3).toFloat())
                    scene.disk(px, x.toFloat() * w, y.toFloat() * h, r, 235)
                }
            }
            session.process(LumaFrame(w, h, data, 5_000_000_000L + f * frameNs))
        }
    }

    @Test
    fun snapsTapToBallAndMeasuresIt() {
        val session = ShotCaptureSession()
        recordShot(session, seed = 1)
        val shot = session.stop()
        assertEquals(teeX, shot.teeX, 1.5f / w)
        assertEquals(teeY, shot.teeY, 1.5f / h)
        assertNotNull(shot.ballRadius)
        assertEquals(ballRadius / h, shot.ballRadius!!, 1.5f / h)
    }

    @Test
    fun measuresABigBallCloseToTheCamera() {
        // Phone lying right behind the ball: a ball 25 cells in radius with a shaded underside and a
        // logo, tapped near its edge.
        val scene = Scene(w, h, 30)
        val cx = teeX * w
        val cy = teeY * h
        val data = scene.render { px ->
            scene.disk(px, cx, cy, 25f, 225)
            scene.rect(px, (cx - 25).toInt(), (cy + 8).toInt(), (cx + 25).toInt(), (cy + 25).toInt(), 150)
            scene.rect(px, (cx - 10).toInt(), (cy - 2).toInt(), (cx + 10).toInt(), (cy + 1).toInt(), 60)
        }
        // Put back the background around the ball that the shading rectangle overwrote.
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            if (dx * dx + dy * dy > 25f * 25f) data[y * w + x] = scene.background[y * w + x]
        }
        val session = ShotCaptureSession()
        session.arm((cx + 18) / w, (cy - 12) / h)
        session.process(LumaFrame(w, h, data, 0L))
        val shot = session.stop()
        assertNotNull(shot.ballRadius)
        assertEquals(25f / h, shot.ballRadius!!, 3f / h)
        assertEquals(teeX, shot.teeX, 3f / w)
        assertEquals(teeY, shot.teeY, 3f / h)
    }

    /** Sunlit grass: bright and busy, like the backyard recordings. */
    private fun brightGrass(seed: Int): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { 165 + rnd.nextInt(-30, 31) }
    }

    private fun measure(px: IntArray, tapX: Float, tapY: Float): Pair<ShotCaptureSession, com.golftracker.app.tracker.TeeBall?> {
        var measured: com.golftracker.app.tracker.TeeBall? = null
        val session = ShotCaptureSession(onTeeMeasured = { measured = it })
        session.arm(tapX, tapY)
        session.process(LumaFrame(w, h, ByteArray(w * h) { px[it].coerceIn(0, 255).toByte() }, 0L))
        return session to measured
    }

    @Test
    fun findsSmallBallOnBrightGrass() {
        for (seed in 40..44) {
            val px = brightGrass(seed)
            val scene = Scene(w, h, seed)
            scene.disk(px, teeX * w, teeY * h, 3f, 240)
            // Tapped a few pixels off to the side, as happens with a small ball.
            val (_, ball) = measure(px, teeX + 6f / w, teeY - 3f / h)
            assertNotNull("seed $seed", ball)
            assertEquals(teeX, ball!!.x, 1.5f / w)
            assertEquals(teeY, ball.y, 1.5f / h)
            assertEquals(3f / h, ball.radius, 1.5f / h)
        }
    }

    @Test
    fun rejectsTapOnPlainGrass() {
        for (seed in 50..59) {
            val (_, ball) = measure(brightGrass(seed), 0.5f, 0.6f)
            assertNull("found a ball in plain grass (seed $seed)", ball)
        }
    }

    @Test
    fun detectsLaunchWithinAFrame() {
        var reported = false
        val session = ShotCaptureSession(onLaunchDetected = { reported = it })
        recordShot(session, seed = 2)
        val shot = session.stop()
        assertTrue(reported)
        val launchNs = 5_000_000_000L + 40 * frameNs
        assertNotNull(shot.launchTimestampNs)
        assertTrue(abs(shot.launchTimestampNs!! - launchNs) <= frameNs)
    }

    @Test
    fun noLaunchWhileBallStaysOnTee() {
        val session = ShotCaptureSession()
        recordShot(session, seed = 3, launchFrame = 1000, totalFrames = 90)
        assertNull(session.stop().launchTimestampNs)
    }

    @Test
    fun movingBodyGivesOnlyStraySpecks() {
        val session = ShotCaptureSession()
        val frames = 90
        recordShot(session, seed = 4, launchFrame = 1000, totalFrames = frames)
        val onBody = session.stop().candidates.filter { it.x * w in 30f..135f && it.y * h in 245f..565f }
        // The swaying block itself never shows up; at most the odd flicker along its edge does.
        assertTrue("${onBody.size} candidates on the body", onBody.size < frames)
        assertTrue(onBody.all { abs(it.x * w - 40) < 8 || abs(it.x * w - 120) < 8 })
    }

    @Test
    fun handHeldCameraShakeGivesNoFlight() {
        for (seed in 20..23) {
            val session = ShotCaptureSession()
            recordShot(session, seed, shake = 6, withBall = false)
            val shot = session.stop()
            assertTrue("${shot.candidates.size} candidates from shake alone (seed $seed)", shot.candidates.size < 130)
            assertTrue(BallFlightFitter.fit(shot).isEmpty())
        }
    }

    @Test
    fun endToEndTracesTheBallHandHeld() = endToEnd(shake = 6)

    @Test
    fun endToEndTracesTheBall() = endToEnd(shake = 0)

    private fun endToEnd(shake: Int) {
        for (seed in 5..8) {
            val session = ShotCaptureSession()
            recordShot(session, seed, shake = shake)
            val tracer = BallFlightFitter.fit(session.stop())
            assertTrue("No tracer for seed $seed", tracer.size >= 10)
            val launchMs = (5_000_000_000L + 40 * frameNs) / 1_000_000L
            for (p in tracer.drop(1)) {
                val t = (p.timestampMs - launchMs) / 1000.0
                if (t < 0.05) continue
                val (x, y) = flight(t)
                assertEquals("x at t=$t (seed $seed)", x, p.x.toDouble(), 0.02)
                assertEquals("y at t=$t (seed $seed)", y, p.y.toDouble(), 0.02)
            }
            val span = (tracer.last().timestampMs - launchMs) / 1000.0
            assertTrue("Tracer only reached ${span}s (seed $seed)", span > 1.5)
        }
    }
}
