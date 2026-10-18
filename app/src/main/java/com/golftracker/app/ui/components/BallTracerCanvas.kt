package com.golftracker.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.model.TracerStyle
import com.golftracker.app.model.isPixelTracer
import com.golftracker.app.model.tracerRuns
import com.golftracker.app.tracker.TrajectoryMath
import kotlin.math.sin

@Composable
fun BallTracerCanvas(
    points: List<ScreenPoint>,
    style: TracerStyle,
    isRecording: Boolean,
    isEditMode: Boolean,
    tappedBallLocation: ScreenPoint?,
    onTapBallLocation: (ScreenPoint) -> Unit,
    /** Radius of the ball found at the tap, as a fraction of the height; the marker is sized to it. */
    ballRadius: Float? = null,
    /** Diagnostic: every spot the detector saw, colored from early (cyan) to late (red). */
    debugSpots: List<ScreenPoint> = emptyList(),
    onPointAdjusted: ((List<ScreenPoint>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Pulse animation for ball launch & target reticle
    val transitionState = rememberInfiniteTransition(label = "pulse")
    val pulseScale by transitionState.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val coreColor = Color(style.colorHex)
    val glowColor = Color(style.secondaryHex)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val normX = offset.x / size.width
                    val normY = offset.y / size.height
                    onTapBallLocation(ScreenPoint(normX, normY))
                }
            }
            .then(
                if (isEditMode && onPointAdjusted != null) {
                    Modifier.pointerInput(points) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (points.isNotEmpty()) {
                                val touchX = change.position.x / size.width
                                val touchY = change.position.y / size.height
                                
                                val updated = points.mapIndexed { idx, pt ->
                                    if (idx == points.size / 2) {
                                        ScreenPoint(touchX, touchY, pt.timestampMs, 1.0f)
                                    } else pt
                                }
                                onPointAdjusted(TrajectoryMath.smoothTrajectory(updated))
                            }
                        }
                    }
                } else Modifier
            )
    ) {
        val width = size.width
        val height = size.height

        if (debugSpots.isNotEmpty()) {
            val first = debugSpots.minOf { it.timestampMs }
            val span = maxOf(1L, debugSpots.maxOf { it.timestampMs } - first).toFloat()
            for (spot in debugSpots) {
                val f = (spot.timestampMs - first) / span
                drawCircle(
                    color = lerp(Color.Cyan, Color.Red, f).copy(alpha = 0.7f),
                    radius = width * 0.006f,
                    center = Offset(spot.x * width, spot.y * height)
                )
            }
        }

        // 1. Draw Tapped Ball Target Reticle if ball origin is locked & no active curve recorded yet
        if (tappedBallLocation != null && points.isEmpty()) {
            val tx = tappedBallLocation.x * width
            val ty = tappedBallLocation.y * height

            if (ballRadius != null) {
                // Locked on: a ring hugging the ball that was found, so it's clear what is being tracked.
                val r = maxOf(ballRadius * height, width * 0.01f)
                drawCircle(
                    color = glowColor,
                    radius = (r + width * 0.02f) * pulseScale,
                    center = Offset(tx, ty),
                    style = Stroke(width = width * 0.005f)
                )
                drawCircle(
                    color = coreColor,
                    radius = r + width * 0.006f,
                    center = Offset(tx, ty),
                    style = Stroke(width = width * 0.006f)
                )
            } else {
                // Still checking the tapped spot.
                drawCircle(
                    color = glowColor,
                    radius = (width * 0.055f) * pulseScale,
                    center = Offset(tx, ty),
                    style = Stroke(width = width * 0.005f)
                )
                drawCircle(
                    color = coreColor,
                    radius = width * 0.024f,
                    center = Offset(tx, ty),
                    style = Stroke(width = width * 0.008f)
                )
                drawCircle(
                    color = Color.White,
                    radius = width * 0.008f,
                    center = Offset(tx, ty)
                )

                val len = width * 0.035f
                drawLine(color = coreColor, start = Offset(tx - len, ty), end = Offset(tx + len, ty), strokeWidth = 3f)
                drawLine(color = coreColor, start = Offset(tx, ty - len), end = Offset(tx, ty + len), strokeWidth = 3f)
            }
        }

        // 2. Draw Trajectory Curve
        val activePoints = points

        if (activePoints.size >= 2) {
            val pixels = isPixelTracer(activePoints)
            fun at(pt: ScreenPoint) = if (pixels) Offset(pt.x, pt.y) else Offset(pt.x * width, pt.y * height)
            val startX = at(activePoints.first()).x
            val startY = at(activePoints.first()).y

            // Where the ball was seen is drawn solid; where its flight was estimated, dashed.
            for ((run, estimated) in tracerRuns(activePoints)) {
                val path = Path().apply {
                    moveTo(at(run.first()).x, at(run.first()).y)
                    run.drop(1).forEach { lineTo(at(it).x, at(it).y) }
                }
                val dash = if (estimated) PathEffect.dashPathEffect(floatArrayOf(width * 0.03f, width * 0.025f)) else null
                // Outer neon glow
                drawPath(
                    path = path,
                    color = glowColor,
                    style = Stroke(width = width * 0.024f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash)
                )
                // Inner core line
                drawPath(
                    path = path,
                    color = coreColor,
                    style = Stroke(width = width * 0.010f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash)
                )
            }

            // Particle sparks along curve
            val sparkCount = 8
            for (k in 0 until sparkCount) {
                val fraction = (k + 1) / (sparkCount + 1f)
                val index = (fraction * (activePoints.size - 1)).toInt()
                val pt = activePoints[index]
                val px = at(pt).x
                val py = at(pt).y

                val sparkRadius = (width * 0.008f) * (1f + 0.3f * sin(k * 2.0)).toFloat()
                drawCircle(
                    color = Color.White,
                    radius = sparkRadius,
                    center = Offset(px, py)
                )
            }

            // Launch Point Tee Marker
            drawCircle(
                color = coreColor,
                radius = width * 0.018f,
                center = Offset(startX, startY)
            )
            drawCircle(
                color = Color.White,
                radius = width * 0.008f,
                center = Offset(startX, startY)
            )

            // Landing Target Pin Marker
            val endX = at(activePoints.last()).x
            val endY = at(activePoints.last()).y

            drawCircle(
                color = glowColor,
                radius = (width * 0.035f) * pulseScale,
                center = Offset(endX, endY),
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = coreColor,
                radius = width * 0.015f,
                center = Offset(endX, endY)
            )
            drawCircle(
                color = Color.White,
                radius = width * 0.006f,
                center = Offset(endX, endY)
            )
        }
    }
}
