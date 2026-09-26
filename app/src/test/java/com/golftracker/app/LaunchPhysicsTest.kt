package com.golftracker.app

import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.tracker.BallCandidate
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.TrajectoryMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

class LaunchPhysicsTest {

    private val aspect = 0.53
    private val vfov = Math.toRadians(65.0)
    private val focal = 0.5 / tan(vfov / 2)
    private val phone = GpsCoordinate(40.0, -75.0)

    /** Phone upright in portrait, camera facing [headingDeg] and tilted down by [pitchDeg]. */
    private fun deviceToWorld(headingDeg: Double, pitchDeg: Double): FloatArray {
        val h = Math.toRadians(headingDeg)
        val p = Math.toRadians(pitchDeg)
        val right = doubleArrayOf(cos(h), -sin(h), 0.0)
        val forward = doubleArrayOf(sin(h) * cos(p), cos(h) * cos(p), -sin(p))
        val up = doubleArrayOf(sin(h) * sin(p), cos(h) * sin(p), cos(p))
        val out = doubleArrayOf(-forward[0], -forward[1], -forward[2])
        // Columns are the phone's x (right), y (up the screen) and z (out of the screen) in world axes.
        return FloatArray(9) { i ->
            val row = i / 3
            when (i % 3) { 0 -> right[row]; 1 -> up[row]; else -> out[row] }.toFloat()
        }
    }

    private class Shot(val capture: CapturedShot, val velocity: DoubleArray)

    /**
     * A ball launched from [teeDistance] m in front of a camera 1 m up, flown with gravity and drag,
     * projected onto the screen at 30 fps with a little jitter and a few random specks.
     */
    private fun film(
        speed: Double, launchDeg: Double, aimDeg: Double, headingDeg: Double, pitchDeg: Double,
        teeDistance: Double = 2.0, seed: Int = 1
    ): Shot {
        val r = deviceToWorld(headingDeg, pitchDeg)
        val right = doubleArrayOf(r[0].toDouble(), r[3].toDouble(), r[6].toDouble())
        val up = doubleArrayOf(r[1].toDouble(), r[4].toDouble(), r[7].toDouble())
        val forward = doubleArrayOf(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())
        val camera = doubleArrayOf(0.0, 0.0, 1.0)
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun project(p: DoubleArray): Pair<Double, Double>? {
            val d = doubleArrayOf(p[0] - camera[0], p[1] - camera[1], p[2] - camera[2])
            val z = dot(d, forward)
            if (z <= 0.1) return null
            return (aspect / 2 + focal * dot(d, right) / z) / aspect to 0.5 - focal * dot(d, up) / z
        }

        val h = Math.toRadians(headingDeg + aimDeg)
        val a = Math.toRadians(launchDeg)
        val tee = doubleArrayOf(teeDistance * sin(Math.toRadians(headingDeg)), teeDistance * cos(Math.toRadians(headingDeg)), 0.0)
        var p = tee.copyOf()
        val v = doubleArrayOf(speed * cos(a) * sin(h), speed * cos(a) * cos(h), speed * sin(a))
        val launchVelocity = v.copyOf()
        val k = 0.5 * 1.225 * Math.PI * 0.021335 * 0.021335 / 0.04593 * 0.25

        val rnd = Random(seed)
        val candidates = ArrayList<BallCandidate>()
        val frameNs = 33_333_333L
        val launchNs = 2_000_000_000L
        for (f in 0 until 90) {
            val ns = launchNs + f * frameNs
            repeat(3) { candidates += BallCandidate(rnd.nextFloat(), rnd.nextFloat(), ns, 20f) }
            if (f > 0 && p[2] >= 0) {
                project(p)?.let { (x, y) ->
                    if (x in 0.0..1.0 && y in 0.0..1.0 && rnd.nextFloat() > 0.1f) {
                        candidates += BallCandidate(
                            (x + rnd.nextDouble(-0.0015, 0.0015)).toFloat(),
                            (y + rnd.nextDouble(-0.0015, 0.0015)).toFloat(), ns, 30f
                        )
                    }
                }
            }
            // One frame of flight in small steps.
            repeat(20) {
                val dt = 1.0 / 600
                val s = hypot(hypot(v[0], v[1]), v[2])
                v[0] -= k * s * v[0] * dt
                v[1] -= k * s * v[1] * dt
                v[2] -= (9.81 + k * s * v[2]) * dt
                for (i in 0..2) p[i] += v[i] * dt
            }
        }
        val (teeX, teeY) = project(tee)!!
        val teeDepth = dot(doubleArrayOf(tee[0] - camera[0], tee[1] - camera[1], tee[2] - camera[2]), forward)
        return Shot(
            CapturedShot(
                candidates = candidates,
                teeX = teeX.toFloat(), teeY = teeY.toFloat(),
                ballRadius = (focal * 0.021335 / teeDepth).toFloat(),
                launchTimestampNs = launchNs,
                aspect = aspect.toFloat()
            ),
            launchVelocity
        )
    }

    private fun check(speed: Double, launchDeg: Double, aimDeg: Double, headingDeg: Double, pitchDeg: Double, teeDistance: Double = 2.0) {
        val shot = film(speed, launchDeg, aimDeg, headingDeg, pitchDeg, teeDistance)
        val flight = BallFlightFitter.fitFlight(shot.capture)
        assertNotNull("no flight found", flight)
        val landing = LaunchPhysics.estimateLanding(
            flight!!.launch, shot.capture.ballRadius!!, vfov, deviceToWorld(headingDeg, pitchDeg), 0.0, phone
        )
        assertNotNull("no landing estimate", landing)
        landing!!
        assertEquals("launch speed", speed, landing.launchSpeedMps, speed * 0.15)
        assertEquals("launch angle", launchDeg, landing.launchAngleDegrees, 5.0)
        val expectedBearing = LaunchPhysics.normalizeDegrees(headingDeg + aimDeg)
        val bearingError = ((landing.bearingDegrees - expectedBearing + 540) % 360) - 180
        assertEquals("bearing", 0.0, bearingError, 5.0)

        val (trueCarry, _) = LaunchPhysics.fly(hypot(shot.velocity[0], shot.velocity[1]), shot.velocity[2])
        assertEquals("carry", trueCarry, landing.carryMeters, trueCarry * 0.2)

        // The landing point is about that far from the phone, in that direction.
        val fromPhone = TrajectoryMath.calculateGpsDistanceMeters(phone, landing.position)
        assertEquals(landing.totalMeters + teeDistance, fromPhone, 1.0 + 0.05 * fromPhone)
    }

    @Test
    fun chipFromBehindFacingNorth() = check(speed = 12.0, launchDeg = 38.0, aimDeg = 0.0, headingDeg = 0.0, pitchDeg = 12.0)

    @Test
    fun chipPulledLeftFacingEast() = check(speed = 14.0, launchDeg = 30.0, aimDeg = -8.0, headingDeg = 90.0, pitchDeg = 8.0)

    @Test
    fun pitchFromFurtherBack() = check(speed = 20.0, launchDeg = 35.0, aimDeg = 5.0, headingDeg = 200.0, pitchDeg = 10.0, teeDistance = 3.0)

    @Test
    fun realisticCarries() {
        assertEquals(15.0, LaunchPhysics.fly(12 * cos(Math.toRadians(38.0)), 12 * sin(Math.toRadians(38.0))).first / 0.9144, 3.0)
        assertEquals(224.0, LaunchPhysics.fly(70 * cos(Math.toRadians(11.0)), 70 * sin(Math.toRadians(11.0))).first / 0.9144, 20.0)
    }

    @Test
    fun destinationAndBearingAgree() {
        val there = LaunchPhysics.destination(phone, 47.0, 150.0)
        assertEquals(150.0, TrajectoryMath.calculateGpsDistanceMeters(phone, there), 0.5)
        assertEquals(47.0, LaunchPhysics.bearing(phone, there), 0.5)
    }
}
