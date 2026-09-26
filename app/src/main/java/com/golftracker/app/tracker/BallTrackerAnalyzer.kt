package com.golftracker.app.tracker

import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.max

/**
 * Converts camera frames into [LumaFrame]s for [ShotCaptureSession].
 *
 * The camera delivers frames in sensor orientation (usually landscape) and wider than what the
 * preview shows. Each frame is rotated upright and cropped to the image's crop rect, which matches
 * the preview when the use cases share a ViewPort. That way a point's position in the grid is the
 * same as its position on screen, where the user tapped and where the tracer is drawn.
 */
class BallTrackerAnalyzer(
    private val session: ShotCaptureSession,
    private val targetLongSide: Int = 640
) : ImageAnalysis.Analyzer {

    private var geometry: Geometry? = null

    private class Geometry(
        val crop: Rect,
        val rotation: Int,
        val rowStride: Int,
        val pixelStride: Int,
        val gridWidth: Int,
        val gridHeight: Int,
        /** Buffer offset of a pixel is colOffset[gx] + rowOffset[gy]. */
        val colOffset: IntArray,
        val rowOffset: IntArray
    )

    override fun analyze(image: ImageProxy) {
        try {
            if (!session.isArmed) return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val g = geometryFor(image.cropRect, image.imageInfo.rotationDegrees, plane.rowStride, plane.pixelStride)

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
            session.process(LumaFrame(g.gridWidth, g.gridHeight, data, image.imageInfo.timestamp))
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun geometryFor(crop: Rect, rotation: Int, rowStride: Int, pixelStride: Int): Geometry {
        geometry?.let {
            if (it.crop == crop && it.rotation == rotation && it.rowStride == rowStride && it.pixelStride == pixelStride) return it
        }
        val cw = crop.width()
        val ch = crop.height()
        val sideways = rotation == 90 || rotation == 270
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
                90 -> (crop.top + ch - 1 - ux) * rowStride
                180 -> (crop.left + cw - 1 - ux) * pixelStride
                270 -> (crop.top + ux) * rowStride
                else -> (crop.left + ux) * pixelStride
            }
        }
        for (gy in 0 until gh) {
            val uy = gy * step + step / 2
            row[gy] = when (rotation) {
                90 -> (crop.left + uy) * pixelStride
                180 -> (crop.top + ch - 1 - uy) * rowStride
                270 -> (crop.left + cw - 1 - uy) * pixelStride
                else -> (crop.top + uy) * rowStride
            }
        }
        return Geometry(Rect(crop), rotation, rowStride, pixelStride, gw, gh, col, row).also { geometry = it }
    }
}
