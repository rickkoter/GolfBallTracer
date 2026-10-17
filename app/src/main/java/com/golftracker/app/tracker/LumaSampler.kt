package com.golftracker.app.tracker

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns the luminance plane of a camera or video frame into a [LumaFrame]: rotated upright, cropped and
 * downsampled so its long side is about [targetLongSide].
 *
 * Frames come in sensor orientation (usually landscape) and often wider than what the preview shows.
 * After cropping to what the preview shows, a point's position in the grid is the same as its position
 * on screen, which is where the tracer is drawn.
 */
class LumaSampler(private val targetLongSide: Int) {

    private var geometry: Geometry? = null

    private class Geometry(
        val key: List<Any>,
        val gridWidth: Int,
        val gridHeight: Int,
        /** Buffer offset of a pixel is colOffset[gx] + rowOffset[gy]. */
        val colOffset: IntArray,
        val rowOffset: IntArray
    )

    /**
     * Samples [buffer]'s crop rectangle (in buffer pixels). With [uprightAspect] (width / height once
     * upright), the crop is first narrowed around its center to that shape, the way the preview's
     * FILL_CENTER crops the camera stream to the screen.
     */
    fun sample(
        buffer: ByteBuffer,
        cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int,
        rotation: Int, rowStride: Int, pixelStride: Int,
        timestampNs: Long,
        uprightAspect: Float? = null
    ): LumaFrame {
        val g = geometryFor(cropLeft, cropTop, cropWidth, cropHeight, rotation, rowStride, pixelStride, uprightAspect)
        val data = ByteArray(g.gridWidth * g.gridHeight)
        val limit = buffer.limit()
        var i = 0
        for (gy in 0 until g.gridHeight) {
            val row = g.rowOffset[gy]
            for (gx in 0 until g.gridWidth) {
                val idx = row + g.colOffset[gx]
                data[i++] = if (idx < limit) buffer.get(idx) else 0
            }
        }
        return LumaFrame(g.gridWidth, g.gridHeight, data, timestampNs)
    }

    private fun geometryFor(
        left0: Int, top0: Int, width0: Int, height0: Int,
        rotation: Int, rowStride: Int, pixelStride: Int, uprightAspect: Float?
    ): Geometry {
        val key = listOf(left0, top0, width0, height0, rotation, rowStride, pixelStride, uprightAspect ?: 0f)
        geometry?.let { if (it.key == key) return it }

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
        return Geometry(key, gw, gh, col, row).also { geometry = it }
    }
}
