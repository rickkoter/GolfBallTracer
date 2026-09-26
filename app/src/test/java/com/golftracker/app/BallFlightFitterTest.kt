package com.golftracker.app

import com.golftracker.app.tracker.BallCandidate
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.golftracker.app.model.ScreenPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class BallFlightFitterTest {

    private val aspect = 0.45f // Portrait phone, 1080 x 2400
    private val frameNs = 33_333_333L
    private val teeX = 0.5f
    private val teeY = 0.8f

    /** Behind the golfer: the ball climbs fast, then hangs as it flies away, barely moving sideways. */
    private fun downTheLine(t: Double) = Pair(
        teeX - 0.02 * t,
        teeY - 0.6 * (1 - exp(-t / 0.7)) + 0.03 * t * t
    )

    /**
     * A chip filmed from behind: camera 2 m back and 1 m up, tilted down 12 degrees, 65 degree field of view.
     * The ball jumps up the screen in the first frames, then nearly stops as it flies away from the camera.
     */
    private fun chip(t: Double): Pair<Double, Double> {
        val launch = Math.toRadians(38.0)
        val bx = 0.3 * t
        val by = 12 * sin(launch) * t - 4.9 * t * t
        val bz = 12 * cos(launch) * t
        val pitch = Math.toRadians(12.0)
        val y = by - 1.0
        val z = bz + 2.0
        val yc = y * cos(pitch) + z * sin(pitch)
        val zc = -y * sin(pitch) + z * cos(pitch)
        val f = 0.5 / kotlin.math.tan(Math.toRadians(32.5))
        // Shifted so the ball starts on the tee used by the other shots.
        return Pair(0.5 + f * bx / zc / aspect, 0.5 - f * yc / zc + (teeY - 0.7044))
    }

    /** Side on: a wide arc across the screen. */
    private fun faceOn(t: Double) = Pair(
        teeX + 0.25 * t,
        teeY - 0.9 * t + 0.35 * t * t
    )

    private fun shot(
        flight: ((Double) -> Pair<Double, Double>)?,
        launchKnown: Boolean,
        seed: Int,
        preSwingS: Double = 4.0,
        flightS: Double = 2.5,
        noisePerFrame: Int = 5,
        withGlove: Boolean = true,
        withClub: Boolean = true
    ): CapturedShot {
        val rnd = Random(seed)
        val candidates = ArrayList<BallCandidate>()
        val frames = ((preSwingS + flightS + 1.0) * 30).toInt()
        val launchFrame = (preSwingS * 30).toInt()
        for (f in 0 until frames) {
            val ns = 1_000_000_000L + f * frameNs
            val t = (f - launchFrame) / 30.0
            // Random bright specks anywhere in the frame.
            repeat(noisePerFrame) {
                candidates += BallCandidate(rnd.nextFloat(), rnd.nextFloat(), ns, rnd.nextFloat() * 40)
            }
            // A white glove waggling right by the ball before the swing.
            if (withGlove && t < 0) {
                candidates += BallCandidate(teeX - 0.04f + 0.03f * sin(f * 0.3).toFloat(), teeY - 0.06f, ns, 30f)
            }
            // Clubhead sweeping through the tee and up around the golfer.
            if (withClub && t in 0.0..0.35) {
                val angle = t / 0.35 * Math.PI * 0.8
                candidates += BallCandidate(
                    (teeX - 0.25 * sin(angle) / aspect * 0.45).toFloat(),
                    (teeY - 0.35 * (1 - cos(angle))).toFloat(),
                    ns, 35f
                )
            }
            if (flight != null && t > 0 && t <= flightS && rnd.nextFloat() > 0.15f) {
                val (x, y) = flight(t)
                candidates += BallCandidate(
                    (x + rnd.nextDouble(-0.002, 0.002)).toFloat(),
                    (y + rnd.nextDouble(-0.002, 0.002)).toFloat(),
                    ns, 25f
                )
            }
        }
        return CapturedShot(
            candidates = candidates,
            teeX = teeX,
            teeY = teeY,
            ballRadius = 0.008f,
            launchTimestampNs = if (launchKnown) 1_000_000_000L + launchFrame * frameNs else null,
            aspect = aspect
        )
    }

    /** Every tracer point lies on the true flight path, and the tracer covers at least [minT] seconds of it. */
    private fun assertFollows(flight: (Double) -> Pair<Double, Double>, result: List<ScreenPoint>, minT: Double) {
        assertTrue("Expected a traced flight", result.size >= 10)
        assertEquals(teeX, result.first().x, 0.01f)
        assertEquals(teeY, result.first().y, 0.01f)
        val truth = (0..3000).map { flight(it / 1000.0) }
        fun offPath(p: ScreenPoint) = truth.minOf { (x, y) ->
            val dx = (x - p.x) * aspect
            val dy = y - p.y
            sqrt(dx * dx + dy * dy)
        }
        for (p in result) {
            assertTrue("(%.3f, %.3f) is %.3f off the flight path".format(p.x, p.y, offPath(p)), offPath(p) < 0.015)
        }
        val (endX, endY) = flight(minT)
        val reached = result.any { abs(it.y - endY) < 0.01 && abs(it.x - endX) < 0.02 } ||
            (result.last().timestampMs - result.first().timestampMs) / 1000.0 >= minT
        assertTrue("Tracer stops short of ${minT}s into the flight", reached)
    }

    @Test
    fun tracesDownTheLineShotWithKnownLaunch() {
        for (seed in 1..5) {
            assertFollows(::downTheLine, BallFlightFitter.fit(shot(::downTheLine, launchKnown = true, seed = seed)), 2.0)
        }
    }

    @Test
    fun tracesDownTheLineShotWithoutLaunchDetection() {
        for (seed in 1..5) {
            assertFollows(::downTheLine, BallFlightFitter.fit(shot(::downTheLine, launchKnown = false, seed = seed)), 2.0)
        }
    }

    @Test
    fun tracesChipFromBehind() {
        for (seed in 1..5) {
            assertFollows(::chip, BallFlightFitter.fit(shot(::chip, launchKnown = true, seed = seed, flightS = 1.3)), 1.0)
        }
    }

    @Test
    fun tracesChipFromBehindWithoutLaunchDetection() {
        for (seed in 1..5) {
            assertFollows(::chip, BallFlightFitter.fit(shot(::chip, launchKnown = false, seed = seed, flightS = 1.3)), 1.0)
        }
    }

    @Test
    fun tracesFaceOnShot() {
        for (seed in 1..5) {
            assertFollows(::faceOn, BallFlightFitter.fit(shot(::faceOn, launchKnown = true, seed = seed, flightS = 1.6)), 1.2)
        }
    }

    @Test
    fun findsNothingWhenThereIsNoBall() {
        for (seed in 1..5) {
            val result = BallFlightFitter.fit(shot(null, launchKnown = false, seed = seed))
            assertTrue("Invented a flight from noise (seed $seed)", result.isEmpty())
        }
    }

    @Test
    fun emptyCaptureGivesNoFlight() {
        val empty = CapturedShot(emptyList(), teeX, teeY, null, null, aspect)
        assertTrue(BallFlightFitter.fit(empty).isEmpty())
    }
}
