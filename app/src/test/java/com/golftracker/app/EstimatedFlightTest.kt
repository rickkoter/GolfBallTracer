package com.golftracker.app

import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.model.isEstimated
import com.golftracker.app.model.tracerRuns
import com.golftracker.app.tracker.BallCandidate
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.LaunchPhysics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** A ball seen only for its first fraction of a second, as at a range filmed from behind: the rest is estimated. */
class EstimatedFlightTest {

    private val aspect = 0.53f
    private val teeX = 0.55f
    private val teeY = 0.7f
    private val radius = 0.004f
    private val launchNs = 50_000_000_000L
    private val frameNs = 33_333_333L

    /** Phone upright, level, facing north down the range. */
    private val camera = run {
        val right = doubleArrayOf(1.0, 0.0, 0.0)
        val up = doubleArrayOf(0.0, 0.0, 1.0)
        val out = doubleArrayOf(0.0, -1.0, 0.0)
        LaunchPhysics.CameraPose(Math.toRadians(68.0), FloatArray(9) { i -> val row = i / 3; when (i % 3) { 0 -> right[row]; 1 -> up[row]; else -> out[row] }.toFloat() })
    }

    /** The true flight: up the screen and a little right, flying away. */
    private val truth = LaunchPhysics.projectFlight(
        BallFlightFitter.ScreenLaunch(teeX * aspect.toDouble(), teeY.toDouble(), 0.4, -5.5, 9.0, aspect.toDouble()),
        radius, camera, launchNs, launchNs
    )

    private fun position(ns: Long): ScreenPoint = truth.minByOrNull { abs(it.timestampMs * 1_000_000L - ns) }!!

    /** Junk spots every frame (grass flicker), plus the ball in [seenFrames] frames after the strike. */
    private fun shot(seed: Int, seenFrames: List<Int>): CapturedShot {
        val rnd = Random(seed)
        val candidates = ArrayList<BallCandidate>()
        for (f in -5..30) {
            val ns = launchNs + f * frameNs
            repeat(6) { candidates += BallCandidate(rnd.nextFloat(), rnd.nextFloat(), ns, 60f) }
            if (f in seenFrames) position(ns).let { candidates += BallCandidate(it.x, it.y, ns, 90f) }
        }
        return CapturedShot(
            candidates, teeX, teeY, radius, launchNs, aspect, heardImpact = true, impactTimestampNs = launchNs
        )
    }

    @Test
    fun aGlimpseOfTheBallGivesItsWholeFlight() {
        assertTrue("the true flight should be a real one", truth.size > 60)
        val apex = truth.minOf { it.y }
        for (seed in 1..5) {
            val flight = BallFlightFitter.fitEstimatedFlight(shot(seed, listOf(2, 3, 5, 7)), camera)
            assertNotNull("seed $seed", flight)
            flight!!
            assertTrue(flight.estimated)
            assertTrue("seed $seed: ${flight.trackedFrames} sightings", flight.trackedFrames >= 3)
            // Solid where seen, dashed after.
            assertTrue(!flight.tracer.first().isEstimated && flight.tracer.last().isEstimated)
            assertEquals("seed $seed: apex", apex, flight.tracer.minOf { it.y }, 0.06f)
            assertEquals("seed $seed: landing x", truth.last().x, flight.tracer.last().x, 0.06f)
            assertEquals("seed $seed: landing y", truth.last().y, flight.tracer.last().y, 0.06f)
        }
    }

    @Test
    fun junkAloneRarelyGivesAFlight() {
        // Six random spots in every frame all over the picture, far more than real grass flicker: now and
        // then three of them line up like a launch. (About 7% here when this was written.) It has to stay rare.
        val falses = (100 until 140).count { seed -> BallFlightFitter.fitEstimatedFlight(shot(seed, emptyList()), camera) != null }
        assertTrue("$falses of 40 junk-only shots got a flight", falses <= 4)
    }

    @Test
    fun withoutTheCameraPoseTheArcIsStillDrawn() {
        val flight = BallFlightFitter.fitEstimatedFlight(shot(3, listOf(2, 3, 5, 7)), null)
        assertNotNull(flight)
        assertTrue(flight!!.tracer.size > 10)
        assertTrue(flight.tracer.minOf { it.y } < teeY - 0.2f)
    }

    @Test
    fun onlyHeardShotsGetAnEstimate() {
        val shot = shot(4, listOf(2, 3, 5, 7))
        assertNotNull(BallFlightFitter.fitBest(shot, camera))
        assertNull(BallFlightFitter.fitBest(shot.copy(heardImpact = false), camera))
    }

    @Test
    fun runsSplitSeenFromEstimatedAndJoinUp() {
        val pts = listOf(0.95f, 0.95f, 0.4f, 0.4f, 0.4f).mapIndexed { i, c -> ScreenPoint(i.toFloat() / 10, 0.5f, i.toLong(), c) }
        val runs = tracerRuns(pts)
        assertEquals(listOf(false, true), runs.map { it.second })
        assertEquals(pts[1], runs[1].first.first())
        assertEquals(5, runs.sumOf { it.first.size } - 1)
    }
}
