package com.golftracker.app.tracker

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the ball that was just hit, given roughly when it was hit.
 *
 * Before impact the ball sits still on the tee; a moment after, it's gone. Taking the per-pixel median
 * of several frames before the impact keeps the still ball and the ground while the club and body
 * swinging through drop out, and the median of a few frames after it shows the same ground without the
 * ball. Where the first is much brighter than the second, at a spot that held still before the swing,
 * something bright left. A ball is measured at each such spot in the "before" picture; the one that
 * stands out most from the ground, and vanished whole, wins.
 */
internal object ImpactBallLocator {

    /**
     * The ball, in the coordinates of frame [referenceIndex] (where a capture should start feeding
     * frames), and when it was seen to go.
     */
    class Found(
        val ball: TeeBall, val referenceIndex: Int, val vanishedNs: Long, val contrast: Float,
        /**
         * Where the picture changed between before and after the swing (255) or not (0): the golfer, now in
         * another pose. A ball in flight crosses what stayed the same.
         */
        val changed: LumaFrame? = null
    )

    private const val BEFORE_FROM_S = 0.9
    private const val BEFORE_TO_S = 0.2
    private const val AFTER_FROM_S = 0.15
    private const val AFTER_TO_S = 0.5
    /** How long before the impact a capture starts, so the ball is seen sitting still first. */
    const val FEED_BEFORE_S = 0.85
    /** How long after the impact frames must have arrived before [locate] can work. */
    const val NEEDS_AFTER_S = AFTER_TO_S
    private const val MAX_BEFORE_FRAMES = 7
    private const val MAX_AFTER_FRAMES = 5
    private const val MIN_DROP = 25
    private const val STABLE_TOLERANCE = 20
    private const val CANDIDATES_TO_MEASURE = 24
    private const val PEAK_SPACING = 6
    /** At least this much of the ball's middle has to have gone dark. */
    private const val MIN_GONE = 0.6f
    private const val EDGE = 0.04f
    private const val MIN_UNCHANGED_AROUND = 0.45f
    private const val MIN_STEADY = 0.5f
    /** The rings checked for other change around a candidate, in pixels, and how much of one must be still. */
    private val ISOLATION_RINGS = listOf(3 to 8, 8 to 16, 16 to 28)
    private const val MIN_ISOLATION = 0.6f
    private const val MIN_TINY_CONTRAST = 25f
    /** A struck ball goes within this long of the sound of the strike (the club may cover it just before). */
    private const val MAX_VANISH_BEFORE_STRIKE_NS = 80_000_000L
    private const val MAX_VANISH_AFTER_STRIKE_NS = 150_000_000L
    /** Candidates scoring at least this share of the best one are taken seriously when choosing by timing. */
    private const val STRONG = 0.5f
    /** Largest ball radius, as a fraction of the frame's long side. */
    private const val MAX_RADIUS = 0.035f
    private const val CHANGED_GROWTH = 4

    /**
     * [frames] in time order; [impactNs] on the same clock. With [heardNs], when the strike was heard,
     * the ball must have gone right then: something that left earlier or later was something else.
     */
    fun locate(frames: List<LumaFrame>, impactNs: Long, heardNs: Long? = null): Found? {
        if (frames.isEmpty()) return null
        fun at(s: Double) = impactNs + (s * 1e9).toLong()
        val before = pick(frames.filter { it.timestampNs in at(-BEFORE_FROM_S)..at(-BEFORE_TO_S) }, MAX_BEFORE_FRAMES)
        val after = pick(frames.filter { it.timestampNs in at(AFTER_FROM_S)..at(AFTER_TO_S) }, MAX_AFTER_FRAMES)
        if (before.size < 3 || after.size < 2) return null
        val referenceIndex = frames.indexOfFirst { it.timestampNs >= at(-FEED_BEFORE_S) }
        val ref = frames[referenceIndex]
        val w = ref.width
        val h = ref.height
        if ((before + after).any { it.width != w || it.height != h }) return null

        val medBefore = IntArray(w * h)
        val stable = BooleanArray(w * h)
        alignedMedian(ref, before, medBefore, stable)
        val medAfter = IntArray(w * h)
        alignedMedian(ref, after, medAfter, null)

        // Pixels that were steady and bright before, and much darker after.
        val drop = IntArray(w * h)
        val mask = BooleanArray(w * h)
        var count = 0
        for (i in 0 until w * h) {
            val d = medBefore[i] - medAfter[i]
            if (stable[i] && d >= max(MIN_DROP, medBefore[i] / 5)) {
                mask[i] = true
                drop[i] = d
                count++
            }
        }
        if (count == 0) return null

        val beforeFrame = LumaFrame(w, h, ByteArray(w * h) { medBefore[it].toByte() }, ref.timestampNs)
        // A ball filmed from a few metres away is small; a big bright circle is something else coming or going.
        val maxRadius = max(w, h) * MAX_RADIUS
        // The frames where the ball could have gone, lined up with the reference once for all candidates.
        val transition by lazy {
            frames.filter { it.timestampNs > before.last().timestampNs && it.timestampNs <= after.last().timestampNs }
                .map { f -> FrameShift.estimate(ref, f).let { (sx, sy) -> Triple(f, sx, sy) } }
        }
        class Candidate(val found: Found, val score: Float)
        val candidates = ArrayList<Candidate>()
        for ((px, py) in peaks(drop, medBefore, medAfter, w, h)) {
            val circle = BallMeasure.find(beforeFrame, (px + 0.5f) / w, (py + 0.5f) / h) ?: tinyBall(medBefore, w, h, px, py)
            if (circle == null || circle.radiusCells > maxRadius) continue
            // A ball on the tee is never right at the edge of the picture; things passing out of it are.
            if (circle.x !in EDGE..1 - EDGE || circle.y !in EDGE..1 - EDGE) continue
            // The whole ball must have gone, not just the patch beside it where a club or hand came in.
            val cx = circle.x * w - 0.5f
            val cy = circle.y * h - 0.5f
            val (gone, meanDrop) = vanished(medBefore, medAfter, w, h, cx, cy, circle.radiusCells)
            if (gone < MIN_GONE) continue
            // A ball on the tee sat still before the swing; a glove or sleeve that went was moving already.
            val steady = steadiness(stable, w, h, cx, cy, circle.radiusCells)
            if (steady < MIN_STEADY) continue
            // ...and the ground around it stayed as it was. Where a body or club came over a bright patch,
            // its surroundings were covered too.
            if (unchangedAround(medBefore, medAfter, w, h, cx, cy, circle.radiusCells) < MIN_UNCHANGED_AROUND) continue
            val score = circle.contrast * meanDrop * gone * steady
            val ball = TeeBall(circle.x, circle.y, circle.radiusCells / h)
            if (candidates.any { abs(it.found.ball.x - ball.x) * w < 2 && abs(it.found.ball.y - ball.y) * h < 2 }) continue
            val vanishedNs = vanishTime(transition, w, h, ball, before.last(), after.last(), medBefore, medAfter)
            if (heardNs != null && vanishedNs - heardNs !in -MAX_VANISH_BEFORE_STRIKE_NS..MAX_VANISH_AFTER_STRIKE_NS) continue
            candidates += Candidate(Found(ball, referenceIndex, vanishedNs, circle.contrast), score)
        }
        val changedMask = changedArea(medBefore, medAfter, w, h, ref.timestampNs)
        // The ball leaves the instant it's struck. A shoe that turns or a heel that lifts in the follow-through
        // changes a little later, so of the strong candidates, only those that went first are considered.
        val top = candidates.maxOfOrNull { it.score } ?: return null
        val strong = candidates.filter { it.score >= STRONG * top }
        val first = strong.minOf { it.found.vanishedNs }
        val frameNs = (after.last().timestampNs - before.last().timestampNs) / max(1, transition.size)
        val chosen = strong.filter { it.found.vanishedNs <= first + frameNs }.maxBy { it.score }.found
        return Found(chosen.ball, chosen.referenceIndex, chosen.vanishedNs, chosen.contrast, changedMask)
    }

    /** What changed between before and after, grown by a few pixels to cover a club or arm a little out of place. */
    private fun changedArea(before: IntArray, after: IntArray, w: Int, h: Int, timestampNs: Long): LumaFrame {
        val changed = SummedArea(IntArray(w * h) { if (abs(before[it] - after[it]) >= MIN_DROP) 1 else 0 }, w, h)
        return LumaFrame(w, h, ByteArray(w * h) { i -> if (changed.mean(i % w, i / w, CHANGED_GROWTH) > 0.1f) -1 else 0 }, timestampNs)
    }

    /**
     * A ball too small to fit a circle to (a pixel or so across, filmed from far away): a pixel clearly
     * brighter than nearly everything around it, taken as a ball of radius one.
     */
    private fun tinyBall(before: IntArray, w: Int, h: Int, x: Int, y: Int): BallMeasure.Circle? {
        // Room for the ring, even after moving a pixel to the brightest spot.
        if (x < 4 || y < 4 || x > w - 5 || y > h - 5) return null
        // The brightest pixel right there, in case the peak is a pixel off.
        var bx = x
        var by = y
        for (dy in -1..1) for (dx in -1..1) if (before[(y + dy) * w + x + dx] > before[by * w + bx]) { bx = x + dx; by = y + dy }
        // Compared with the brighter part of what's around it, so the edge of a dark shoe or shadow
        // beside a patch of grass doesn't make the grass look like a ball.
        val ring = ArrayList<Int>(40)
        for (dy in -3..3) for (dx in -3..3) {
            if (max(abs(dx), abs(dy)) < 2) continue
            ring += before[(by + dy) * w + bx + dx]
        }
        ring.sort()
        val contrast = (before[by * w + bx] - ring[ring.size * 3 / 4]).toFloat()
        if (contrast < MIN_TINY_CONTRAST) return null
        return BallMeasure.Circle((bx + 0.5f) / w, (by + 0.5f) / h, 1f, contrast)
    }

    /** The fraction of the circle (and a pixel round it, for a ball lined up a pixel off) that held still before. */
    private fun steadiness(stable: BooleanArray, w: Int, h: Int, cx: Float, cy: Float, r: Float): Float {
        val reach = r + 1f
        var n = 0
        var still = 0
        for (y in (cy - reach).toInt()..(cy + reach).toInt() + 1) for (x in (cx - reach).toInt()..(cx + reach).toInt() + 1) {
            if (x !in 0 until w || y !in 0 until h) continue
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > reach * reach) continue
            n++
            if (stable[y * w + x]) still++
        }
        return if (n == 0) 0f else still.toFloat() / n
    }

    /** The fraction of a ring around the circle that looks the same after as before. */
    private fun unchangedAround(before: IntArray, after: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float): Float {
        val inner = r * 1.6f + 1f
        val outer = r * 2.6f + 3f
        var n = 0
        var same = 0
        for (y in (cy - outer).toInt()..(cy + outer).toInt() + 1) for (x in (cx - outer).toInt()..(cx + outer).toInt() + 1) {
            if (x !in 0 until w || y !in 0 until h) continue
            val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (d2 < inner * inner || d2 > outer * outer) continue
            n++
            if (abs(before[y * w + x] - after[y * w + x]) < MIN_DROP) same++
        }
        return if (n == 0) 0f else same.toFloat() / n
    }

    /**
     * How much of the circle went dark (fraction of its middle) and by how much on average. Steadiness
     * isn't asked for here: a ball a couple of pixels across, lined up a pixel off between frames,
     * wouldn't look steady, and it's already known to be a ball.
     */
    private fun vanished(before: IntArray, after: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float): Pair<Float, Float> {
        val inner = max(0.8f, r * 0.7f)
        var n = 0
        var gone = 0
        var sum = 0L
        for (y in (cy - inner).toInt()..(cy + inner).toInt() + 1) for (x in (cx - inner).toInt()..(cx + inner).toInt() + 1) {
            if (x !in 0 until w || y !in 0 until h) continue
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy > inner * inner) continue
            n++
            val i = y * w + x
            val d = before[i] - after[i]
            if (d >= max(MIN_DROP, before[i] / 5)) { gone++; sum += d }
        }
        return if (n == 0 || gone == 0) 0f to 0f else gone.toFloat() / n to sum.toFloat() / gone
    }

    /**
     * Spots to measure for a ball, most promising first: where the brightness dropped and, in the
     * picture from before, there was a spot brighter than its surroundings. Looking for both keeps a
     * ball a couple of pixels across ahead of the many small drops that textured grass and a slightly
     * shaking camera leave everywhere. A ball's patch of drop also often runs into the club's or a
     * hand's, so spots are looked for within patches rather than taking each patch as one thing.
     */
    private fun peaks(drop: IntArray, before: IntArray, after: IntArray, w: Int, h: Int): List<Pair<Int, Int>> {
        val dropSum = SummedArea(drop, w, h)
        val beforeSum = SummedArea(before, w, h)
        // Everything that changed either way, to tell a lone ball from part of a moving golfer.
        val changedSum = SummedArea(IntArray(w * h) { if (abs(before[it] - after[it]) >= MIN_DROP) 1 else 0 }, w, h)
        val score = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (drop[y * w + x] == 0) continue
            // Brighter than its surroundings, at the scale of a far ball and of a near one.
            val small = beforeSum.mean(x, y, 1) - beforeSum.mean(x, y, 4)
            val large = beforeSum.mean(x, y, 4) - beforeSum.mean(x, y, 12)
            val prominence = max(small, large)
            if (prominence <= 0f) continue
            // A ball leaves a lone spot of change in grass that otherwise stayed the same; a shoe or a
            // hand is part of a whole body that moved. Looked at in rings of a few sizes, since a ball
            // close to the camera fills the small one itself.
            val isolation = ISOLATION_RINGS.maxOf { (inner, outer) -> 1f - changedSum.ringMean(x, y, inner, outer) }
            if (isolation < MIN_ISOLATION) continue
            score[y * w + x] = dropSum.mean(x, y, 1) * prominence * isolation * isolation
        }
        val found = ArrayList<Triple<Int, Int, Float>>()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val v = score[y * w + x]
            if (v <= 0f) continue
            var isPeak = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && score[(y + dy) * w + x + dx] > v) { isPeak = false; break@loop }
            }
            if (isPeak) found += Triple(x, y, v)
        }
        found.sortByDescending { it.third }
        // Keep peaks apart, so one big patch doesn't take every place in the list.
        val kept = ArrayList<Pair<Int, Int>>()
        for ((x, y, _) in found) {
            if (kept.any { (kx, ky) -> (kx - x) * (kx - x) + (ky - y) * (ky - y) < PEAK_SPACING * PEAK_SPACING }) continue
            kept += x to y
            if (kept.size == CANDIDATES_TO_MEASURE) break
        }
        return kept
    }

    /** Means over square boxes of an image in constant time. */
    private class SummedArea(values: IntArray, private val w: Int, private val h: Int) {
        private val sat = LongArray((w + 1) * (h + 1))
        init {
            for (y in 0 until h) {
                var row = 0L
                for (x in 0 until w) {
                    row += values[y * w + x]
                    sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
                }
            }
        }

        /** Mean of the box reaching [r] pixels each way from (x, y), clipped to the image. */
        fun mean(x: Int, y: Int, r: Int): Float {
            val (sum, n) = box(x, y, r)
            return sum.toFloat() / n
        }

        /** Mean over the square ring between [inner] and [outer] pixels from (x, y). */
        fun ringMean(x: Int, y: Int, inner: Int, outer: Int): Float {
            val (outSum, outN) = box(x, y, outer)
            val (inSum, inN) = box(x, y, inner)
            return if (outN == inN) 0f else (outSum - inSum).toFloat() / (outN - inN)
        }

        private fun box(x: Int, y: Int, r: Int): Pair<Long, Int> {
            val x0 = max(0, x - r); val y0 = max(0, y - r)
            val x1 = min(w - 1, x + r); val y1 = min(h - 1, y + r)
            val s = sat[(y1 + 1) * (w + 1) + x1 + 1] - sat[y0 * (w + 1) + x1 + 1] - sat[(y1 + 1) * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
            return s to (x1 - x0 + 1) * (y1 - y0 + 1)
        }
    }

    /**
     * The first frame after [lastBefore] where the ball's spot looks more like after than before. The
     * strike can fall among the "after" frames when the time it was looked for is a guess.
     */
    private fun vanishTime(
        transition: List<Triple<LumaFrame, Int, Int>>, w: Int, h: Int, ball: TeeBall,
        lastBefore: LumaFrame, lastAfter: LumaFrame, medBefore: IntArray, medAfter: IntArray
    ): Long {
        val cx = (ball.x * w).toInt().coerceIn(0, w - 1)
        val cy = (ball.y * h).toInt().coerceIn(0, h - 1)
        val r = max(0, (ball.radius * h * 0.6f).toInt())
        fun spot(values: (Int) -> Int): Float {
            var sum = 0
            var n = 0
            for (y in max(0, cy - r)..min(h - 1, cy + r)) for (x in max(0, cx - r)..min(w - 1, cx + r)) {
                sum += values(y * w + x); n++
            }
            return sum.toFloat() / n
        }
        val present = spot { medBefore[it] }
        val gone = spot { medAfter[it] }
        val middle = (present + gone) / 2
        for ((f, sx, sy) in transition) {
            val level = spot { i ->
                val x = (i % w + sx).coerceIn(0, w - 1)
                val y = (i / w + sy).coerceIn(0, h - 1)
                f.lum(x, y)
            }
            if (level < middle) return f.timestampNs
        }
        return (lastBefore.timestampNs + lastAfter.timestampNs) / 2
    }

    /** Per-pixel median of [frames] lined up with [ref]; [stable] marks pixels most frames agree on. */
    private fun alignedMedian(ref: LumaFrame, frames: List<LumaFrame>, out: IntArray, stable: BooleanArray?) {
        val w = ref.width
        val h = ref.height
        val shifts = frames.map { if (it === ref) 0 to 0 else FrameShift.estimate(ref, it) }
        val k = frames.size
        val values = IntArray(k)
        val sorted = IntArray(k)
        for (y in 0 until h) for (x in 0 until w) {
            for (j in 0 until k) {
                val (sx, sy) = shifts[j]
                values[j] = frames[j].lum((x + sx).coerceIn(0, w - 1), (y + sy).coerceIn(0, h - 1))
            }
            values.copyInto(sorted)
            sorted.sort()
            val median = sorted[k / 2]
            out[y * w + x] = median
            if (stable != null) {
                var agree = 0
                for (v in values) if (abs(v - median) <= STABLE_TOLERANCE) agree++
                stable[y * w + x] = agree * 10 >= k * 7
            }
        }
    }

    /** Up to [n] frames spread evenly through [frames]. */
    private fun pick(frames: List<LumaFrame>, n: Int): List<LumaFrame> {
        if (frames.size <= n) return frames
        return List(n) { frames[(it * (frames.size - 1) / (n - 1.0)).roundToInt()] }
    }
}
