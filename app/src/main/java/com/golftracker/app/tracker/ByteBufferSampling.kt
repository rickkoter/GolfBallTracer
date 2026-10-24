package com.golftracker.app.tracker

import java.nio.ByteBuffer

/**
 * Samples [buffer]'s crop rectangle (in buffer pixels) into a [LumaFrame], laid out by the shared
 * [LumaSampler]: upright, cropped to [uprightAspect] around its center and downsampled.
 */
fun LumaSampler.sample(
    buffer: ByteBuffer,
    cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int,
    rotation: Int, rowStride: Int, pixelStride: Int,
    timestampNs: Long,
    uprightAspect: Float? = null
): LumaFrame {
    val g = layout(cropLeft, cropTop, cropWidth, cropHeight, rotation, rowStride, pixelStride, uprightAspect)
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
