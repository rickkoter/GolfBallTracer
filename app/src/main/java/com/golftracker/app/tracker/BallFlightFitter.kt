package com.golftracker.app.tracker

import com.golftracker.app.model.ScreenPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Picks the ball's flight out of everything that moved during a recording.
 *
 * A ball flying through the air, seen by a fixed camera, traces a specific curve on screen: its 3D
 * position is quadratic in time (launch velocity plus gravity), and perspective divides by its
 * distance from the camera, which is also quadratic in time. So on screen
 *
 *     x(τ) = (x0 + a1·τ + a2·τ²) / (1 + d1·τ + d2·τ²)
 *     y(τ) = (y0 + b1·τ + b2·τ²) / (1 + d1·τ + d2·τ²)
 *
 * where τ is time since launch and (x0, y0) is the tee. This one shape covers a side-on arc (d ≈ 0)
 * and a shot filmed from behind, where the ball shoots up the screen and then hangs as it flies away.
 * Air drag bends real flights away from it over time, so it's only used for the first part of the
 * flight.
 *
 * 1. Seed: every three early candidates, together with the tee, pin down one such curve. Curves that
 *    don't leave the tee upward and fast are thrown out, and the rest are scored by how many frames
 *    have a candidate on them. Only the ball stays on one path for many frames; hands, club and
 *    specks don't.
 * 2. Grow: from the best seed, follow the ball frame by frame, predicting from its recent motion.
 * 3. Smooth the tracked points into the tracer.
 *
 * Works in "screen heights": x is multiplied by the aspect ratio so distances are the same both ways.
 */
object BallFlightFitter {

    private const val SEED_WINDOW_S = 0.6
    private const val MAX_SECOND_SIGHTING_S = 0.2
    private const val MAX_THIRD_SIGHTING_S = 0.45
    private const val MAX_FIRST_SEEN_AFTER_LAUNCH_S = 0.35
    private const val MAX_FIRST_SEEN_DISTANCE = 0.35
    /**
     * A ball close to the camera crosses the screen proportionally faster, so the first sighting may be
     * this many ball radii (on screen) from the tee, when that's further than the limits in screen heights.
     */
    private const val MAX_FIRST_SEEN_RADII = 50.0
    private const val MAX_FIRST_SEEN_DISTANCE_CLOSE = 0.6
    /** How much earlier than the known launch a constant-speed guess may be, for a ball slowing on screen. */
    private const val MAX_EARLY_LINEAR_LAUNCH_S = 0.35
    /** Largest d2 a path may have: gravity and drag only change the ball's speed away from the camera so fast. */
    private const val MAX_DEPTH_ACCELERATION = 6.0
    private const val MIN_LAUNCH_SPEED = 0.25 // screen heights per second
    private const val MIN_SEED_FRAMES = 5
    private const val MIN_SEED_TRAVEL = 0.15 // screen heights from the tee within the seed window
    private const val MIN_TRACK_FRAMES = 10
    private const val MIN_TRACKED_FLIGHT_S = 0.4
    private const val SEEDS_TO_TRY = 10
    private const val MAX_GAP_S = 0.5
    private const val MAX_FLIGHT_S = 10.0
    /** For an estimated flight: how long after the strike sightings are looked for, and how many are needed. */
    private const val SHORT_WINDOW_S = 0.35
    private const val MIN_SHORT_SIGHTINGS = 3
    /** How close, in ball tolerances, a sighting must be to the path: tight, as three points are few. */
    private const val SHORT_TOLERANCE = 1.0
    private const val MAX_FIRST_SHORT_SIGHTING_S = 0.12
    private const val MIN_SHORT_D1 = -0.3
    /** Screen heights from the tee. */
    private const val MAX_FIRST_SHORT_DISTANCE = 0.3
    /** Screen heights per second, from the tee to the last sighting. */
    private const val MIN_SEEN_SPEED = 0.8
    /**
     * The launch is when the ball was seen to leave the tee, to within a frame: the frame it went from
     * is the earliest it could have been struck.
     */
    private const val SHORT_LAUNCH_EARLIEST_S = -0.035
    private const val SHORT_LAUNCH_LATEST_S = 0.01
    /** Heard only: the sound and the camera's clock can be tens of milliseconds apart. */
    private const val HEARD_LAUNCH_EARLIEST_S = -0.05
    private const val HEARD_LAUNCH_LATEST_S = 0.08
    private const val MAX_ESTIMATED_FLIGHT_S = 8.0
    private const val GRAVITY_MPS2 = 9.81
    private const val BALL_RADIUS_M = 0.021335
    /** Fastest a golf ball leaves the club: a long-drive champion's 200 mph is 89 m/s. */
    private const val MAX_BALL_SPEED_MPS = 85.0
    /** Half the screen height over the tangent of half a typical phone camera's field of view, when it isn't known. */
    private const val TYPICAL_FOCAL = 0.75

    private class Obs(val t: Double, val x: Double, val y: Double, val ns: Long)

    private class Frame(val t: Double, val ns: Long, val obs: List<Obs>)

    /** The perspective ballistic curve above, leaving the tee (x0, y0) at time t0. */
    private class Path(
        val t0: Double,
        val x0: Double, val y0: Double,
        val a1: Double, val a2: Double,
        val b1: Double, val b2: Double,
        val d1: Double, val d2: Double
    ) {
        private fun den(tau: Double) = 1 + d1 * tau + d2 * tau * tau
        fun x(t: Double): Double { val tau = t - t0; return (x0 + a1 * tau + a2 * tau * tau) / den(tau) }
        fun y(t: Double): Double { val tau = t - t0; return (y0 + b1 * tau + b2 * tau * tau) / den(tau) }
        val launchVx get() = a1 - x0 * d1
        val launchVy get() = b1 - y0 * d1

        /** The ball stays in front of the camera over the first [span] seconds. */
        fun inFrontFor(span: Double): Boolean {
            var tau = 0.0
            while (tau <= span) {
                if (den(tau) < 0.05) return false
                tau += 0.05
            }
            return true
        }
    }

    private class Seed(val path: Path, val score: Double, val inliers: List<Obs>)

    /**
     * How the ball left the tee, on screen, in "screen heights" with x measured from the left edge:
     * position, velocity (per second), and d1, the rate its distance from the camera grows relative to
     * its starting distance (1/s). [LaunchPhysics] turns this into a real speed and direction.
     */
    data class ScreenLaunch(val x: Double, val y: Double, val vx: Double, val vy: Double, val d1: Double, val aspect: Double)

    /**
     * The tracer plus the launch it was fitted from, and how well the ball was followed: in how many
     * frames, and for how long after launch.
     */
    data class Flight(
        val tracer: List<ScreenPoint>,
        val launch: ScreenLaunch,
        val trackedFrames: Int = 0,
        val trackedSeconds: Double = 0.0,
        /** The ball was only seen briefly; most of the tracer is worked out from its launch (see [fitEstimatedFlight]). */
        val estimated: Boolean = false,
        /** For an estimated flight, where the ball was actually seen. */
        val sightings: List<ScreenPoint> = emptyList()
    )

    /** Returns the tracer from the tee along the ball's flight, or an empty list if no ball flight was found. */
    fun fit(shot: CapturedShot): List<ScreenPoint> = fitFlight(shot)?.tracer ?: emptyList()

    /**
     * The flight if the ball could be followed; otherwise, for a shot whose strike was heard (so it's
     * surely a shot), the flight estimated from its first moments.
     */
    fun fitBest(shot: CapturedShot, camera: LaunchPhysics.CameraPose?): Flight? =
        fitFlight(shot) ?: if (shot.heardImpact) fitEstimatedFlight(shot, camera) else null

    /** The ball's flight, or null if none was found. */
    fun fitFlight(shot: CapturedShot): Flight? {
        if (shot.candidates.isEmpty()) return null
        val aspect = shot.aspect.toDouble()
        val t0Ns = shot.candidates.minOf { it.timestampNs }
        val frames = shot.candidates
            .groupBy { it.timestampNs }
            .toSortedMap()
            .map { (ns, list) ->
                val t = (ns - t0Ns) / 1e9
                Frame(t, ns, list.map { Obs(t, it.x * aspect, it.y.toDouble(), ns) })
            }
        val teeX = shot.teeX * aspect
        val teeY = shot.teeY.toDouble()
        val tol = ((shot.ballRadius ?: 0.01f) * 1.5).coerceIn(0.012, 0.04)
        val frameDt = typicalFrameInterval(frames)

        // Without seeing the ball leave the tee there's nothing to anchor the search to. Hunting through
        // the whole recording instead turned swaying trees and a golfer's head into flights, and a wrong
        // tracer is worse than none.
        val launchT = shot.launchTimestampNs?.let { (it - t0Ns) / 1e9 } ?: return null
        val seeds = findSeeds(frames, frameDt, teeX, teeY, tol, launchT, firstSeenLimit(MAX_FIRST_SEEN_DISTANCE, shot.ballRadius), shot.launchSeen)

        // Refining can turn a seed built from three awkwardly spaced sightings into the full flight,
        // so rank seeds only after refining them. A clubhead can briefly follow a launch-like path;
        // the ball is the one that keeps flying.
        for (refined in seeds.map { refine(frames, frameDt, it, tol) }.sortedByDescending { it.score }) {
            val track = grow(frames, frameDt, refined, tol)
            if (track.size < MIN_TRACK_FRAMES) continue
            if (track.last().t - refined.path.t0 < MIN_TRACKED_FLIGHT_S) continue
            val path = refined.path
            // Faster than any golf ball leaves a club, for a ball this size on screen: something else.
            if (shot.ballRadius?.let { launchSpeedMps(path, it) > MAX_BALL_SPEED_MPS } == true) continue
            return Flight(
                toTracer(refined, track, teeX, teeY, t0Ns, aspect),
                ScreenLaunch(path.x0, path.y0, path.launchVx, path.launchVy, path.d1, aspect),
                trackedFrames = track.size,
                trackedSeconds = track.last().t - path.t0
            )
        }
        return null
    }

    /**
     * For a shot whose flight couldn't be followed far enough for [fitFlight], typically a ball a pixel
     * or two across that's lost against trees or sky after a fraction of a second: the launch from the
     * few sightings of the ball leaving the tee, and the rest of the flight estimated from it.
     *
     * Over that short time the launch is fitted with the screen-space path of [fitFlight] with the
     * gravity term fixed: the ball's size on the tee says how far away it is, so how fast gravity pulls
     * it down the screen (g × radius on screen / real radius, in screen heights per s²). From the launch,
     * [LaunchPhysics.projectFlight] flies the ball with drag and lift and draws it from the [camera]; without
     * the camera's pose the arc is drawn without drag. Points past the last sighting are marked estimated.
     */
    fun fitEstimatedFlight(shot: CapturedShot, camera: LaunchPhysics.CameraPose?): Flight? {
        val radius = shot.ballRadius ?: return null
        val launchNs = shot.launchTimestampNs ?: return null
        val aspect = shot.aspect.toDouble()
        val teeX = shot.teeX * aspect
        val teeY = shot.teeY.toDouble()
        val tol = (radius * 1.5).coerceIn(0.012, 0.04)
        val obs = shot.candidates
            .filter { it.timestampNs > launchNs && it.timestampNs <= launchNs + (SHORT_WINDOW_S * 1e9).toLong() }
            .map { Obs((it.timestampNs - launchNs) / 1e9, it.x * aspect, it.y.toDouble(), it.timestampNs) }
            .filter { dist(it.x, it.y, teeX, teeY) > 2 * tol }
            // Not where the golfer moved: the arms and club swinging through to the finish make paths too.
            .filter { o -> shot.changedArea?.let { m -> m.lum(((o.x / aspect) * m.width).toInt().coerceIn(0, m.width - 1), (o.y * m.height).toInt().coerceIn(0, m.height - 1)) == 0 } ?: true }
        if (obs.size < MIN_SHORT_SIGHTINGS) return null
        val b2 = 0.5 * GRAVITY_MPS2 * radius / BALL_RADIUS_M
        val firstShortLimit = firstSeenLimit(MAX_FIRST_SHORT_DISTANCE, radius)
        // How far the ball was, in metres, to keep the launch to speeds a golf ball can have.
        val focal = camera?.let { 0.5 / kotlin.math.tan(it.verticalFovRadians / 2) } ?: TYPICAL_FOCAL
        val distanceM = focal * BALL_RADIUS_M / radius

        class Fit(val t0: Double, val a1: Double, val b1: Double, val d1: Double, val inliers: List<Obs>, val err: Double) {
            fun x(t: Double): Double { val tau = t - t0; return (teeX + a1 * tau) / (1 + d1 * tau) }
            fun y(t: Double): Double { val tau = t - t0; return (teeY + b1 * tau + b2 * tau * tau) / (1 + d1 * tau) }
        }
        fun solve(t0: Double, points: List<Obs>): Fit? {
            val rows = ArrayList<DoubleArray>()
            val rhs = ArrayList<Double>()
            for (p in points) {
                val tau = p.t - t0
                if (tau <= 1e-3) return null
                rows += doubleArrayOf(1.0, 0.0, -p.x); rhs += (p.x - teeX) / tau
                rows += doubleArrayOf(0.0, 1.0, -p.y); rhs += (p.y - teeY - b2 * tau * tau) / tau
            }
            val c = leastSquares(rows, rhs) ?: return null
            return Fit(t0, c[0], c[1], c[2], emptyList(), 0.0)
        }
        fun plausible(f: Fit): Boolean {
            val vx = f.a1 - teeX * f.d1
            val vy = f.b1 - teeY * f.d1
            val across = hypot(vx, vy) * distanceM / focal
            val away = f.d1 * distanceM
            // Going up and away: nobody films a golf shot from where the ball is going.
            return vy < 0 && hypot(vx, vy) >= MIN_LAUNCH_SPEED && f.d1 in MIN_SHORT_D1..60.0 && 1 + f.d1 * SHORT_WINDOW_S > 0.05 &&
                hypot(across, away) <= MAX_BALL_SPEED_MPS
        }
        // The best sighting per frame on the path, if close enough; each must be farther from the tee than the last.
        fun inliers(f: Fit): List<Obs> {
            val chosen = obs.groupBy { it.ns }.values.mapNotNull { frame ->
                frame.minByOrNull { dist(it.x, it.y, f.x(it.t), f.y(it.t)) }?.takeIf { dist(it.x, it.y, f.x(it.t), f.y(it.t)) < SHORT_TOLERANCE * tol }
            }.sortedBy { it.t }
            val out = ArrayList<Obs>()
            for (o in chosen) if (out.isEmpty() || dist(o.x, o.y, teeX, teeY) > dist(out.last().x, out.last().y, teeX, teeY)) out += o
            return out
        }

        // The sound of the strike and the camera's frames can be tens of milliseconds apart, so the launch
        // time is searched around the one given; every pair of sightings proposes a path, and the one seen in
        // the most frames, fitting them best, wins.
        var best: Fit? = null
        val (earliest, latest) = if (shot.launchSeen) SHORT_LAUNCH_EARLIEST_S to SHORT_LAUNCH_LATEST_S
            else HEARD_LAUNCH_EARLIEST_S to HEARD_LAUNCH_LATEST_S
        var t0 = earliest
        while (t0 <= latest + 1e-9) {
            for (i in obs.indices) for (j in i + 1 until obs.size) {
                if (obs[j].ns == obs[i].ns) continue
                val proposal = solve(t0, listOf(obs[i], obs[j]))?.takeIf { plausible(it) } ?: continue
                val pts = inliers(proposal)
                if (pts.size < MIN_SHORT_SIGHTINGS) continue
                // Seen soon after it left, and getting away fast: spots that happen to line up drift slowly.
                if (pts.first().t - t0 > MAX_FIRST_SHORT_SIGHTING_S) continue
                if (pts.first().let { dist(it.x, it.y, teeX, teeY) } > firstShortLimit) continue
                if (pts.last().let { dist(it.x, it.y, teeX, teeY) / (it.t - t0) } < MIN_SEEN_SPEED) continue
                val refit = solve(t0, pts)?.takeIf { plausible(it) } ?: continue
                val err = pts.sumOf { dist(it.x, it.y, refit.x(it.t), refit.y(it.t)).let { d -> d * d } } / pts.size
                val fit = Fit(t0, refit.a1, refit.b1, refit.d1, pts, err)
                val b = best
                if (b == null || pts.size > b.inliers.size || (pts.size == b.inliers.size && err < b.err)) best = fit
            }
            t0 += 0.01
        }
        val fit = best ?: return null
        val launch = ScreenLaunch(teeX, teeY, fit.a1 - teeX * fit.d1, fit.b1 - teeY * fit.d1, fit.d1, aspect)
        val startNs = launchNs + (fit.t0 * 1e9).toLong()
        val seenUntilNs = fit.inliers.last().ns
        // With the camera's pose the launch can be checked as a real one (see projectFlight); without, drawn on screen.
        val tracer = if (camera != null) LaunchPhysics.projectFlight(launch, radius, camera, startNs, seenUntilNs)
            else screenArc(fit.a1, fit.b1, b2, fit.d1, teeX, teeY, aspect, startNs, seenUntilNs)
        if (tracer.size < 2) return null
        val sightings = fit.inliers.map { ScreenPoint((it.x / aspect).toFloat(), it.y.toFloat(), it.ns / 1_000_000L) }
        return Flight(tracer, launch, fit.inliers.size, fit.inliers.last().t - fit.t0, estimated = true, sightings = sightings)
    }

    /**
     * Without the camera's pose: the fitted screen path carried on, without drag, until the ball is back
     * at the ground (taking the horizon as level with the middle of the screen).
     */
    private fun screenArc(
        a1: Double, b1: Double, b2: Double, d1: Double, x0: Double, y0: Double, aspect: Double, startNs: Long, seenUntilNs: Long
    ): List<ScreenPoint> {
        val horizon = 0.5
        val land = (-(b1 - horizon * d1) / b2).coerceAtMost(MAX_ESTIMATED_FLIGHT_S)
        if (land <= 0) return emptyList()
        val out = ArrayList<ScreenPoint>()
        var tau = 0.0
        while (tau <= land) {
            val den = 1 + d1 * tau
            if (den < 0.05) break
            val ns = startNs + (tau * 1e9).toLong()
            out += ScreenPoint(
                ((x0 + a1 * tau) / den / aspect).toFloat(), ((y0 + b1 * tau + b2 * tau * tau) / den).toFloat(), ns / 1_000_000L,
                if (ns <= seenUntilNs) 0.95f else LaunchPhysics.ESTIMATED_CONFIDENCE
            )
            tau += 1.0 / 60
        }
        return out
    }

    private fun toTracer(seed: Seed, track: List<Obs>, teeX: Double, teeY: Double, t0Ns: Long, aspect: Double): List<ScreenPoint> {
        val refined = seed
        val launchNs = t0Ns + (refined.path.t0 * 1e9).toLong()
        // Early on the fitted flight path is more accurate than smoothing a few fast-moving points.
        val path = refined.path
        val early = track.filter { it.t - path.t0 <= SEED_WINDOW_S }.map { Obs(it.t, path.x(it.t), path.y(it.t), it.ns) }
        val late = smooth(track).filter { it.t - path.t0 > SEED_WINDOW_S }
        val raw = listOf(Obs(path.t0, teeX, teeY, launchNs)) + early + late
        return densify(raw).map { o ->
            ScreenPoint((o.x / aspect).toFloat(), o.y.toFloat(), o.ns / 1_000_000L, confidence = 0.95f)
        }
    }

    /** The best few distinct seeds, best first. */
    /**
     * How fast the ball left, in m/s: its size on screen says how far away it was (with a typical phone
     * camera's field of view), which turns its speed across the screen and away from the camera into metres.
     */
    private fun launchSpeedMps(path: Path, ballRadius: Float): Double {
        if (ballRadius <= 0f) return 0.0
        val distanceM = TYPICAL_FOCAL * BALL_RADIUS_M / ballRadius
        val across = hypot(path.launchVx, path.launchVy) * distanceM / TYPICAL_FOCAL
        return hypot(across, path.d1 * distanceM)
    }

    /** How far from the tee, in screen heights, the ball may first be seen: further for a ball close to the camera. */
    private fun firstSeenLimit(base: Double, ballRadius: Float?): Double =
        max(base, min(MAX_FIRST_SEEN_DISTANCE_CLOSE, (ballRadius ?: 0f) * MAX_FIRST_SEEN_RADII))

    private fun findSeeds(
        frames: List<Frame>, frameDt: Double, teeX: Double, teeY: Double, tol: Double, launchT: Double, firstSeenLimit: Double,
        launchSeen: Boolean
    ): List<Seed> {
        val top = ArrayList<Seed>()
        fun offer(seed: Seed) {
            if (top.size == SEEDS_TO_TRY && seed.score <= top.last().score) return
            if (!isPlausible(seed, frameDt, tol)) return
            // Many hypotheses land on the same object; keep the best of each.
            val same = top.indexOfFirst { other ->
                other.inliers.count { it in seed.inliers } > 0.8 * min(other.inliers.size, seed.inliers.size)
            }
            if (same >= 0) {
                if (top[same].score >= seed.score) return
                top.removeAt(same)
            }
            top.add(seed)
            top.sortByDescending { it.score }
            if (top.size > SEEDS_TO_TRY) top.removeAt(top.size - 1)
        }
        for ((i, f1) in frames.withIndex()) {
            if (f1.t < launchT - 0.05 || f1.t > launchT + MAX_FIRST_SEEN_AFTER_LAUNCH_S) continue
            for (p1 in f1.obs) {
                val d1 = dist(p1.x, p1.y, teeX, teeY)
                if (d1 > firstSeenLimit) continue
                // It got there from the tee since the launch: something near the tee long after is just clutter.
                if (d1 / max(p1.t - launchT, frameDt / 2) < MIN_SEEN_SPEED) continue
                for (j in i + 1 until frames.size) {
                    val f2 = frames[j]
                    if (f2.t - f1.t > MAX_SECOND_SIGHTING_S) break
                    for (p2 in f2.obs) {
                        val d2 = dist(p2.x, p2.y, teeX, teeY)
                        if (d2 < d1 + 0.5 * tol) continue // Must be moving away from the tee.
                        val speed = dist(p1.x, p1.y, p2.x, p2.y) / (f2.t - f1.t)
                        if (speed < MIN_LAUNCH_SPEED) continue
                        // Launch time, first guess: back up from p1 to the tee at the speed between p1 and p2.
                        // A ball flying away from a camera close behind slows down a lot on screen, which
                        // makes this guess early, so only a guess well after the launch rules a pair out.
                        val linearT0 = p1.t - d1 / speed
                        if (linearT0 - launchT > 0.15 || launchT - linearT0 > MAX_EARLY_LINEAR_LAUNCH_S) continue
                        val dx12 = p2.x - p1.x
                        val dy12 = p2.y - p1.y

                        for (k in j + 1 until frames.size) {
                            val f3 = frames[k]
                            if (f3.t - f1.t > MAX_THIRD_SIGHTING_S) break
                            for (p3 in f3.obs) {
                                if (dist(p3.x, p3.y, teeX, teeY) < d2 + 0.5 * tol) continue
                                // No sharp turns between the three sightings.
                                val dx23 = p3.x - p2.x
                                val dy23 = p3.y - p2.y
                                val cos = (dx12 * dx23 + dy12 * dy23) /
                                    (sqrt(dx12 * dx12 + dy12 * dy12) * sqrt(dx23 * dx23 + dy23 * dy23) + 1e-12)
                                if (cos < 0.25) continue

                                for (t0 in launchTimes(p1, p2, p3, d1, d2, teeX, teeY, linearT0, frameDt, launchT, launchSeen)) {
                                    // Three sightings pin all six unknowns, so a little blur or timing error in a fast
                                    // early one can throw the depth's acceleration far off; then try again without it.
                                    val full = solvePath(t0, teeX, teeY, listOf(p1, p2, p3)) ?: continue
                                    val path = if (launchesLikeABall(full) || abs(full.d2) <= MAX_DEPTH_ACCELERATION) full
                                        else solvePath(t0, teeX, teeY, listOf(p1, p2, p3), depthAcceleration = false) ?: continue
                                    if (!launchesLikeABall(path)) continue
                                    var seed = scoreSeed(frames, path, tol)
                                    // A curve through three close, jittery sightings is rough. If it
                                    // looks promising, refit it to everything it matched and rescore.
                                    if (seed.inliers.size >= MIN_SEED_FRAMES - 1) {
                                        (solvePath(t0, teeX, teeY, seed.inliers)?.takeIf { launchesLikeABall(it) }
                                            ?: solvePath(t0, teeX, teeY, seed.inliers, depthAcceleration = false))
                                            ?.takeIf { launchesLikeABall(it) }
                                            ?.let { scoreSeed(frames, it, tol) }
                                            ?.takeIf { it.score > seed.score }
                                            ?.let { seed = it }
                                    }
                                    offer(seed)
                                }
                            }
                        }
                    }
                }
            }
        }
        return top
    }

    /**
     * Launch times to try for three sightings. Near launch the ball moves so fast that a few
     * milliseconds off is already a miss. Backing up at constant speed guesses too early for a ball
     * that is slowing down, so the distance from the tee is also fitted with a curve through all
     * three sightings and followed back to zero. A few times around each guess are tried.
     */
    private fun launchTimes(
        p1: Obs, p2: Obs, p3: Obs, d1: Double, d2: Double, teeX: Double, teeY: Double,
        linearT0: Double, frameDt: Double, launchT: Double, launchSeen: Boolean
    ): List<Double> {
        // Seen to leave: it went between the frame before and this one, and that's all there is to try.
        if (launchSeen) {
            val out = ArrayList<Double>(8)
            for (k in -6..1) {
                val t0 = launchT + k * frameDt / 6
                if (t0 < p1.t - 1e-3) out += t0
            }
            return out
        }
        val guesses = ArrayList<Double>(3)
        // When the strike was heard: the sound's clock and the camera's can be tens of milliseconds apart.
        guesses += launchT
        guesses += linearT0
        val d3 = dist(p3.x, p3.y, teeX, teeY)
        // Quadratic d(t) = c0 + c1·(t - t1) + c2·(t - t1)² through the three sightings.
        val u2 = p2.t - p1.t
        val u3 = p3.t - p1.t
        val c2 = ((d3 - d1) / u3 - (d2 - d1) / u2) / (u3 - u2)
        val c1 = (d2 - d1) / u2 - c2 * u2
        val disc = c1 * c1 - 4 * c2 * d1
        if (abs(c2) > 1e-9 && disc >= 0) {
            val r = sqrt(disc)
            listOf((-c1 + r) / (2 * c2), (-c1 - r) / (2 * c2))
                .filter { it < 0 && it > -MAX_FIRST_SEEN_AFTER_LAUNCH_S }
                .maxOrNull()?.let { guesses += p1.t + it }
        }
        val out = ArrayList<Double>(12)
        for ((i, g) in guesses.withIndex()) for (k in if (i == 0) -1..1 else -1..3) {
            val t0 = g + k * frameDt / 6
            if (t0 >= p1.t - 1e-3) continue
            if (abs(t0 - launchT) > 0.1) continue
            if (out.none { abs(it - t0) < frameDt / 12 }) out += t0
        }
        return out
    }

    /**
     * Least-squares perspective path through the tee at [t0] and [points] (exact for three points).
     * Multiplying out the denominator makes each coordinate linear in the six unknowns:
     *   a1 + a2·τ − x·d1 − x·d2·τ = (x − x0)/τ,  and the same for y with b1, b2.
     */
    private fun solvePath(t0: Double, x0: Double, y0: Double, points: List<Obs>, depthAcceleration: Boolean = true): Path? {
        // Three sightings, tried by the million while seeding: solved directly, without allocating rows.
        if (points.size == 3) return solveThree(t0, x0, y0, points[0], points[1], points[2], depthAcceleration)
        val rows = ArrayList<DoubleArray>(points.size * 2)
        val rhs = ArrayList<Double>(points.size * 2)
        for (p in points) {
            val tau = p.t - t0
            if (tau <= 1e-4) return null
            if (depthAcceleration) {
                rows += doubleArrayOf(1.0, tau, 0.0, 0.0, -p.x, -p.x * tau); rhs += (p.x - x0) / tau
                rows += doubleArrayOf(0.0, 0.0, 1.0, tau, -p.y, -p.y * tau); rhs += (p.y - y0) / tau
            } else {
                rows += doubleArrayOf(1.0, tau, 0.0, 0.0, -p.x); rhs += (p.x - x0) / tau
                rows += doubleArrayOf(0.0, 0.0, 1.0, tau, -p.y); rhs += (p.y - y0) / tau
            }
        }
        val c = leastSquares(rows, rhs) ?: return null
        return Path(t0, x0, y0, c[0], c[1], c[2], c[3], c[4], if (depthAcceleration) c[5] else 0.0)
    }

    /**
     * [solvePath] for exactly three sightings: six equations in the six unknowns, solved by elimination, or
     * without depth acceleration, five unknowns by least squares (normal equations).
     */
    private fun solveThree(t0: Double, x0: Double, y0: Double, p1: Obs, p2: Obs, p3: Obs, depthAcceleration: Boolean): Path? {
        val n = if (depthAcceleration) 6 else 5
        // Rows: [a1, a2, b1, b2, d1, d2 | rhs]
        val m = DoubleArray(6 * 7)
        for ((k, p) in arrayOf(p1, p2, p3).withIndex()) {
            val tau = p.t - t0
            if (tau <= 1e-4) return null
            val rx = (2 * k) * 7
            m[rx + 0] = 1.0; m[rx + 1] = tau; m[rx + 4] = -p.x; m[rx + 5] = -p.x * tau; m[rx + 6] = (p.x - x0) / tau
            val ry = (2 * k + 1) * 7
            m[ry + 2] = 1.0; m[ry + 3] = tau; m[ry + 4] = -p.y; m[ry + 5] = -p.y * tau; m[ry + 6] = (p.y - y0) / tau
        }
        // A (rows × n) and b; without d2, the normal equations AᵀA·c = Aᵀb.
        val a = DoubleArray(n * (n + 1))
        if (depthAcceleration) {
            for (r in 0 until 6) { for (c in 0 until 6) a[r * 7 + c] = m[r * 7 + c]; a[r * 7 + 6] = m[r * 7 + 6] }
        } else {
            for (i in 0 until 5) {
                for (j in 0 until 5) { var sum = 0.0; for (r in 0 until 6) sum += m[r * 7 + i] * m[r * 7 + j]; a[i * 6 + j] = sum }
                var sum = 0.0; for (r in 0 until 6) sum += m[r * 7 + i] * m[r * 7 + 6]; a[i * 6 + 5] = sum
            }
        }
        val w = n + 1
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r * w + col]) > abs(a[pivot * w + col])) pivot = r
            if (abs(a[pivot * w + col]) < 1e-12) return null
            if (pivot != col) for (c in 0 until w) { val t = a[col * w + c]; a[col * w + c] = a[pivot * w + c]; a[pivot * w + c] = t }
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r * w + col] / a[col * w + col]
                if (f == 0.0) continue
                for (c in col until w) a[r * w + c] -= f * a[col * w + c]
            }
        }
        fun c(i: Int) = a[i * w + n] / a[i * w + i]
        return Path(t0, x0, y0, c(0), c(1), c(2), c(3), c(4), if (depthAcceleration) c(5) else 0.0)
    }

    /**
     * Leaves the tee upward (screen y shrinks), fast, and stays in front of the camera. d1 and d2 are the
     * ball's speed and acceleration away from the camera relative to its starting distance; gravity and
     * drag only allow so much acceleration, while arbitrary motion fits with extreme values.
     */
    private fun launchesLikeABall(path: Path): Boolean {
        if (path.d1 < -2 || path.d1 > 60 || abs(path.d2) > MAX_DEPTH_ACCELERATION) return false
        val vx = path.launchVx
        val vy = path.launchVy
        if (vy >= 0) return false
        if (sqrt(vx * vx + vy * vy) < MIN_LAUNCH_SPEED) return false
        return path.inFrontFor(SEED_WINDOW_S)
    }

    /**
     * Whether a seed looks like a ball leaving the tee rather than a hand or club that happens to line up
     * with a path: the ball is seen in most frames and gets well away from the tee.
     */
    private fun isPlausible(seed: Seed, frameDt: Double, tol: Double): Boolean {
        val inliers = seed.inliers
        if (inliers.size < MIN_SEED_FRAMES) return false
        val path = seed.path

        // Farther from the tee at first; from behind, a chip can drift back down once it hangs.
        var farthest = 0.0
        for (o in inliers.take(inliers.size / 2 + 1)) {
            val d = dist(o.x, o.y, path.x0, path.y0)
            if (d < farthest - tol) return false
            farthest = max(farthest, d)
        }
        if (inliers.maxOf { dist(it.x, it.y, path.x0, path.y0) } < MIN_SEED_TRAVEL) return false

        val span = inliers.last().t - inliers.first().t
        if (span <= 0) return false
        return inliers.size >= 0.5 * (span / frameDt + 1)
    }

    /**
     * The seed's launch time came from assuming constant speed between two sightings, which is early
     * for a ball that is slowing down. Refit the path to all of the seed's points at a range of launch
     * times, keep the one that fits best, and pick up any frames that were missed.
     */
    private fun refine(frames: List<Frame>, frameDt: Double, seed: Seed, tol: Double): Seed {
        var current = seed
        repeat(3) {
            val pts = current.inliers
            val first = pts.first().t
            var bestPath: Path? = null
            var bestErr = Double.MAX_VALUE
            var t0 = first - 2 * frameDt
            while (t0 < first - 1e-3) {
                val path = solvePath(t0, seed.path.x0, seed.path.y0, pts)?.takeIf { launchesLikeABall(it) }
                    ?: solvePath(t0, seed.path.x0, seed.path.y0, pts, depthAcceleration = false)
                if (path != null && launchesLikeABall(path)) {
                    val err = pts.sumOf { o -> dist(o.x, o.y, path.x(o.t), path.y(o.t)).let { it * it } }
                    if (err < bestErr) { bestErr = err; bestPath = path }
                }
                t0 += frameDt / 10
            }
            val path = bestPath ?: return current
            val rescored = scoreSeed(frames, path, tol)
            if (rescored.inliers.size < current.inliers.size || !isPlausible(rescored, frameDt, tol)) return current
            current = rescored
        }
        return current
    }

    private fun scoreSeed(frames: List<Frame>, path: Path, tol: Double): Seed {
        var score = 0.0
        val inliers = ArrayList<Obs>()
        // Straight to the first frame after the launch: this runs for every candidate path.
        var lo = 0
        var hi = frames.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (frames[mid].t <= path.t0) lo = mid + 1 else hi = mid }
        val onTee = 4 * tol * tol
        for (i in lo until frames.size) {
            val f = frames[i]
            if (f.t > path.t0 + SEED_WINDOW_S) break
            val px = path.x(f.t)
            val py = path.y(f.t)
            var bestD2 = Double.MAX_VALUE
            var bestObs: Obs? = null
            for (o in f.obs) {
                // Anything still on the tee (the clubhead at impact) says nothing about the flight.
                val tx = o.x - path.x0
                val ty = o.y - path.y0
                if (tx * tx + ty * ty < onTee) continue
                val dx = o.x - px
                val dy = o.y - py
                val d2 = dx * dx + dy * dy
                if (d2 < bestD2) { bestD2 = d2; bestObs = o }
            }
            if (bestObs != null && bestD2 < tol * tol) {
                score += 1.0 - sqrt(bestD2) / tol
                inliers.add(bestObs)
            }
        }
        return Seed(path, score, inliers)
    }

    /** Follows the ball past the seed window, predicting each frame from the last few positions. */
    private fun grow(frames: List<Frame>, frameDt: Double, seed: Seed, tol: Double): List<Obs> {
        val track = ArrayList(seed.inliers)
        if (track.isEmpty()) return track
        // For each grown point: whether it needed the gate widened for a gap to be accepted.
        val loose = ArrayList<Boolean>()

        for (f in frames) {
            val last = track.last()
            if (f.t <= last.t) continue
            if (f.t - last.t > MAX_GAP_S || f.t - seed.path.t0 > MAX_FLIGHT_S) break

            val recent = track.takeLast(8)
            // Early on, the physical path predicts better than a fit to a few fast-changing points.
            val (px, py) = if (f.t - seed.path.t0 <= SEED_WINDOW_S) {
                seed.path.x(f.t) to seed.path.y(f.t)
            } else {
                predict(recent, f.t)
            }
            val gapFrames = max(1.0, (f.t - last.t) / frameDt)
            val gate = tol * min(2.5, 1.5 + 0.25 * (gapFrames - 1))

            // Direction of travel, to reject candidates behind the ball.
            val prior = recent[max(0, recent.size - 3)]
            val dirX = last.x - prior.x
            val dirY = last.y - prior.y

            var best: Obs? = null
            var bestD = gate
            for (o in f.obs) {
                val d = dist(o.x, o.y, px, py)
                if (d >= bestD) continue
                if ((o.x - last.x) * dirX + (o.y - last.y) * dirY < 0 && dist(o.x, o.y, last.x, last.y) > tol) continue
                best = o
                bestD = d
            }
            if (best != null) {
                track.add(best)
                loose.add(gapFrames > 1.5 && bestD > tol)
            }
        }

        // Once the ball is gone, the widened gate can still chain together stray specks. A point that
        // only got in through the widened gate has to be followed by three tight, gap-free frames.
        val grownFrom = seed.inliers.size
        for (i in loose.indices) {
            if (!loose[i]) continue
            val confirmed = i + 3 < loose.size && (1..3).all { k ->
                val prev = track[grownFrom + i + k - 1]
                val cur = track[grownFrom + i + k]
                !loose[i + k] && (cur.t - prev.t) < 1.5 * frameDt
            }
            if (!confirmed) {
                track.subList(grownFrom + i, track.size).clear()
                break
            }
        }

        // Drop trailing points that don't fit the motion leading up to them.
        while (track.size > seed.inliers.size && track.size >= 7) {
            val last = track.last()
            val (px, py) = predict(track.subList(track.size - 7, track.size - 1), last.t)
            if (dist(last.x, last.y, px, py) <= tol) break
            track.removeAt(track.size - 1)
        }
        return track
    }

    /** Least-squares quadratic (linear for fewer than 3 points) in time, per axis, evaluated at [t]. */
    private fun predict(points: List<Obs>, t: Double): Pair<Double, Double> {
        val ref = points.last().t
        val ts = points.map { it.t - ref }
        val degree = if (points.size >= 3) 2 else 1
        val cx = polyFit(ts, points.map { it.x }, degree)
        val cy = polyFit(ts, points.map { it.y }, degree)
        val dt = t - ref
        return polyEval(cx, dt) to polyEval(cy, dt)
    }

    /** Local quadratic smoothing over neighboring track points, to take out detection jitter. */
    private fun smooth(track: List<Obs>): List<Obs> {
        if (track.size < 5) return track
        return track.indices.map { i ->
            val lo = max(0, i - 2)
            val hi = min(track.size - 1, i + 2)
            val window = track.subList(lo, hi + 1)
            val ts = window.map { it.t - track[i].t }
            val degree = if (window.size >= 5) 2 else 1
            val cx = polyFit(ts, window.map { it.x }, degree)
            val cy = polyFit(ts, window.map { it.y }, degree)
            Obs(track[i].t, cx[0], cy[0], track[i].ns)
        }
    }

    /** Catmull-Rom interpolation so the tracer is drawn as a smooth curve rather than a polyline. */
    private fun densify(points: List<Obs>, steps: Int = 4): List<Obs> {
        if (points.size < 3) return points
        val out = ArrayList<Obs>()
        for (i in 0 until points.size - 1) {
            val p0 = points[max(0, i - 1)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[min(points.size - 1, i + 2)]
            for (s in 0 until steps) {
                val u = s.toDouble() / steps
                fun cr(a: Double, b: Double, c: Double, d: Double) =
                    0.5 * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u * u + (-a + 3 * b - 3 * c + d) * u * u * u)
                out.add(
                    Obs(
                        p1.t + (p2.t - p1.t) * u,
                        cr(p0.x, p1.x, p2.x, p3.x),
                        cr(p0.y, p1.y, p2.y, p3.y),
                        p1.ns + ((p2.ns - p1.ns) * u).toLong()
                    )
                )
            }
        }
        out.add(points.last())
        return out
    }

    private fun typicalFrameInterval(frames: List<Frame>): Double {
        if (frames.size < 2) return 1.0 / 30
        val gaps = (1 until frames.size).map { frames[it].t - frames[it - 1].t }.sorted()
        return gaps[gaps.size / 2].coerceIn(1.0 / 240, 0.2)
    }

    /** Least-squares polynomial coefficients [c0, c1, (c2)]. */
    private fun polyFit(ts: List<Double>, vs: List<Double>, degree: Int): DoubleArray {
        val rows = ts.map { t -> DoubleArray(degree + 1) { p -> Math.pow(t, p.toDouble()) } }
        // Degenerate (e.g. all samples at one time): fall back to the mean value.
        return leastSquares(rows, vs) ?: DoubleArray(degree + 1).also { it[0] = vs.average() }
    }

    /**
     * Solves rows·c ≈ rhs in the least-squares sense by Householder QR, or null if (nearly) singular.
     * QR rather than normal equations: filmed from behind, x barely changes, which makes some columns
     * nearly parallel, and normal equations would square that into numerical garbage.
     */
    private fun leastSquares(rows: List<DoubleArray>, rhs: List<Double>): DoubleArray? {
        val m = rows.size
        val n = rows.first().size
        if (m < n) return null
        val a = Array(m) { rows[it].copyOf() }
        val b = DoubleArray(m) { rhs[it] }
        var maxDiag = 0.0
        for (k in 0 until n) {
            var norm = 0.0
            for (i in k until m) norm += a[i][k] * a[i][k]
            norm = sqrt(norm)
            if (norm == 0.0) return null
            val alpha = if (a[k][k] > 0) -norm else norm
            val v = DoubleArray(m)
            for (i in k until m) v[i] = a[i][k]
            v[k] -= alpha
            var vv = 0.0
            for (i in k until m) vv += v[i] * v[i]
            if (vv == 0.0) return null
            for (j in k until n) {
                var dot = 0.0
                for (i in k until m) dot += v[i] * a[i][j]
                val f = 2 * dot / vv
                for (i in k until m) a[i][j] -= f * v[i]
            }
            var dot = 0.0
            for (i in k until m) dot += v[i] * b[i]
            val f = 2 * dot / vv
            for (i in k until m) b[i] -= f * v[i]
            maxDiag = max(maxDiag, abs(a[k][k]))
        }
        val c = DoubleArray(n)
        for (k in n - 1 downTo 0) {
            if (abs(a[k][k]) < 1e-9 * maxDiag) return null
            var sum = b[k]
            for (j in k + 1 until n) sum -= a[k][j] * c[j]
            c[k] = sum / a[k][k]
        }
        return c
    }

    private fun polyEval(c: DoubleArray, t: Double): Double {
        var r = 0.0
        for (i in c.indices.reversed()) r = r * t + c[i]
        return r
    }

    private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }
}
