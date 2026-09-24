package com.golftracker.app.tracker

import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.model.ScreenPoint
import kotlin.math.*

object TrajectoryMath {

    private const val METERS_PER_YARD = 0.9144
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates the Haversine distance between launch and landing GPS coordinates in meters.
     */
    fun calculateGpsDistanceMeters(start: GpsCoordinate, end: GpsCoordinate): Double {
        val dLat = Math.toRadians(end.latitude - start.latitude)
        val dLon = Math.toRadians(end.longitude - start.longitude)
        
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)

        val a = sin(dLat / 2).pow(2) + sin(dLon / 2).pow(2) * cos(lat1) * cos(lat2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Converts meters to yards.
     */
    fun metersToYards(meters: Double): Double {
        return meters / METERS_PER_YARD
    }

    /**
     * Converts yards to meters.
     */
    fun yardsToMeters(yards: Double): Double {
        return yards * METERS_PER_YARD
    }

    /**
     * Estimates shot distance from camera launch angle and apex height when landing GPS is unavailable.
     */
    fun estimateDistanceFromLaunch(
        launchAngleDegrees: Float,
        apexHeightYards: Float = 30f
    ): Double {
        val rad = Math.toRadians(launchAngleDegrees.toDouble())
        if (sin(2 * rad) <= 0.0) return 150.0
        
        // Aerodynamic projectile estimation with dimple drag factor (~0.58)
        val idealVelocity = sqrt((2 * 9.81 * (apexHeightYards * METERS_PER_YARD)) / sin(rad).pow(2))
        val rangeMeters = (idealVelocity.pow(2) * sin(2 * rad) / 9.81) * 0.58
        return metersToYards(rangeMeters)
    }

    /**
     * Fits a least-squares parabolic curve y = a*x^2 + b*x + c through screen points.
     * Returns [a, b, c] or null if system is singular.
     */
    fun fitParabola(points: List<ScreenPoint>): DoubleArray? {
        if (points.size < 3) return null

        var n = 0.0
        var sumX = 0.0
        var sumX2 = 0.0
        var sumX3 = 0.0
        var sumX4 = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2Y = 0.0

        for (p in points) {
            val x = p.x.toDouble()
            val y = p.y.toDouble()
            val x2 = x * x
            n += 1.0
            sumX += x
            sumX2 += x2
            sumX3 += x2 * x
            sumX4 += x2 * x2
            sumY += y
            sumXY += x * y
            sumX2Y += x2 * y
        }

        val detM = sumX4 * (sumX2 * n - sumX * sumX) -
                   sumX3 * (sumX3 * n - sumX * sumX2) +
                   sumX2 * (sumX3 * sumX - sumX2 * sumX2)

        if (abs(detM) < 1e-7) return null

        val detA = sumX2Y * (sumX2 * n - sumX * sumX) -
                   sumX3 * (sumXY * n - sumX * sumY) +
                   sumX2 * (sumXY * sumX - sumX2 * sumY)

        val detB = sumX4 * (sumXY * n - sumX * sumY) -
                   sumX2Y * (sumX3 * n - sumX * sumX2) +
                   sumX2 * (sumX3 * sumY - sumXY * sumX2)

        val detC = sumX4 * (sumX2 * sumY - sumXY * sumX) -
                   sumX3 * (sumX3 * sumY - sumXY * sumX2) +
                   sumX2Y * (sumX3 * sumX - sumX2 * sumX2)

        val a = detA / detM
        val b = detB / detM
        val c = detC / detM

        return doubleArrayOf(a, b, c)
    }

    /**
     * Filters spatial noise, direction reversals, and parabolic fit outliers.
     */
    fun filterOutliers(points: List<ScreenPoint>): List<ScreenPoint> {
        if (points.size <= 2) return points

        // Pass 1: Filter spatial jumps & backward reversals
        val stepFiltered = mutableListOf<ScreenPoint>()
        stepFiltered.add(points.first())

        for (i in 1 until points.size) {
            val prev = stepFiltered.last()
            val curr = points[i]

            val dx = curr.x - prev.x
            val dy = curr.y - prev.y
            val dist = sqrt(dx * dx + dy * dy)

            // Keep points within physical displacement bounds per frame
            if (dist in 0.001f..0.45f) {
                stepFiltered.add(curr)
            }
        }

        if (stepFiltered.size <= 4) return stepFiltered

        // Pass 2: Parabolic Least-Squares Outlier Purge
        val parabola = fitParabola(stepFiltered) ?: return stepFiltered
        val a = parabola[0]
        val b = parabola[1]
        val c = parabola[2]

        val parabolaFiltered = mutableListOf<ScreenPoint>()
        for (p in stepFiltered) {
            val x = p.x.toDouble()
            val expectedY = a * x * x + b * x + c
            val error = abs(p.y.toDouble() - expectedY)

            // Keep points within 5% screen height error of parabolic trajectory
            if (error <= 0.05) {
                parabolaFiltered.add(p)
            }
        }

        return if (parabolaFiltered.size >= 2) parabolaFiltered else stepFiltered
    }

    /**
     * Fits a smooth quadratic trajectory y(x) = a*x^2 + b*x + c through screen points.
     * Returns smooth screen points formatted for tracer curve.
     */
    fun smoothTrajectory(
        rawPoints: List<ScreenPoint>,
        sampleCount: Int = 40
    ): List<ScreenPoint> {
        val points = filterOutliers(rawPoints)
        if (points.size < 2) return points

        val start = points.first()
        val end = points.last()

        // Calculate highest point (apex) in screen coordinates (screen Y goes downwards)
        val minScreenY = points.minOf { it.y }
        val apexX = points.find { it.y == minScreenY }?.x ?: ((start.x + end.x) / 2f)
        val apexY = maxOf(0.05f, minScreenY - 0.02f) // Clean apex peak

        val result = mutableListOf<ScreenPoint>()
        val startTime = start.timestampMs
        val totalTime = maxOf(100L, end.timestampMs - startTime)

        for (i in 0..sampleCount) {
            val t = i.toFloat() / sampleCount
            val invT = 1f - t
            val x = invT * invT * start.x + 2 * invT * t * apexX + t * t * end.x
            val y = invT * invT * start.y + 2 * invT * t * apexY + t * t * end.y
            
            val pointTime = startTime + (t * totalTime).toLong()
            result.add(ScreenPoint(x, y, pointTime, confidence = 0.95f))
        }

        return result
    }

    /**
     * Generates a default parabolic shot tracer curve across the viewfinder if no manual points exist.
     */
    fun generateDefaultTracer(
        viewWidth: Float,
        viewHeight: Float,
        apexFactor: Float = 0.45f
    ): List<ScreenPoint> {
        val startX = viewWidth * 0.5f
        val startY = viewHeight * 0.85f

        val apexX = viewWidth * 0.45f
        val apexY = viewHeight * (1f - apexFactor)

        val endX = viewWidth * 0.35f
        val endY = viewHeight * 0.55f

        val points = mutableListOf<ScreenPoint>()
        val now = System.currentTimeMillis()

        for (i in 0..30) {
            val t = i / 30f
            val invT = 1f - t
            val x = invT * invT * startX + 2 * invT * t * apexX + t * t * endX
            val y = invT * invT * startY + 2 * invT * t * apexY + t * t * endY
            points.add(ScreenPoint(x, y, now + (i * 100)))
        }

        return points
    }
}
