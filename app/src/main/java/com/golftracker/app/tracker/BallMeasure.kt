package com.golftracker.app.tracker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Measures a golf ball sitting still in a frame: where its center is and how big it is. */
internal object BallMeasure {

    /** Center in screen fractions, radius in grid cells, and how much brighter it is than the ground. */
    class Circle(val x: Float, val y: Float, val radiusCells: Float, val contrast: Float)

    /**
     * Finds the ball nearest screen-normalized ([x], [y]) and measures its size, or null if nothing
     * there looks like a ball. The spot is rarely exactly on the ball's center. Works from a ball a few
     * pixels across to one filling a good part of the screen with the phone right behind it.
     */
    fun find(frame: LumaFrame, x: Float, y: Float): Circle? {
        val w = frame.width
        val h = frame.height
        val long = max(w, h)
        val cx = (x * w).roundToInt().coerceIn(0, w - 1)
        val cy = (y * h).roundToInt().coerceIn(0, h - 1)

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
        val histogram = IntArray(256)
        var samples = 0
        for (y in max(0, seedY - bgReach)..min(h - 1, seedY + bgReach) step 3) {
            for (x in max(0, seedX - bgReach)..min(w - 1, seedX + bgReach) step 3) { histogram[frame.lum(x, y)]++; samples++ }
        }
        var background = 0
        var seen = histogram[0]
        while (seen <= samples / 2) seen += histogram[++background]
        val peak = frame.lum(seedX, seedY)
        if (peak - background < 25) return null

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
        val found = best ?: return null
        return Circle((found[0] + 0.5f) / w, (found[1] + 0.5f) / h, max(1f, found[2]), bestContrast)
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
    private fun fitCircle(xList: List<Float>, yList: List<Float>, insideX: Float, insideY: Float, maxRadius: Float): FloatArray? {
        val n = xList.size
        if (n < 5) return null
        // Plain arrays: this runs for every triple of points, thousands of times per ball.
        val xs = xList.toFloatArray()
        val ys = yList.toFloatArray()
        fun isInlier(i: Int, cx: Float, cy: Float, r: Float, tol: Float): Boolean {
            val dx = xs[i] - cx
            val dy = ys[i] - cy
            return abs(sqrt(dx * dx + dy * dy) - r) <= tol
        }
        var bestCount = 0
        var bestX = 0f
        var bestY = 0f
        var bestR = 0f
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
            // Inliers are within tol of the circle: compared squared, and given up on as soon as this
            // circle can no longer beat the best so far.
            val tol = max(1.5f, 0.12f * r)
            val lo = if (r > tol) (r - tol) * (r - tol) else 0f
            val hi = (r + tol) * (r + tol)
            val allowedMisses = n - bestCount
            var count = 0
            var misses = 0
            for (q in 0 until n) {
                val dx = xs[q] - ox
                val dy = ys[q] - oy
                val d2 = dx * dx + dy * dy
                if (d2 in lo..hi) count++ else if (++misses >= allowedMisses) break
            }
            if (count > bestCount) { bestCount = count; bestX = ox; bestY = oy; bestR = r }
        }
        if (bestCount < 5) return null
        val bestTol = max(1.5f, 0.12f * bestR)
        val best = (0 until n).filter { isInlier(it, bestX, bestY, bestR, bestTol) }

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

    private const val RAYS = 24
    private const val MIN_BALL_CONTRAST = 30
    private const val MIN_TINY_BALL_CONTRAST = 25
    private val CUT_FRACTIONS = floatArrayOf(0.35f, 0.5f, 0.65f)
}
