package com.golftracker.app.tracker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Downsampled luminance frame, already rotated upright and cropped to exactly what the
 * preview shows on screen, so grid coordinates normalized by width/height are screen coordinates.
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val timestampNs: Long
) {
    fun lum(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
}

/** A moving compact blob seen in one frame. x/y are screen-normalized (0..1). */
data class BallCandidate(
    val x: Float,
    val y: Float,
    val timestampNs: Long,
    val score: Float
)

/** Everything collected while recording, handed to [BallFlightFitter] after the shot. */
/** The ball found where the user tapped: center in screen fractions, radius as a fraction of screen height. */
data class TeeBall(val x: Float, val y: Float, val radius: Float)

data class CapturedShot(
    val candidates: List<BallCandidate>,
    val teeX: Float,
    val teeY: Float,
    /** Ball radius as a fraction of screen height, or null if the ball couldn't be measured on the tee. */
    val ballRadius: Float?,
    /** Sensor timestamp of the frame the ball left the tee, or null if launch wasn't detected. */
    val launchTimestampNs: Long?,
    /** Screen width / height. */
    val aspect: Float,
    /** Where the user actually tapped, before snapping to the ball. */
    val tapX: Float = teeX,
    val tapY: Float = teeY,
    val framesProcessed: Int = 0,
    /** Kept only when recording for diagnostics: the first few frames and the most recent ones. */
    val firstFrames: List<LumaFrame> = emptyList(),
    val recentFrames: List<LumaFrame> = emptyList()
)

/**
 * Collects ball candidates while recording without trying to decide live which one is the ball.
 *
 * Per frame, differencing against the frames before and after finds things that moved, and connected
 * components turn those pixels into blobs. Small, isolated blobs are kept as candidates. Separately, the tapped tee ball is
 * watched so the frame where it disappears (the launch) can be timestamped.
 *
 * Called from the camera analysis thread ([process]) and the UI thread (everything else).
 */
class ShotCaptureSession(
    private val maxCandidatesPerFrame: Int = 8,
    /** Keep raw frames so a shot can be saved and replayed offline. */
    private val keepFrames: Boolean = false,
    /** Called on the camera thread when the ball leaves the tee (true) or turns out to still be there (false). */
    private val onLaunchDetected: ShotCaptureSession.(Boolean) -> Unit = {},
    /** Called on the camera thread once the tapped spot has been checked: the ball found there, or null. */
    private val onTeeMeasured: ShotCaptureSession.(TeeBall?) -> Unit = {}
) {
    private val lock = Any()

    private var armed = false
    private var teeX = 0f
    private var teeY = 0f
    private var tapX = 0f
    private var tapY = 0f
    private var framesProcessed = 0
    private val firstFrames = ArrayList<LumaFrame>()
    private val recentFrames = ArrayDeque<LumaFrame>()
    private var teeRefined = false
    private var ballRadiusCells: Float? = null
    private var aspect = 0.5f
    private var gridHeight = 1

    /** A frame and how far the camera had moved by then since recording started, in grid cells. */
    private class Tracked(val frame: LumaFrame, val ox: Int, val oy: Int) {
        /**
         * Darkest and brightest value within ±1 pixel of each pixel. Comparing against this range
         * instead of the single pixel ignores sharp edges wobbling by a pixel, which stabilization
         * and hand tremor cause constantly and which would otherwise look like motion.
         */
        val lo: ByteArray
        val hi: ByteArray
        init {
            val w = frame.width
            val h = frame.height
            val d = frame.data
            val rowLo = IntArray(w * h)
            val rowHi = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var mn = d[i].toInt() and 0xFF
                var mx = mn
                if (x > 0) { val v = d[i - 1].toInt() and 0xFF; if (v < mn) mn = v; if (v > mx) mx = v }
                if (x < w - 1) { val v = d[i + 1].toInt() and 0xFF; if (v < mn) mn = v; if (v > mx) mx = v }
                rowLo[i] = mn
                rowHi[i] = mx
            }
            lo = ByteArray(w * h)
            hi = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var mn = rowLo[i]
                var mx = rowHi[i]
                if (y > 0) { mn = min(mn, rowLo[i - w]); mx = max(mx, rowHi[i - w]) }
                if (y < h - 1) { mn = min(mn, rowLo[i + w]); mx = max(mx, rowHi[i + w]) }
                lo[i] = mn.toByte()
                hi[i] = mx.toByte()
            }
        }
    }

    /** Recent frames; candidates are found for the middle one, so it can be compared both ways. */
    private val history = ArrayDeque<Tracked>()
    private var cameraX = 0
    private var cameraY = 0
    private val candidates = ArrayList<BallCandidate>()

    private val launch = TeeLaunchDetector()

    val isArmed: Boolean get() = synchronized(lock) { armed }

    /**
     * How far the camera has shifted the picture since recording started, as a fraction of the screen.
     * Positions the session reports are relative to the scene at the start, so add this to draw them
     * over what the camera shows now.
     */
    fun cameraShift(): Pair<Float, Float> = synchronized(lock) {
        val last = history.lastOrNull()?.frame ?: return 0f to 0f
        cameraX.toFloat() / last.width to cameraY.toFloat() / last.height
    }

    /** Starts a new capture with the ball the user tapped at screen-normalized (x, y). */
    fun arm(x: Float, y: Float) = synchronized(lock) {
        armed = true
        cameraX = 0
        cameraY = 0
        teeX = x
        teeY = y
        tapX = x
        tapY = y
        framesProcessed = 0
        firstFrames.clear()
        recentFrames.clear()
        teeRefined = false
        ballRadiusCells = null
        history.clear()
        candidates.clear()
        launch.reset()
    }

    /** Stops capturing and returns what was collected. */
    fun stop(): CapturedShot = synchronized(lock) {
        armed = false
        history.clear()
        CapturedShot(
            candidates = candidates.toList(),
            teeX = teeX,
            teeY = teeY,
            ballRadius = ballRadiusCells?.let { it / gridHeight },
            launchTimestampNs = launch.launchTimestampNs,
            aspect = aspect,
            tapX = tapX,
            tapY = tapY,
            framesProcessed = framesProcessed,
            firstFrames = firstFrames.toList(),
            recentFrames = recentFrames.toList()
        ).also {
            firstFrames.clear()
            recentFrames.clear()
        }
    }

    fun reset() = synchronized(lock) {
        armed = false
        history.clear()
        candidates.clear()
        firstFrames.clear()
        recentFrames.clear()
        launch.reset()
    }

    fun process(frame: LumaFrame) = synchronized(lock) {
        if (!armed) return
        aspect = frame.width.toFloat() / frame.height
        gridHeight = frame.height
        framesProcessed++
        if (keepFrames) {
            if (firstFrames.size < KEPT_FIRST_FRAMES) {
                firstFrames.add(frame)
            } else {
                recentFrames.addLast(frame)
                if (recentFrames.size > KEPT_RECENT_FRAMES) recentFrames.removeFirst()
            }
        }

        if (!teeRefined) {
            refineTee(frame)
            teeRefined = true
            onTeeMeasured(ballRadiusCells?.let { TeeBall(teeX, teeY, it / frame.height) })
        }

        if (history.isNotEmpty() && (history.last().frame.width != frame.width || history.last().frame.height != frame.height)) {
            history.clear()
        }
        // Follow camera shake so the scene can be compared like-for-like between frames.
        history.lastOrNull()?.let { prev ->
            val (sx, sy) = estimateShift(prev.frame, frame)
            cameraX += sx
            cameraY += sy
        }

        val wasLaunched = launch.launchTimestampNs != null
        ballRadiusCells?.let { r ->
            launch.update(frame, teeX * frame.width + cameraX, teeY * frame.height + cameraY, r)
        }
        val isLaunched = launch.launchTimestampNs != null
        if (isLaunched != wasLaunched) onLaunchDetected(isLaunched)

        history.addLast(Tracked(frame, cameraX, cameraY))
        if (history.size > 2 * FAR + 1) history.removeFirst()
        if (history.size == 2 * FAR + 1 && candidates.size < MAX_BUFFERED_CANDIDATES) {
            candidates.addAll(detectMovingBlobs(history))
        }
    }

    /**
     * Snaps the tap to the ball under the finger and measures its size. The tap is rarely exactly on
     * the ball's center, and knowing the ball's size lets us reject blobs that are far too big and
     * watch the right patch of ground for the launch. Works from a ball a few pixels across to one
     * filling a good part of the screen with the phone right behind it.
     */
    private fun refineTee(frame: LumaFrame) {
        val w = frame.width
        val h = frame.height
        val long = max(w, h)
        val cx = (teeX * w).roundToInt().coerceIn(0, w - 1)
        val cy = (teeY * h).roundToInt().coerceIn(0, h - 1)

        // Seed: the brightest pixel near the tap, favoring ones closer to the finger.
        val seedReach = max(3, (long * 0.03f).roundToInt())
        var seedX = cx
        var seedY = cy
        var seedScore = Float.NEGATIVE_INFINITY
        for (y in max(0, cy - seedReach)..min(h - 1, cy + seedReach)) {
            for (x in max(0, cx - seedReach)..min(w - 1, cx + seedReach)) {
                val d = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat())
                if (d > seedReach) continue
                val s = frame.lum(x, y) - d
                if (s > seedScore) { seedScore = s; seedX = x; seedY = y }
            }
        }

        // Background: median over a wide area, which even a ball close to the camera only partly covers.
        val bgReach = max(8, (long * 0.2f).roundToInt())
        val samples = ArrayList<Int>()
        for (y in max(0, seedY - bgReach)..min(h - 1, seedY + bgReach) step 3) {
            for (x in max(0, seedX - bgReach)..min(w - 1, seedX + bgReach) step 3) samples.add(frame.lum(x, y))
        }
        samples.sort()
        val background = samples[samples.size / 2]
        val peak = frame.lum(seedX, seedY)
        if (peak - background < 25) return

        val maxReach = max(10, (long * 0.15f).roundToInt())
        val longRun = max(3, (maxReach * 0.12f).roundToInt())

        // Where to draw the line between ball and ground depends on the scene: a low cut keeps the
        // shaded underside of a ball on dark carpet, while on sunlit grass only a high cut stops the
        // rays running off through bright blades. Try a few and keep the ball that stands out most.
        var best: FloatArray? = null
        var bestContrast = 0f
        for (fraction in CUT_FRACTIONS) {
            val threshold = (background + (peak - background) * fraction).roundToInt()
            val circle = measureBall(frame, seedX, seedY, threshold, maxReach, longRun)
            val contrast = circle?.let { contrastOf(frame, it[0], it[1], max(1f, it[2])) }
            if (circle == null || contrast == null) continue
            if (contrast > bestContrast) { bestContrast = contrast; best = circle }
        }
        val found = best ?: return
        teeX = (found[0] + 0.5f) / w
        teeY = (found[1] + 0.5f) / h
        ballRadiusCells = max(1f, found[2])
    }

    /** The ball's circle as [centerX, centerY, radius] with pixels at or above [threshold] counting as ball. */
    private fun measureBall(frame: LumaFrame, seedX: Int, seedY: Int, threshold: Int, maxReach: Int, longRun: Int): FloatArray? {
        val w = frame.width
        val h = frame.height
        val long = max(w, h)

        // Walk rays out from the seed and note where each crosses into background: both where it first
        // dims (the edge, or the logo stripe) and where it stays dim (the edge, or past a gap into a
        // bright rug). The ball is the circle most of these points agree on; points from the logo, a
        // touching finger or a nearby bright patch don't fit it.
        fun castRays(fromX: Float, fromY: Float): Pair<List<Float>, List<Float>> {
            val edgeX = ArrayList<Float>()
            val edgeY = ArrayList<Float>()
            for (k in 0 until RAYS) {
                val angle = 2 * PI * k / RAYS
                val dx = kotlin.math.cos(angle).toFloat()
                val dy = kotlin.math.sin(angle).toFloat()
                var dark = 0
                var firstEdge = -1
                var r = 1
                while (r <= maxReach) {
                    val x = (fromX + dx * r).roundToInt()
                    val y = (fromY + dy * r).roundToInt()
                    if (x !in 0 until w || y !in 0 until h) break
                    if (frame.lum(x, y) < threshold) {
                        dark++
                        if (dark == 2 && firstEdge < 0) firstEdge = r - 1
                        if (dark >= longRun) {
                            val lastEdge = r - dark + 1
                            edgeX += fromX + dx * lastEdge; edgeY += fromY + dy * lastEdge
                            if (firstEdge in 1 until lastEdge) { edgeX += fromX + dx * firstEdge; edgeY += fromY + dy * firstEdge }
                            break
                        }
                    } else {
                        dark = 0
                    }
                    r++
                }
            }
            return edgeX to edgeY
        }

        // Rays cast from the edge of the ball mostly measure how close the edge is, so start from the
        // middle of the bright patch around the seed (kept small, so a touching rug can't pull it far).
        val (startX, startY) = brightCentroid(frame, seedX, seedY, threshold, max(3, (long * 0.03f).roundToInt()))
        var (xs, ys) = castRays(startX, startY)
        var circle = fitCircle(xs, ys, startX, startY, maxReach.toFloat())
        // Once more from the fitted center, where the rays are even all round.
        circle?.let { c ->
            if (frame.lum(c[0].roundToInt().coerceIn(0, w - 1), c[1].roundToInt().coerceIn(0, h - 1)) >= threshold) {
                val again = castRays(c[0], c[1])
                fitCircle(again.first, again.second, c[0], c[1], maxReach.toFloat())?.let { circle = it }
            }
        }
        return circle
    }

    /** Center of the pixels at or above [threshold] connected to the seed, within [reach] of it. */
    private fun brightCentroid(frame: LumaFrame, seedX: Int, seedY: Int, threshold: Int, reach: Int): Pair<Float, Float> {
        val w = frame.width
        val h = frame.height
        val side = 2 * reach + 1
        val visited = BooleanArray(side * side)
        val stack = IntArray(side * side)
        var sp = 0
        stack[sp++] = reach * side + reach
        visited[reach * side + reach] = true
        var sumX = 0L
        var sumY = 0L
        var n = 0
        while (sp > 0) {
            val local = stack[--sp]
            val x = seedX + local % side - reach
            val y = seedY + local / side - reach
            sumX += x; sumY += y; n++
            for (dy in -1..1) for (dx in -1..1) {
                val lx = local % side + dx
                val ly = local / side + dy
                if (lx !in 0 until side || ly !in 0 until side) continue
                val nx = seedX + lx - reach
                val ny = seedY + ly - reach
                if (nx !in 0 until w || ny !in 0 until h) continue
                val li = ly * side + lx
                if (visited[li] || frame.lum(nx, ny) < threshold) continue
                visited[li] = true
                stack[sp++] = li
            }
        }
        return sumX.toFloat() / n to sumY.toFloat() / n
    }


    /**
     * How much brighter the circle is than the ground around it, or null if not clearly brighter.
     * Sunlit grass is full of bright blades that a circle can be fitted to; a real ball stands out
     * from its surroundings as a whole. The surroundings are judged by a brighter-than-typical level
     * so a nearby shadow can't make a patch of sunlit grass look like a ball.
     */
    private fun contrastOf(frame: LumaFrame, cx: Float, cy: Float, r: Float): Float? {
        val inside = ArrayList<Int>()
        val ring = ArrayList<Int>()
        val outer = max(r * 2.2f, r + 3f)
        val inner = max(r * 1.4f, r + 1.5f)
        val reach = outer.toInt() + 1
        for (y in (cy - reach).toInt()..(cy + reach).toInt()) {
            if (y !in 0 until frame.height) continue
            for (x in (cx - reach).toInt()..(cx + reach).toInt()) {
                if (x !in 0 until frame.width) continue
                val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                if (d <= max(1f, r)) {
                    inside.add(frame.lum(x, y))
                } else if (d in inner..outer) {
                    ring.add(frame.lum(x, y))
                }
            }
        }
        if (inside.isEmpty() || ring.size < 8) return null
        // The brightest half inside: a far-off ball is only a few pixels, partly hidden by the club or
        // shaded underneath, but those pixels are bright; a circle fitted to grass has none that are.
        inside.sortDescending()
        val top = inside.subList(0, max(1, inside.size / 2))
        val insideLevel = top.sum().toFloat() / top.size
        ring.sort()
        val around = ring[ring.size * 65 / 100].toFloat()
        // A ball only a few pixels across can't stand out as much: its edges blur into the grass.
        val tiny = r <= 5f
        val minContrast = if (tiny) MIN_TINY_BALL_CONTRAST else MIN_BALL_CONTRAST
        val minRatio = if (tiny) 1.12f else 1.15f
        if (insideLevel - around < minContrast || insideLevel < around * minRatio) return null
        return insideLevel - around
    }

    /**
     * The circle through the most of the given edge points, refined by least squares on those points,
     * as [centerX, centerY, radius]. It must contain [insideX], [insideY] (the ball the user tapped).
     */
    private fun fitCircle(xs: List<Float>, ys: List<Float>, insideX: Float, insideY: Float, maxRadius: Float): FloatArray? {
        val n = xs.size
        if (n < 5) return null
        fun inliers(cx: Float, cy: Float, r: Float): List<Int> {
            val tol = max(1.5f, 0.12f * r)
            return (0 until n).filter { abs(sqrt((xs[it] - cx) * (xs[it] - cx) + (ys[it] - cy) * (ys[it] - cy)) - r) <= tol }
        }
        var best: List<Int> = emptyList()
        for (i in 0 until n) for (j in i + 1 until n) for (k in j + 1 until n) {
            // Circumcircle of three points.
            val ax = xs[i]; val ay = ys[i]; val bx = xs[j]; val by = ys[j]; val cx = xs[k]; val cy = ys[k]
            val d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
            if (abs(d) < 1e-3f) continue
            val a2 = ax * ax + ay * ay; val b2 = bx * bx + by * by; val c2 = cx * cx + cy * cy
            val ox = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d
            val oy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d
            val r = sqrt((ax - ox) * (ax - ox) + (ay - oy) * (ay - oy))
            if (r < 1f || r > maxRadius) continue
            if ((insideX - ox) * (insideX - ox) + (insideY - oy) * (insideY - oy) > r * r) continue
            val these = inliers(ox, oy, r)
            if (these.size > best.size) best = these
        }
        if (best.size < 5) return null

        // Least squares (Kasa): x² + y² + D·x + E·y + F = 0 over the inliers.
        val m = Array(3) { DoubleArray(4) }
        for (i in best) {
            val x = xs[i].toDouble(); val y = ys[i].toDouble()
            val row = doubleArrayOf(x, y, 1.0)
            val rhs = -(x * x + y * y)
            for (p in 0 until 3) {
                for (q in 0 until 3) m[p][q] += row[p] * row[q]
                m[p][3] += row[p] * rhs
            }
        }
        for (col in 0 until 3) {
            var pivot = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            if (abs(m[col][col]) < 1e-9) return null
            for (r in 0 until 3) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                for (c in col..3) m[r][c] -= f * m[col][c]
            }
        }
        val dd = m[0][3] / m[0][0]; val ee = m[1][3] / m[1][1]; val ff = m[2][3] / m[2][2]
        val ox = -dd / 2; val oy = -ee / 2
        val r2 = ox * ox + oy * oy - ff
        if (r2 <= 0) return null
        return floatArrayOf(ox.toFloat(), oy.toFloat(), sqrt(r2).toFloat())
    }

    /**
     * Candidates for the middle frame of [frames]. A pixel is moving if it differs from both the frame
     * before and the frame after, which places the ball where it is now without a ghost where it was.
     * Neighbors one frame away catch a fast ball; neighbors [FAR] frames away catch a ball that has
     * slowed on screen near its apex and moves less than its own width per frame.
     */
    private fun detectMovingBlobs(frames: ArrayDeque<Tracked>): List<BallCandidate> {
        val mid = frames[FAR]
        val b = mid.frame
        val bd = b.data
        val w = b.width
        val h = b.height
        val size = w * h

        // Each neighbor's data plus how far to look back into it for the same bit of scene.
        class Ref(t: Tracked) {
            val lo = t.lo
            val hi = t.hi
            val dx = t.ox - mid.ox
            val dy = t.oy - mid.oy
            /** How far b falls outside the neighbor's ±1 pixel range there, or -1 where the neighbor didn't see it. */
            fun diff(x: Int, y: Int, lb: Int): Int {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) return -1
                val j = ny * w + nx
                val l = lo[j].toInt() and 0xFF
                val u = hi[j].toInt() and 0xFF
                return if (lb > u) lb - u else if (lb < l) l - lb else 0
            }
        }
        val nearA = Ref(frames[FAR - 1])
        val nearC = Ref(frames[FAR + 1])
        val farA = Ref(frames[0])
        val farC = Ref(frames[2 * FAR])

        val mask = BooleanArray(size)
        val strength = IntArray(size)
        var moving = 0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val lb = bd[i].toInt() and 0xFF
            val near = min(nearA.diff(x, y, lb), nearC.diff(x, y, lb))
            val far = min(farA.diff(x, y, lb), farC.diff(x, y, lb))
            val d = max(near, far)
            if (d > DIFF_THRESHOLD) {
                mask[i] = true
                strength[i] = d
                moving++
            }
        }
        if (moving == 0) return emptyList()
        // Most of the frame changed: camera shake or an exposure jump, nothing useful to find.
        if (moving > size / 4) return emptyList()

        // Summed-area table of the mask so each blob's surroundings can be checked in O(1).
        val sat = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                if (mask[y * w + x]) rowSum++
                sat[(y + 1) * (w + 1) + (x + 1)] = sat[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        fun maskCount(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val ax = max(0, x0); val ay = max(0, y0)
            val bx = min(w - 1, x1); val by = min(h - 1, y1)
            if (ax > bx || ay > by) return 0
            return sat[(by + 1) * (w + 1) + (bx + 1)] - sat[ay * (w + 1) + (bx + 1)] -
                sat[(by + 1) * (w + 1) + ax] + sat[ay * (w + 1) + ax]
        }

        val ballDiameter = (ballRadiusCells ?: (max(w, h) * 0.0075f)) * 2f
        val maxDim = max(8f, max(4f * ballDiameter, max(w, h) * 0.04f))
        val maxThickness = max(2f, 1.5f * ballDiameter)

        val visited = BooleanArray(size)
        val stack = IntArray(moving)
        val found = ArrayList<BallCandidate>()
        for (start in 0 until size) {
            if (!mask[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var area = 0
            var minX = w; var maxX = -1; var minY = h; var maxY = -1
            var sumW = 0f; var sumX = 0f; var sumY = 0f
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                val weight = strength[idx].toFloat()
                area++
                sumW += weight
                sumX += weight * x
                sumY += weight * y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                    val nIdx = ny * w + nx
                    if (!mask[nIdx] || visited[nIdx]) continue
                    visited[nIdx] = true
                    stack[sp++] = nIdx
                }
            }

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val dim = max(bw, bh).toFloat()
            if (dim > maxDim) continue
            if (area > dim * maxThickness) continue
            // Long, one-pixel-thin lines are edges of limbs or the club shaft, never a ball.
            if (dim >= 4 && area / dim < 1.5f) continue

            // A ball in flight is alone in the sky; fragments of a moving body are surrounded by more motion.
            val margin = max(4, dim.roundToInt())
            val ringCells = (min(w - 1, maxX + margin) - max(0, minX - margin) + 1) *
                (min(h - 1, maxY + margin) - max(0, minY - margin) + 1) - bw * bh
            val ringMoving = maskCount(minX - margin, minY - margin, maxX + margin, maxY + margin) -
                maskCount(minX, minY, maxX, maxY)
            val isolation = if (ringCells > 0) 1f - ringMoving.toFloat() / ringCells else 1f
            if (isolation < 0.5f || ringMoving > 2 * area + 4) continue

            val contrast = sumW / area
            // Reported where the scene was when recording started, so camera shake doesn't bend the path.
            found.add(
                BallCandidate(
                    x = (sumX / sumW + 0.5f - mid.ox) / w,
                    y = (sumY / sumW + 0.5f - mid.oy) / h,
                    timestampNs = b.timestampNs,
                    score = contrast * isolation * isolation
                )
            )
        }
        found.sortByDescending { it.score }
        return if (found.size > maxCandidatesPerFrame) found.subList(0, maxCandidatesPerFrame) else found
    }

    /**
     * How far the scene moved from [prev] to [cur] (content at prev(x, y) is at cur(x + sx, y + sy)),
     * found by block matching: a coarse search on 4× smaller images, then a fine one around it.
     */
    private fun estimateShift(prev: LumaFrame, cur: LumaFrame): Pair<Int, Int> {
        val w = prev.width
        val h = prev.height
        val f = 4
        val cw = w / f
        val ch = h / f
        fun shrink(fr: LumaFrame) = IntArray(cw * ch).also { out ->
            for (y in 0 until ch * f) for (x in 0 until cw * f) {
                out[(y / f) * cw + x / f] += fr.data[y * w + x].toInt() and 0xFF
            }
        }
        val p = shrink(prev)
        val c = shrink(cur)

        fun cost(a: IntArray, b: IntArray, aw: Int, ah: Int, sx: Int, sy: Int, step: Int): Double {
            var sum = 0L
            var n = 0
            var y = max(0, -sy)
            while (y < min(ah, ah - sy)) {
                var x = max(0, -sx)
                while (x < min(aw, aw - sx)) {
                    sum += abs(b[(y + sy) * aw + x + sx] - a[y * aw + x])
                    n++
                    x += step
                }
                y += step
            }
            // Slight preference for no movement, so a featureless scene doesn't wander.
            return if (n < aw * ah / (4 * step * step)) Double.MAX_VALUE else sum.toDouble() / n * (1 + 0.002 * (abs(sx) + abs(sy)))
        }

        var bestX = 0
        var bestY = 0
        var best = Double.MAX_VALUE
        for (sy in -COARSE_SEARCH..COARSE_SEARCH) for (sx in -COARSE_SEARCH..COARSE_SEARCH) {
            val cst = cost(p, c, cw, ch, sx, sy, 2)
            if (cst < best) { best = cst; bestX = sx; bestY = sy }
        }

        // Refine to the pixel. A pixel off is fine: differencing tolerates ±1 pixel anyway.
        val pf = IntArray(w * h) { prev.data[it].toInt() and 0xFF }
        val cf = IntArray(w * h) { cur.data[it].toInt() and 0xFF }
        var fineX = bestX * f
        var fineY = bestY * f
        best = Double.MAX_VALUE
        for (sy in bestY * f - 2..bestY * f + 2) for (sx in bestX * f - 2..bestX * f + 2) {
            val cst = cost(pf, cf, w, h, sx, sy, 3)
            if (cst < best) { best = cst; fineX = sx; fineY = sy }
        }
        return fineX to fineY
    }

    companion object {
        private const val COARSE_SEARCH = 5
        private const val RAYS = 24
        private const val MIN_BALL_CONTRAST = 30
        private const val MIN_TINY_BALL_CONTRAST = 25
        private val CUT_FRACTIONS = floatArrayOf(0.35f, 0.5f, 0.65f)
        private const val DIFF_THRESHOLD = 18
        private const val FAR = 4
        private const val KEPT_FIRST_FRAMES = 10
        private const val KEPT_RECENT_FRAMES = 150
        private const val MAX_BUFFERED_CANDIDATES = 60_000
    }
}

/**
 * Watches the ball on the tee and reports when it leaves.
 *
 * Presence is the ratio of the ball's brightness to its surroundings' median, which holds steady when
 * a shadow or exposure change darkens both. The ball counts as gone after several consecutive frames
 * without it, so a club passing over the ball during a waggle isn't mistaken for a launch.
 */
internal class TeeLaunchDetector {
    var launchTimestampNs: Long? = null
        private set

    private var referenceRatio = 0f
    private var referenceFrames = 0
    private var absentRun = 0
    private var absentStartNs = 0L
    private var presentRun = 0

    fun reset() {
        launchTimestampNs = null
        referenceRatio = 0f
        referenceFrames = 0
        absentRun = 0
        presentRun = 0
    }

    fun update(frame: LumaFrame, cx: Float, cy: Float, radius: Float) {
        val ratio = presenceRatio(frame, cx, cy, radius) ?: return

        if (referenceFrames < REFERENCE_FRAMES) {
            referenceRatio = (referenceRatio * referenceFrames + ratio) / (referenceFrames + 1)
            referenceFrames++
            return
        }
        // Too little contrast between ball and ground to tell when it's gone.
        if (referenceRatio < 1.15f) return

        val present = ratio - 1f >= 0.4f * (referenceRatio - 1f)
        if (present) {
            absentRun = 0
            presentRun++
            // The ball is back, so the earlier "launch" was something passing in front of it.
            if (launchTimestampNs != null && presentRun >= REAPPEAR_FRAMES) launchTimestampNs = null
        } else {
            presentRun = 0
            if (absentRun == 0) absentStartNs = frame.timestampNs
            absentRun++
            if (absentRun == ABSENT_FRAMES) launchTimestampNs = absentStartNs
        }
    }

    private fun presenceRatio(frame: LumaFrame, cx: Float, cy: Float, radius: Float): Float? {
        val inner = max(1f, radius * 0.7f)
        val ringIn = radius * 2f + 1f
        val ringOut = radius * 3f + 2f
        var innerSum = 0
        var innerCount = 0
        val ring = ArrayList<Int>()
        val reach = ringOut.toInt() + 1
        val px = cx.toInt()
        val py = cy.toInt()
        for (y in py - reach..py + reach) {
            if (y < 0 || y >= frame.height) continue
            for (x in px - reach..px + reach) {
                if (x < 0 || x >= frame.width) continue
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d <= inner) {
                    innerSum += frame.lum(x, y)
                    innerCount++
                } else if (d in ringIn..ringOut) {
                    ring.add(frame.lum(x, y))
                }
            }
        }
        if (innerCount == 0 || ring.size < 4) return null
        ring.sort()
        val ringMedian = ring[ring.size / 2]
        return (innerSum.toFloat() / innerCount + 1f) / (ringMedian + 1f)
    }

    companion object {
        private const val REFERENCE_FRAMES = 3
        private const val ABSENT_FRAMES = 3
        private const val REAPPEAR_FRAMES = 5
    }
}
