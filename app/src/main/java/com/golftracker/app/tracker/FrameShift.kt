package com.golftracker.app.tracker

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Follows camera shake between frames, so a hand-held or nudged phone's frames can be compared like-for-like. */
internal object FrameShift {

    private const val F = 4
    private const val COARSE_SEARCH = 5
    private const val MAX_PIXEL_MISMATCH = 30
    private const val FINE_STEP = 3

    /**
     * A frame with its 4× smaller copy made once, so it can be matched against several others without
     * shrinking it again each time.
     */
    class Prepared(val frame: LumaFrame) {
        val cw = frame.width / F
        val ch = frame.height / F
        val small = IntArray(cw * ch).also { out ->
            val w = frame.width
            val d = frame.data
            for (y in 0 until ch * F) {
                val row = (y / F) * cw
                val src = y * w
                for (x in 0 until cw * F) out[row + x / F] += d[src + x].toInt() and 0xFF
            }
        }
    }

    /**
     * How far the scene moved from [prev] to [cur] (content at prev(x, y) is at cur(x + sx, y + sy)),
     * found by block matching: a coarse search on 4× smaller images, then a fine one around it.
     */
    fun estimate(prev: LumaFrame, cur: LumaFrame): Pair<Int, Int> = estimate(Prepared(prev), Prepared(cur))

    /**
     * As above, with the coarse search centred on a guess ([guessX], [guessY], in full pixels): the
     * shift already built up frame by frame, so a camera that panned a long way since [prev] is still followed.
     * Only rows from [fromRow] (a fraction of the height) down are matched: when the phone itself moves,
     * the ground near it shifts further than distant trees, and lining up the ground is what's wanted there.
     */
    fun estimate(prev: Prepared, cur: Prepared, guessX: Int = 0, guessY: Int = 0, fromRow: Float = 0f): Pair<Int, Int> {
        val cw = prev.cw
        val ch = prev.ch
        if (cw != cur.cw || ch != cur.ch || cw == 0 || ch == 0) return 0 to 0
        val gx = Math.round(guessX.toFloat() / F)
        val gy = Math.round(guessY.toFloat() / F)
        var bestX = gx
        var bestY = gy
        var best = Double.MAX_VALUE
        for (sy in gy - COARSE_SEARCH..gy + COARSE_SEARCH) for (sx in gx - COARSE_SEARCH..gx + COARSE_SEARCH) {
            val cst = coarseCost(prev.small, cur.small, cw, ch, sx, sy, (fromRow * ch).toInt())
            if (cst < best) { best = cst; bestX = sx; bestY = sy }
        }
        return refine(prev.frame, cur.frame, bestX * F, bestY * F, 2, fromRow)
    }

    /** The best shift within [reach] pixels of ([guessX], [guessY]), matched at full size. */
    fun refine(prev: LumaFrame, cur: LumaFrame, guessX: Int, guessY: Int, reach: Int = 2, fromRow: Float = 0f): Pair<Int, Int> {
        if (prev.width != cur.width || prev.height != cur.height) return 0 to 0
        var fineX = guessX
        var fineY = guessY
        var best = Double.MAX_VALUE
        for (sy in guessY - reach..guessY + reach) for (sx in guessX - reach..guessX + reach) {
            val cst = fineCost(prev.data, cur.data, prev.width, prev.height, sx, sy, (fromRow * prev.height).toInt())
            if (cst < best) { best = cst; fineX = sx; fineY = sy }
        }
        return fineX to fineY
    }

    // Each pixel's mismatch is capped, so a golfer swinging through the picture can't outweigh the
    // whole background and pass for the camera moving. A slight preference for no movement keeps a
    // featureless scene from wandering.
    private fun coarseCost(a: IntArray, b: IntArray, w: Int, h: Int, sx: Int, sy: Int, fromRow: Int): Double {
        val cap = MAX_PIXEL_MISMATCH * F
        var sum = 0L
        var n = 0
        var y = max(fromRow, -sy)
        val yEnd = min(h, h - sy)
        val xStart = max(0, -sx)
        val xEnd = min(w, w - sx)
        while (y < yEnd) {
            val ra = y * w
            val rb = (y + sy) * w + sx
            var x = xStart
            while (x < xEnd) {
                val d = b[rb + x] - a[ra + x]
                sum += min(cap, if (d < 0) -d else d)
                n++
                x += 2
            }
            y += 2
        }
        return if (n < w * (h - fromRow) / 16) Double.MAX_VALUE else sum.toDouble() / n * (1 + 0.002 * (abs(sx) + abs(sy)))
    }

    private fun fineCost(a: ByteArray, b: ByteArray, w: Int, h: Int, sx: Int, sy: Int, fromRow: Int): Double {
        var sum = 0L
        var n = 0
        var y = max(fromRow, -sy)
        val yEnd = min(h, h - sy)
        val xStart = max(0, -sx)
        val xEnd = min(w, w - sx)
        while (y < yEnd) {
            val ra = y * w
            val rb = (y + sy) * w + sx
            var x = xStart
            while (x < xEnd) {
                val d = (b[rb + x].toInt() and 0xFF) - (a[ra + x].toInt() and 0xFF)
                sum += min(MAX_PIXEL_MISMATCH, if (d < 0) -d else d)
                n++
                x += FINE_STEP
            }
            y += FINE_STEP
        }
        return if (n < w * (h - fromRow) / (4 * FINE_STEP * FINE_STEP)) Double.MAX_VALUE else sum.toDouble() / n * (1 + 0.002 * (abs(sx) + abs(sy)))
    }
}
