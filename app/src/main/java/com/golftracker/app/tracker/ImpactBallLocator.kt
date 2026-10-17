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
    class Found(val ball: TeeBall, val referenceIndex: Int, val vanishedNs: Long, val contrast: Float)

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
    private const val PEAK_SMOOTHING = 2
    private const val PEAK_SPACING = 6
    /** At least this much of the ball's middle has to have gone dark. */
    private const val MIN_GONE = 0.6f
    private const val EDGE = 0.04f

    /** [frames] in time order; [impactNs] on the same clock. */
    fun locate(frames: List<LumaFrame>, impactNs: Long): Found? {
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
        val maxRadius = max(w, h) * 0.06f
        var best: Found? = null
        var bestScore = 0f
        for ((px, py) in peaks(drop, w, h)) {
            val circle = BallMeasure.find(beforeFrame, (px + 0.5f) / w, (py + 0.5f) / h)
            if (circle == null || circle.radiusCells > maxRadius) continue
            // A ball on the tee is never right at the edge of the picture; things passing out of it are.
            if (circle.x !in EDGE..1 - EDGE || circle.y !in EDGE..1 - EDGE) continue
            // The whole ball must have gone, not just the patch beside it where a club or hand came in.
            val (gone, meanDrop) = vanished(mask, drop, w, h, circle.x * w - 0.5f, circle.y * h - 0.5f, circle.radiusCells)
            if (gone < MIN_GONE) continue
            val score = circle.contrast * meanDrop * gone
            if (score > bestScore) {
                bestScore = score
                val ball = TeeBall(circle.x, circle.y, circle.radiusCells / h)
                best = Found(ball, referenceIndex, vanishTime(frames, ref, ball, before.last(), after.last(), medBefore, medAfter), circle.contrast)
            }
        }
        return best
    }

    /** How much of the circle went dark (fraction of its middle in [mask]) and by how much on average. */
    private fun vanished(mask: BooleanArray, drop: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float): Pair<Float, Float> {
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
            if (mask[i]) { gone++; sum += drop[i] }
        }
        return if (n == 0 || gone == 0) 0f to 0f else gone.toFloat() / n to sum.toFloat() / gone
    }

    /**
     * Where the brightness dropped most, as local peaks of the drop smoothed over a few pixels, strongest
     * first. A ball's patch of drop often runs into the club's or a hand's, so these are looked for
     * within the patches rather than taking each patch as one thing.
     */
    private fun peaks(drop: IntArray, w: Int, h: Int): List<Pair<Int, Int>> {
        // Summed-area table, for the mean drop in a box around each pixel.
        val sat = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += drop[y * w + x]
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
            }
        }
        val r = PEAK_SMOOTHING
        fun boxMean(x: Int, y: Int): Long {
            val x0 = max(0, x - r); val y0 = max(0, y - r)
            val x1 = min(w - 1, x + r); val y1 = min(h - 1, y + r)
            val s = sat[(y1 + 1) * (w + 1) + x1 + 1] - sat[y0 * (w + 1) + x1 + 1] - sat[(y1 + 1) * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
            return s / ((x1 - x0 + 1) * (y1 - y0 + 1))
        }
        val smooth = LongArray(w * h)
        for (y in 0 until h) for (x in 0 until w) if (drop[y * w + x] > 0) smooth[y * w + x] = boxMean(x, y)
        val found = ArrayList<Triple<Int, Int, Long>>()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val v = smooth[y * w + x]
            if (v < MIN_DROP) continue
            var isPeak = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && smooth[(y + dy) * w + x + dx] > v) { isPeak = false; break@loop }
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

    /**
     * The first frame after [lastBefore] where the ball's spot looks more like after than before. The
     * strike can fall among the "after" frames when the time it was looked for is a guess.
     */
    private fun vanishTime(
        frames: List<LumaFrame>, ref: LumaFrame, ball: TeeBall,
        lastBefore: LumaFrame, lastAfter: LumaFrame, medBefore: IntArray, medAfter: IntArray
    ): Long {
        val w = ref.width
        val h = ref.height
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
        for (f in frames) {
            if (f.timestampNs <= lastBefore.timestampNs || f.timestampNs > lastAfter.timestampNs) continue
            val (sx, sy) = FrameShift.estimate(ref, f)
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
