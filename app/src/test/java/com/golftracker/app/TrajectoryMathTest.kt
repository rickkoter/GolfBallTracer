package com.golftracker.app

import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.tracker.TrajectoryMath
import org.junit.Assert.*
import org.junit.Test

class TrajectoryMathTest {

    @Test
    fun testHaversineDistanceCalculation() {
        // Tee at Augusta National Hole 1 (approx)
        val tee = GpsCoordinate(33.5031, -82.0225)
        // Landing area ~250 yards away
        val landing = GpsCoordinate(33.5050, -82.0225)

        val distanceMeters = TrajectoryMath.calculateGpsDistanceMeters(tee, landing)
        val distanceYards = TrajectoryMath.metersToYards(distanceMeters)

        assertTrue("Distance in meters should be positive", distanceMeters > 0)
        assertTrue("Distance in yards should be approximately 230 yards", distanceYards in 200.0..260.0)
    }

    @Test
    fun testUnitConversions() {
        val yards = 100.0
        val meters = TrajectoryMath.yardsToMeters(yards)
        val backToYards = TrajectoryMath.metersToYards(meters)

        assertEquals(91.44, meters, 0.01)
        assertEquals(100.0, backToYards, 0.01)
    }

    @Test
    fun testTrajectorySmoothing() {
        val rawPoints = listOf(
            ScreenPoint(0.5f, 0.9f),
            ScreenPoint(0.45f, 0.5f),
            ScreenPoint(0.35f, 0.2f)
        )

        val smoothed = TrajectoryMath.smoothTrajectory(rawPoints, sampleCount = 20)

        assertEquals(21, smoothed.size)
        assertEquals(0.5f, smoothed.first().x, 0.05f)
        assertEquals(0.9f, smoothed.first().y, 0.05f)
        assertTrue("Apex Y should be higher up on screen (smaller Y value)", smoothed.minOf { it.y } < 0.5f)
    }

    @Test
    fun testEstimateDistanceFromLaunch() {
        val estimatedYards = TrajectoryMath.estimateDistanceFromLaunch(launchAngleDegrees = 14.5f, apexHeightYards = 30f)
        assertTrue("Estimated distance should be realistic for driver", estimatedYards in 150.0..350.0)
    }
}
