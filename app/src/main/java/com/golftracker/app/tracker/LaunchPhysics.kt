package com.golftracker.app.tracker

import com.golftracker.app.model.GpsCoordinate
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Estimates where the ball came down from how it left the tee on camera.
 *
 * 1. How far the ball was from the camera: a golf ball is 42.67 mm across, so its size on screen and
 *    the camera's field of view give the distance.
 * 2. Launch velocity: the fitted flight gives how fast the ball moved across the screen and how fast
 *    it moved away from the camera relative to that distance; scaled by the distance these are m/s.
 * 3. Direction on the ground: the phone's orientation turns camera directions into east/north/up.
 * 4. Carry: a golf ball flight with air drag and backspin lift, then some roll.
 *
 * Every step is approximate (ball size is a few pixels at a distance, 30 fps gives only a few frames
 * of fast flight, drag and spin are typical values), so the result is a search area, not a spot.
 */
object LaunchPhysics {

    private const val BALL_RADIUS_M = 0.021335
    private const val BALL_MASS_KG = 0.04593
    private const val AIR_DENSITY = 1.225
    private const val GRAVITY = 9.81
    private const val DRAG_COEFFICIENT = 0.25
    private const val LIFT_COEFFICIENT = 0.15
    /** Rolling resistance on short grass. */
    private const val ROLL_FRICTION = 0.12
    /** Fraction of horizontal speed kept when the ball lands and starts rolling. */
    private const val LANDING_SPEED_KEPT = 0.5
    private const val EARTH_RADIUS_M = 6_371_000.0

    data class Landing(
        val position: GpsCoordinate,
        /** Compass bearing from the tee, degrees from true north. */
        val bearingDegrees: Double,
        val carryMeters: Double,
        val totalMeters: Double,
        val launchSpeedMps: Double,
        val launchAngleDegrees: Double
    )

    /**
     * @param ballRadius the ball's radius on the tee as a fraction of screen height
     * @param verticalFovRadians the camera's field of view across the screen's height
     * @param deviceToWorld 3×3 row-major rotation from phone axes to east/north/up
     *   (as from SensorManager.getRotationMatrixFromVector), taken while filming
     * @param declinationDegrees magnetic declination at the tee, to turn magnetic north into true north
     * @param phone where the phone was when the shot was set up
     */
    fun estimateLanding(
        launch: BallFlightFitter.ScreenLaunch,
        ballRadius: Float,
        verticalFovRadians: Double,
        deviceToWorld: FloatArray,
        declinationDegrees: Double,
        phone: GpsCoordinate
    ): Landing? {
        if (ballRadius <= 0f || verticalFovRadians <= 0.0) return null

        // Camera: x right, y down, z out of the lens; positions on screen relative to its center.
        val focal = 0.5 / tan(verticalFovRadians / 2)
        val u = (launch.x - launch.aspect / 2) / focal
        val v = (launch.y - 0.5) / focal
        val depth = focal * BALL_RADIUS_M / ballRadius
        val vz = launch.d1 * depth
        val vx = depth * launch.vx / focal + u * vz
        val vy = depth * launch.vy / focal + v * vz

        // Phone axes (x right, y up the screen, z out of the screen) for the back camera, then world.
        val velocity = toWorld(deviceToWorld, vx, -vy, -vz)
        val offset = toWorld(deviceToWorld, u * depth, -v * depth, -depth)

        val east = velocity[0]
        val north = velocity[1]
        val up = velocity[2]
        val horizontal = hypot(east, north)
        val speed = sqrt(horizontal * horizontal + up * up)
        if (speed < 0.5 || speed > 95.0) return null

        val bearing = normalizeDegrees(Math.toDegrees(atan2(east, north)) + declinationDegrees)
        val (carry, landingSpeed) = fly(horizontal, up)
        val roll = (landingSpeed * LANDING_SPEED_KEPT).let { it * it / (2 * ROLL_FRICTION * GRAVITY) }
        val total = carry + roll

        // The ball started a couple of meters in front of the phone, not at the phone.
        val offsetBearing = normalizeDegrees(Math.toDegrees(atan2(offset[0], offset[1])) + declinationDegrees)
        val tee = destination(phone, offsetBearing, hypot(offset[0], offset[1]))
        return Landing(
            position = destination(tee, bearing, total),
            bearingDegrees = bearing,
            carryMeters = carry,
            totalMeters = total,
            launchSpeedMps = speed,
            launchAngleDegrees = Math.toDegrees(atan2(up, horizontal))
        )
    }

    private fun toWorld(r: FloatArray, x: Double, y: Double, z: Double) = doubleArrayOf(
        r[0] * x + r[1] * y + r[2] * z,
        r[3] * x + r[4] * y + r[5] * z,
        r[6] * x + r[7] * y + r[8] * z
    )

    /**
     * Flies a ball launched from the ground with drag and backspin lift until it comes back down.
     * Returns the carry in meters and the horizontal speed on landing.
     */
    fun fly(horizontalSpeed: Double, upSpeed: Double): Pair<Double, Double> {
        if (upSpeed <= 0.0) return 0.0 to horizontalSpeed
        val k = 0.5 * AIR_DENSITY * PI * BALL_RADIUS_M * BALL_RADIUS_M / BALL_MASS_KG
        var vx = horizontalSpeed
        var vy = upSpeed
        var x = 0.0
        var y = 0.0
        var t = 0.0
        val dt = 0.002
        while (t < 15.0) {
            val s = hypot(vx, vy)
            val ax = -k * DRAG_COEFFICIENT * s * vx - k * LIFT_COEFFICIENT * s * vy
            val ay = -GRAVITY - k * DRAG_COEFFICIENT * s * vy + k * LIFT_COEFFICIENT * s * vx
            vx += ax * dt
            vy += ay * dt
            x += vx * dt
            y += vy * dt
            t += dt
            if (y < 0 && vy < 0) break
        }
        return x to vx
    }

    /** The point [meters] from [from] along compass [bearingDegrees]. */
    fun destination(from: GpsCoordinate, bearingDegrees: Double, meters: Double): GpsCoordinate {
        val d = meters / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return GpsCoordinate(Math.toDegrees(lat2), Math.toDegrees(lon2), from.altitude)
    }

    /** Compass bearing from [from] to [to], degrees from true north. */
    fun bearing(from: GpsCoordinate, to: GpsCoordinate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    fun normalizeDegrees(d: Double): Double = ((d % 360) + 360) % 360
}
