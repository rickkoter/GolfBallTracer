package com.golftracker.app.tracker

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Converts camera frames into [LumaFrame]s for the shot detector.
 *
 * Each frame is rotated upright and cropped to the image's crop rect, which matches the preview when the
 * use cases share a ViewPort. Timestamps are moved onto the [SystemClock.elapsedRealtimeNanos] clock,
 * which is also what impact sounds are timed with.
 */
class BallTrackerAnalyzer(
    private val wantsFrames: () -> Boolean,
    private val onFrame: (LumaFrame) -> Unit,
    targetLongSide: Int = 640,
    /** Frames closer together than this are skipped, so analysis keeps up even when the camera runs at 60 fps. */
    private val minFrameIntervalNs: Long = 28_000_000L
) : ImageAnalysis.Analyzer {

    private val sampler = LumaSampler(targetLongSide)
    private var clockOffsetNs: Long? = null
    private var lastKeptNs = Long.MIN_VALUE

    override fun analyze(image: ImageProxy) {
        try {
            if (!wantsFrames()) return
            val timestamp = toElapsedRealtime(image.imageInfo.timestamp)
            if (timestamp - lastKeptNs < minFrameIntervalNs) return
            lastKeptNs = timestamp
            val plane = image.planes[0]
            val crop = image.cropRect
            onFrame(
                sampler.sample(
                    plane.buffer, crop.left, crop.top, crop.width(), crop.height(),
                    image.imageInfo.rotationDegrees, plane.rowStride, plane.pixelStride, timestamp
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    /**
     * Camera timestamps are on the boot-time clock on most phones, but some use the monotonic clock,
     * which stops during deep sleep. Whichever is closer to the frame's time is the one in use.
     */
    private fun toElapsedRealtime(sensorNs: Long): Long {
        val offset = clockOffsetNs ?: run {
            val boot = SystemClock.elapsedRealtimeNanos()
            val mono = System.nanoTime()
            if (abs(boot - sensorNs) <= abs(mono - sensorNs)) 0L else boot - mono
        }.also { clockOffsetNs = it }
        return sensorNs + offset
    }
}
