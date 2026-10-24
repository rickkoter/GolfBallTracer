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
    /** Frames still waiting for the detector: up to 60 fps are analysed while it keeps up, else 30 (see [FramePacer]). */
    private val queuedFrames: () -> Int = { 0 },
    targetLongSide: Int = 640
) : ImageAnalysis.Analyzer {

    private val sampler = LumaSampler(targetLongSide)
    private val pacer = FramePacer()

    /** The rate frames are analysed at now (at most the camera's own). */
    val analysisFps: Double get() = pacer.fps
    private var clockOffsetNs: Long? = null

    override fun analyze(image: ImageProxy) {
        try {
            if (!wantsFrames()) return
            val timestamp = toElapsedRealtime(image.imageInfo.timestamp)
            if (!pacer.keep(timestamp, queuedFrames())) return
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
