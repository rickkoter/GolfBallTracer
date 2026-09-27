package com.golftracker.app

import com.golftracker.app.export.ShotHistoryCsv
import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.model.ShotRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class ShotHistoryCsvTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun oneRowPerShotOldestFirst() {
        val later = ShotRecord(id = "b", timestampMs = 1_790_000_000_000, club = "7-Iron", distanceYards = 152.34, distanceMeters = 139.3,
            launchLocation = GpsCoordinate(40.1234567, -75.7654321), tracerStyle = "FIRE_RED")
        val earlier = ShotRecord(id = "a", timestampMs = 1_789_000_000_000, club = "Driver", tracerStyle = "PRO_LIME")
        val lines = ShotHistoryCsv.build(listOf(later, earlier), utc).trimEnd().split("\r\n")

        assertEquals(3, lines.size)
        assertEquals("Date,Time,Club,Distance (yd),Distance (m),Tee latitude,Tee longitude,Landing latitude,Landing longitude,Tracer style,Notes,Shot ID", lines[0])
        assertEquals("2026-09-10,00:26:40,Driver,,,,,,,Pro Lime,,a", lines[1])
        assertEquals("2026-09-21,14:13:20,7-Iron,152.3,139.3,40.123457,-75.765432,,,Fire Red,,b", lines[2])
    }

    @Test
    fun quotesNotesWithCommasQuotesAndLineBreaks() {
        val shot = ShotRecord(id = "c", timestampMs = 0, notes = "Pushed it, \"thin\"\nwindy")
        val csv = ShotHistoryCsv.build(listOf(shot), utc)
        assertEquals(true, csv.contains(",\"Pushed it, \"\"thin\"\"\nwindy\",c\r\n"))
    }
}
