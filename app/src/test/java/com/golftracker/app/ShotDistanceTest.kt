package com.golftracker.app

import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.model.distance
import com.golftracker.app.tracker.LaunchPhysics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotDistanceTest {

    private val tee = GpsCoordinate(40.0, -80.0)
    private val estimate = LaunchPhysics.Landing(GpsCoordinate(40.0003, -80.0), 0.0, 25.0, 27.432, 14.0, 38.0)

    @Test
    fun aPinnedBallIsMeasuredFromTheTee() {
        // 0.001° of latitude is about 111 m.
        val shot = ShotRecord(launchLocation = tee, landingLocation = GpsCoordinate(40.001, -80.0), estimatedLanding = estimate)
        val d = shot.distance!!
        assertEquals(121.6, d.yards, 0.5)
        assertEquals(false, d.estimated)
        assertEquals("122 YDS", d.label)
    }

    @Test
    fun withoutAPinTheEstimateIsUsed() {
        val d = ShotRecord(launchLocation = tee, estimatedLanding = estimate).distance!!
        assertEquals(30.0, d.yards, 0.01)
        assertTrue(d.estimated)
        assertEquals("~30 YDS", d.label)
    }

    @Test
    fun theDistanceSavedWhileWalkingIsNotTakenForTheShot() {
        // As saved on 23 Oct: the phone hadn't moved, so the "distance" was a fraction of a yard.
        assertNull(ShotRecord(launchLocation = tee, distanceYards = 0.087).distance)
    }
}
