package com.golftracker.app.tracker

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.golftracker.app.model.ScreenPoint
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class BallTrackerAnalyzer(
    private val onBallDetected: (ScreenPoint) -> Unit
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)
    private var lastDetectionTime = 0L

    // Tapped Initial Golf Ball Origin (Screen normalized 0.0 .. 1.0)
    private var initialBallLocation: ScreenPoint? = null

    // Trajectory Tracking & Physics State
    private var lastPoint: ScreenPoint? = null
    private var lastVx = 0f
    private var lastVy = -0.15f // Initial upward velocity bias in screen coordinates
    private var ay = 0.40f      // Downward gravity acceleration in screen Y (Y grows downward)
    private var consecutiveHits = 0
    private var isBallLaunched = false

    fun setInitialBallLocation(normX: Float, normY: Float) {
        initialBallLocation = ScreenPoint(normX, normY)
        resetTracker()
    }

    fun resetTracker() {
        lastPoint = null
        lastVx = 0f
        lastVy = -0.15f
        consecutiveHits = 0
        isBallLaunched = false
    }

    override fun analyze(image: ImageProxy) {
        if (isProcessing.get()) {
            image.close()
            return
        }

        isProcessing.set(true)

        try {
            if (image.format == ImageFormat.YUV_420_888) {
                val yPlane = image.planes[0]
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]

                val yBuffer = yPlane.buffer
                val uBuffer = uPlane.buffer
                val vBuffer = vPlane.buffer

                val width = image.width
                val height = image.height

                val yRowStride = yPlane.rowStride
                val yPixelStride = yPlane.pixelStride

                val now = System.currentTimeMillis()
                val dt = if (lastDetectionTime > 0) maxOf(0.01f, (now - lastDetectionTime) / 1000f) else 0.033f

                if (now - lastDetectionTime > 25) { // ~30-40 fps max analysis frequency
                    var bestCandidateX = -1
                    var bestCandidateY = -1
                    var bestScore = 0f

                    val step = 4 // Downsample stride

                    // 1. Calculate Search Region-of-Interest (ROI) Gate using Parabolic Physics Prediction
                    val minX: Int
                    val maxX: Int
                    val minY: Int
                    val maxY: Int

                    val initLoc = initialBallLocation
                    val prev = lastPoint

                    if (prev != null && isBallLaunched) {
                        // ACTIVE FLIGHT TRACKING: Predict next position using velocity & gravity (x_hat, y_hat)
                        val predictedX = prev.x + lastVx * dt
                        val predictedY = prev.y + lastVy * dt + 0.5f * ay * dt * dt

                        // Search tight 8% gate centered at the predicted parabolic position
                        minX = maxOf(0, ((predictedX - 0.08f) * width).toInt())
                        maxX = minOf(width, ((predictedX + 0.08f) * width).toInt())
                        minY = maxOf(0, ((predictedY - 0.08f) * height).toInt())
                        maxY = minOf(height, ((predictedY + 0.08f) * height).toInt())
                    } else if (initLoc != null) {
                        // LAUNCH ORIGIN GATING: Search tight 6% box around the tapped tee ball position
                        minX = maxOf(0, ((initLoc.x - 0.06f) * width).toInt())
                        maxX = minOf(width, ((initLoc.x + 0.06f) * width).toInt())
                        minY = maxOf(0, ((initLoc.y - 0.08f) * height).toInt())
                        maxY = minOf(height, ((initLoc.y + 0.06f) * height).toInt())
                    } else {
                        // Default fallback: central tee box region
                        minX = (width * 0.25).toInt()
                        maxX = (width * 0.75).toInt()
                        minY = (height * 0.50).toInt()
                        maxY = (height * 0.90).toInt()
                    }

                    for (y in minY until maxY step step) {
                        for (x in minX until maxX step step) {
                            val yIndex = y * yRowStride + x * yPixelStride
                            if (yIndex >= yBuffer.remaining()) continue

                            val lum = yBuffer.get(yIndex).toInt() and 0xFF

                            // High Brightness Threshold (golf balls are bright white or hi-vis yellow)
                            if (lum < 210) continue

                            // Blob Compactness & Edge Drop-off Filter (reject large white objects like shirts/clouds)
                            val isCompact = checkBlobCompactness(yBuffer, x, y, width, height, yRowStride, yPixelStride)
                            if (!isCompact) continue

                            // Chrominance Filter (White: U~128, V~128 | Yellow: U~105, V~145)
                            val uvX = x / 2
                            val uvY = y / 2
                            val uIndex = uvY * uPlane.rowStride + uvX * uPlane.pixelStride
                            val vIndex = uvY * vPlane.rowStride + uvX * vPlane.pixelStride

                            var isColorMatched = true
                            if (uIndex < uBuffer.remaining() && vIndex < vBuffer.remaining()) {
                                val u = uBuffer.get(uIndex).toInt() and 0xFF
                                val v = vBuffer.get(vIndex).toInt() and 0xFF
                                
                                val isWhiteBall = abs(u - 128) < 22 && abs(v - 128) < 22
                                val isYellowBall = u >= 80 && u <= 130 && v >= 130 && v <= 170
                                isColorMatched = isWhiteBall || isYellowBall
                            }

                            if (!isColorMatched) continue

                            val normX = x.toFloat() / width
                            val normY = y.toFloat() / height

                            // 2. Physics & Directional Alignment Constraints
                            if (prev != null && isBallLaunched) {
                                val dx = normX - prev.x
                                val dy = normY - prev.y
                                val dist = sqrt(dx * dx + dy * dy)

                                // Reject spatial teleportation or static non-moving noise
                                if (dist > 0.20f || dist < 0.003f) continue

                                // Direction Alignment Check: Candidate vector must align with previous velocity (no sharp turns!)
                                if (consecutiveHits >= 2 && (lastVx != 0f || lastVy != 0f)) {
                                    val candidateVx = dx / dt
                                    val candidateVy = dy / dt

                                    val prevMag = sqrt(lastVx * lastVx + lastVy * lastVy)
                                    val candMag = sqrt(candidateVx * candidateVx + candidateVy * candidateVy)

                                    if (prevMag > 0.01f && candMag > 0.01f) {
                                        val dot = (lastVx * candidateVx + lastVy * candidateVy) / (prevMag * candMag)
                                        // Reject candidate if heading direction changes by more than 40 degrees (dot product < 0.76)
                                        if (dot < 0.76f) continue
                                    }
                                }
                            } else if (initLoc != null && !isBallLaunched) {
                                // Launch Gate: Require ball to move away from tee origin with upward/forward displacement
                                val dx = normX - initLoc.x
                                val dy = normY - initLoc.y
                                val launchDist = sqrt(dx * dx + dy * dy)

                                // Ball must have actually launched off the tee (moved > 1.5% screen distance)
                                if (launchDist < 0.015f) continue
                            }

                            val score = lum.toFloat()
                            if (score > bestScore) {
                                bestScore = score
                                bestCandidateX = x
                                bestCandidateY = y
                            }
                        }
                    }

                    if (bestCandidateX != -1 && bestCandidateY != -1) {
                        val normX = bestCandidateX.toFloat() / width
                        val normY = bestCandidateY.toFloat() / height

                        val origin = prev ?: initLoc ?: ScreenPoint(normX, normY)
                        val dx = normX - origin.x
                        val dy = normY - origin.y

                        lastVx = dx / dt
                        lastVy = dy / dt
                        isBallLaunched = true
                        consecutiveHits++

                        val point = ScreenPoint(normX, normY, now, confidence = bestScore / 255f)
                        lastPoint = point
                        lastDetectionTime = now

                        onBallDetected(point)
                    } else {
                        // Reset tracking state if ball exits frame or disappears for > 600ms
                        if (now - lastDetectionTime > 600) {
                            resetTracker()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
            isProcessing.set(false)
        }
    }

    private fun checkBlobCompactness(
        yBuffer: ByteBuffer,
        cx: Int,
        cy: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): Boolean {
        val rOuter = 14
        var darkOuterCount = 0
        var sampledOuter = 0

        val offsets = arrayOf(
            Pair(-rOuter, 0), Pair(rOuter, 0), Pair(0, -rOuter), Pair(0, rOuter),
            Pair(-rOuter, -rOuter), Pair(rOuter, rOuter), Pair(-rOuter, rOuter), Pair(rOuter, -rOuter)
        )

        for ((dx, dy) in offsets) {
            val nx = cx + dx
            val ny = cy + dy
            if (nx in 0 until width && ny in 0 until height) {
                val idx = ny * rowStride + nx * pixelStride
                if (idx < yBuffer.remaining()) {
                    val lum = yBuffer.get(idx).toInt() and 0xFF
                    sampledOuter++
                    if (lum < 190) {
                        darkOuterCount++
                    }
                }
            }
        }

        return sampledOuter > 0 && (darkOuterCount.toFloat() / sampledOuter) >= 0.55f
    }
}
