package com.golftracker.app.tracker

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Works out how to turn the luminance plane of a camera or video frame into a [LumaFrame]: rotated
 * upright, cropped and downsampled so its long side is about [targetLongSide]. Each app reads the pixels
 * from its own kind of buffer ([Layout.offset] gives where each one is).
 *
 * Frames come in sensor orientation (usually landscape) and often wider than what the preview shows.
 * After cropping to what the preview shows, a point's position in the grid is the same as its position
 * on screen, which is where the tracer is drawn.
 */
class LumaSampler(private val targetLongSide: Int) {

    private var layout: Layout? = null

    class Layout internal constructor(
        internal val key: List<Any>,
        val gridWidth: Int,
        val gridHeight: Int,
        /** Buffer offset of a pixel is colOffset[gx] + rowOffset[gy]. */
        val colOffset: IntArray,
        val rowOffset: IntArray
    ) {
        fun offset(gx: Int, gy: Int) = colOffset[gx] + rowOffset[gy]
    }

    /**
     * Where to sample the buffer's crop rectangle (in buffer pixels). With [uprightAspect] (width / height
     * once upright), the crop is first narrowed around its center to that shape, the way the preview's
     * fill-the-screen scaling crops the camera stream to the screen. Kept until the inputs change.
     */
    fun layout(
        left0: Int, top0: Int, width0: Int, height0: Int,
        rotation: Int, rowStride: Int, pixelStride: Int, uprightAspect: Float? = null
    ): Layout {
        val key = listOf(left0, top0, width0, height0, rotation, rowStride, pixelStride, uprightAspect ?: 0f)
        layout?.let { if (it.key == key) return it }

        val sideways = rotation == 90 || rotation == 270
        var left = left0
        var top = top0
        var cw = width0
        var ch = height0
        if (uprightAspect != null && uprightAspect > 0f) {
            // The wanted shape in buffer terms: sideways, the upright width runs along the buffer's height.
            val bufferAspect = if (sideways) 1f / uprightAspect else uprightAspect
            if (cw.toFloat() / ch > bufferAspect) {
                val w = (ch * bufferAspect).roundToInt().coerceIn(1, cw)
                left += (cw - w) / 2
                cw = w
            } else {
                val h = (cw / bufferAspect).roundToInt().coerceIn(1, ch)
                top += (ch - h) / 2
                ch = h
            }
        }

        val uprightW = if (sideways) ch else cw
        val uprightH = if (sideways) cw else ch
        val step = max(1, (max(uprightW, uprightH) + targetLongSide - 1) / targetLongSide)
        val gw = uprightW / step
        val gh = uprightH / step

        // Upright pixel (ux, uy) comes from buffer pixel (bx, by) inside the crop rect.
        // For 90° the buffer is turned clockwise to stand upright: bx = uy, by = ch - 1 - ux.
        val col = IntArray(gw)
        val row = IntArray(gh)
        for (gx in 0 until gw) {
            val ux = gx * step + step / 2
            col[gx] = when (rotation) {
                90 -> (top + ch - 1 - ux) * rowStride
                180 -> (left + cw - 1 - ux) * pixelStride
                270 -> (top + ux) * rowStride
                else -> (left + ux) * pixelStride
            }
        }
        for (gy in 0 until gh) {
            val uy = gy * step + step / 2
            row[gy] = when (rotation) {
                90 -> (left + uy) * pixelStride
                180 -> (top + ch - 1 - uy) * rowStride
                270 -> (left + cw - 1 - uy) * pixelStride
                else -> (top + uy) * rowStride
            }
        }
        return Layout(key, gw, gh, col, row).also { layout = it }
    }
}
