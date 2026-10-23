package com.golftracker.app.tracker

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tiny box-averaged copies of frames. Cheap enough to make for every frame, they show how much is moving
 * and let the same moment be found in the live frames and in the recorded video.
 */
internal object Thumbnails {

    const val LONG_SIDE = 96

    fun of(frame: LumaFrame, longSide: Int = LONG_SIDE): LumaFrame {
        val w = frame.width
        val h = frame.height
        val scale = longSide.toFloat() / max(w, h)
        val tw = max(1, (w * scale).roundToInt())
        val th = max(1, (h * scale).roundToInt())
        val sums = IntArray(tw * th)
        val counts = IntArray(tw * th)
        val d = frame.data
        val column = IntArray(w) { x -> (x * tw / w).coerceAtMost(tw - 1) }
        for (y in 0 until h) {
            val ty = (y * th / h).coerceAtMost(th - 1) * tw
            val row = y * w
            for (x in 0 until w) {
                val i = ty + column[x]
                sums[i] += d[row + x].toInt() and 0xFF
                counts[i]++
            }
        }
        return LumaFrame(tw, th, ByteArray(tw * th) { (sums[it] / max(1, counts[it])).toByte() }, frame.timestampNs)
    }

    /**
     * How much changed from [a] to [b]: the mean absolute difference per pixel after lining them up to
     * within a couple of thumbnail pixels, so a shaking hand doesn't count as movement in the scene.
     */
    fun motion(a: LumaFrame, b: LumaFrame, reach: Int = 2): Float {
        if (a.width != b.width || a.height != b.height) return 0f
        val w = a.width
        val h = a.height
        var best = Float.MAX_VALUE
        for (dy in -reach..reach) for (dx in -reach..reach) {
            var sum = 0L
            var n = 0
            for (y in max(0, -dy) until min(h, h - dy)) for (x in max(0, -dx) until min(w, w - dx)) {
                sum += abs((a.data[y * w + x].toInt() and 0xFF) - (b.data[(y + dy) * w + x + dx].toInt() and 0xFF))
                n++
            }
            if (n > 0) best = min(best, sum.toFloat() / n)
        }
        return best
    }

    /** Mean absolute difference per pixel, ignoring an overall change in brightness; MAX_VALUE if the shapes differ. */
    fun difference(a: LumaFrame, b: LumaFrame): Float {
        if (a.width != b.width || a.height != b.height) return Float.MAX_VALUE
        val n = a.data.size
        var meanA = 0L
        var meanB = 0L
        for (i in 0 until n) {
            meanA += a.data[i].toInt() and 0xFF
            meanB += b.data[i].toInt() and 0xFF
        }
        val offset = ((meanB - meanA).toFloat() / n).roundToInt()
        var sum = 0L
        for (i in 0 until n) sum += abs((a.data[i].toInt() and 0xFF) + offset - (b.data[i].toInt() and 0xFF))
        return sum.toFloat() / n
    }
}
