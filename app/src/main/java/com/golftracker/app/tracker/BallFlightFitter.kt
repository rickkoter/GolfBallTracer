package com.golftracker.app.tracker

import com.golftracker.app.model.ScreenPoint
import kotlin.math.abs
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
    private const val MIN_LAUNCH_SPEED = 0.25 // screen heights per second
    private const val MIN_SEED_FRAMES = 5
    private const val MIN_SEED_TRAVEL = 0.15 // screen heights from the tee within the seed window
    private const val MIN_TRACK_FRAMES = 10
    private const val MIN_TRACKED_FLIGHT_S = 0.4
    private const val SEEDS_TO_TRY = 10
    private const val MAX_GAP_S = 0.5
    private const val MAX_FLIGHT_S = 10.0

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

    /** The tracer plus the launch it was fitted from. */
    data class Flight(val tracer: List<ScreenPoint>, val launch: ScreenLaunch)

    /** Returns the tracer from the tee along the ball's flight, or an empty list if no ball flight was found. */
    fun fit(shot: CapturedShot): List<ScreenPoint> = fitFlight(shot)?.tracer ?: emptyList()

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
        val seeds = findSeeds(frames, frameDt, teeX, teeY, tol, launchT)

        // Refining can turn a seed built from three awkwardly spaced sightings into the full flight,
        // so rank seeds only after refining them. A clubhead can briefly follow a launch-like path;
        // the ball is the one that keeps flying.
        for (refined in seeds.map { refine(frames, frameDt, it, tol) }.sortedByDescending { it.score }) {
            val track = grow(frames, frameDt, refined, tol)
            if (track.size < MIN_TRACK_FRAMES) continue
            if (track.last().t - refined.path.t0 < MIN_TRACKED_FLIGHT_S) continue
            val path = refined.path
            return Flight(
                toTracer(refined, track, teeX, teeY, t0Ns, aspect),
                ScreenLaunch(path.x0, path.y0, path.launchVx, path.launchVy, path.d1, aspect)
            )
        }
        return null
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
    private fun findSeeds(
        frames: List<Frame>, frameDt: Double, teeX: Double, teeY: Double, tol: Double, launchT: Double
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
                if (d1 > MAX_FIRST_SEEN_DISTANCE) continue
                for (j in i + 1 until frames.size) {
                    val f2 = frames[j]
                    if (f2.t - f1.t > MAX_SECOND_SIGHTING_S) break
                    for (p2 in f2.obs) {
                        val d2 = dist(p2.x, p2.y, teeX, teeY)
                        if (d2 < d1 + 0.5 * tol) continue // Must be moving away from the tee.
                        val speed = dist(p1.x, p1.y, p2.x, p2.y) / (f2.t - f1.t)
                        if (speed < MIN_LAUNCH_SPEED) continue
                        // Launch time, first guess: back up from p1 to the tee at the speed between p1 and p2.
                        val linearT0 = p1.t - d1 / speed
                        if (p1.t - linearT0 > MAX_FIRST_SEEN_AFTER_LAUNCH_S) continue
                        if (abs(linearT0 - launchT) > 0.15) continue
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

                                for (t0 in launchTimes(p1, p2, p3, d1, d2, teeX, teeY, linearT0, frameDt, launchT)) {
                                    val path = solvePath(t0, teeX, teeY, listOf(p1, p2, p3)) ?: continue
                                    if (!launchesLikeABall(path)) continue
                                    var seed = scoreSeed(frames, path, tol)
                                    // A curve through three close, jittery sightings is rough. If it
                                    // looks promising, refit it to everything it matched and rescore.
                                    if (seed.inliers.size >= MIN_SEED_FRAMES - 1) {
                                        solvePath(t0, teeX, teeY, seed.inliers)
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
        linearT0: Double, frameDt: Double, launchT: Double
    ): List<Double> {
        val guesses = ArrayList<Double>(2)
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
        val out = ArrayList<Double>(10)
        for (g in guesses) for (k in -1..3) {
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
    private fun solvePath(t0: Double, x0: Double, y0: Double, points: List<Obs>): Path? {
        val rows = ArrayList<DoubleArray>(points.size * 2)
        val rhs = ArrayList<Double>(points.size * 2)
        for (p in points) {
            val tau = p.t - t0
            if (tau <= 1e-4) return null
            rows += doubleArrayOf(1.0, tau, 0.0, 0.0, -p.x, -p.x * tau); rhs += (p.x - x0) / tau
            rows += doubleArrayOf(0.0, 0.0, 1.0, tau, -p.y, -p.y * tau); rhs += (p.y - y0) / tau
        }
        val c = leastSquares(rows, rhs) ?: return null
        return Path(t0, x0, y0, c[0], c[1], c[2], c[3], c[4], c[5])
    }

    /**
     * Leaves the tee upward (screen y shrinks), fast, and stays in front of the camera. d1 and d2 are the
     * ball's speed and acceleration away from the camera relative to its starting distance; gravity and
     * drag only allow so much acceleration, while arbitrary motion fits with extreme values.
     */
    private fun launchesLikeABall(path: Path): Boolean {
        if (path.d1 < -2 || path.d1 > 60 || abs(path.d2) > 6) return false
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
                val path = solvePath(t0, seed.path.x0, seed.path.y0, pts)
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
        for (f in frames) {
            if (f.t <= path.t0) continue
            if (f.t > path.t0 + SEED_WINDOW_S) break
            val px = path.x(f.t)
            val py = path.y(f.t)
            var bestD = Double.MAX_VALUE
            var bestObs: Obs? = null
            for (o in f.obs) {
                // Anything still on the tee (the clubhead at impact) says nothing about the flight.
                if (dist(o.x, o.y, path.x0, path.y0) < 2 * tol) continue
                val d = dist(o.x, o.y, px, py)
                if (d < bestD) { bestD = d; bestObs = o }
            }
            if (bestObs != null && bestD < tol) {
                score += 1.0 - bestD / tol
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
