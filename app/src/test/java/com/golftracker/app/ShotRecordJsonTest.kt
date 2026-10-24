package com.golftracker.app

import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.tracker.LaunchPhysics
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShotRecordJsonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun golferAndLandingEstimateAreKept() {
        val landing = LaunchPhysics.Landing(GpsCoordinate(40.55, -80.0), 45.0, 30.0, 34.0, 15.0, 35.0)
        val shot = ShotRecord(id = "s", golfer = 2, estimatedLanding = landing)
        val back = json.decodeFromString(ShotRecord.serializer(), json.encodeToString(ShotRecord.serializer(), shot))
        assertEquals(2, back.golfer)
        assertEquals(landing, back.estimatedLanding)
    }

    @Test
    fun shotsSavedBeforeGolfersStillLoad() {
        val old = json.decodeFromString(ShotRecord.serializer(), """{"id":"old","timestampMs":1,"club":"PW"}""")
        assertEquals("PW", old.club)
        assertNull(old.golfer)
        assertNull(old.estimatedLanding)
    }
}
