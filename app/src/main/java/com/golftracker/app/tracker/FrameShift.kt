package com.golftracker.app.tracker

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Follows camera shake between frames, so a hand-held or nudged phone's frames can be compared like-for-like. */
internal object FrameShift {

    /**
     * How far the scene moved from [prev] to [cur] (content at prev(x, y) is at cur(x + sx, y + sy)),
     * found by block matching: a coarse search on 4× smaller images, then a fine one around it.
     */
    fun estimate(prev: LumaFrame, cur: LumaFrame): Pair<Int, Int> {
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

        // Each pixel's mismatch is capped, so a golfer swinging through the picture can't outweigh the
        // whole background and pass for the camera moving.
        fun cost(a: IntArray, b: IntArray, aw: Int, ah: Int, sx: Int, sy: Int, step: Int, cap: Int): Double {
            var sum = 0L
            var n = 0
            var y = max(0, -sy)
            while (y < min(ah, ah - sy)) {
                var x = max(0, -sx)
                while (x < min(aw, aw - sx)) {
                    sum += min(cap, abs(b[(y + sy) * aw + x + sx] - a[y * aw + x]))
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
            val cst = cost(p, c, cw, ch, sx, sy, 2, MAX_PIXEL_MISMATCH * f)
            if (cst < best) { best = cst; bestX = sx; bestY = sy }
        }

        // Refine to the pixel. A pixel off is fine: differencing tolerates ±1 pixel anyway.
        val pf = IntArray(w * h) { prev.data[it].toInt() and 0xFF }
        val cf = IntArray(w * h) { cur.data[it].toInt() and 0xFF }
        var fineX = bestX * f
        var fineY = bestY * f
        best = Double.MAX_VALUE
        for (sy in bestY * f - 2..bestY * f + 2) for (sx in bestX * f - 2..bestX * f + 2) {
            val cst = cost(pf, cf, w, h, sx, sy, 3, MAX_PIXEL_MISMATCH)
            if (cst < best) { best = cst; fineX = sx; fineY = sy }
        }
        return fineX to fineY
    }

    private const val COARSE_SEARCH = 5
    private const val MAX_PIXEL_MISMATCH = 30
}
