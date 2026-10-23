package com.golftracker.app

import com.golftracker.app.tracker.RecordingCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCoverageTest {

    private val impact = 10_000_000_000L
    private fun frames(fromS: Double, toS: Double, skip: ClosedFloatingPointRange<Double>? = null) =
        generateSequence(fromS) { it + 1.0 / 30 }.takeWhile { it <= toS }
            .filter { skip == null || it !in skip }
            .map { impact + (it * 1e9).toLong() }.toList()

    @Test
    fun aWholeRecordingHasNoProblems() {
        assertEquals(emptyList<String>(), RecordingCoverage.problems(impact, frames(-0.85, 4.0), impact - 2_500_000_000L, true))
    }

    @Test
    fun aVideoStartedAfterTheStrikeIsReported() {
        // As on 2026-10-23: the video for the next shot began 0.9 s after its strike.
        val problems = RecordingCoverage.problems(impact, frames(-0.85, 4.0), impact + 860_000_000L, true)
        assertEquals(listOf("The video started 0.9 s after the strike"), problems)
    }

    @Test
    fun framesDroppedDuringTheFlightAreReported() {
        val problems = RecordingCoverage.problems(impact, frames(-0.85, 4.0, skip = 0.2..0.8), null, false)
        assertEquals(1, problems.size)
        assertTrue(problems.single(), problems.single().startsWith("0.6 s of camera frames were missed"))
    }

    @Test
    fun framesStartingTooLateAreReported() {
        val problems = RecordingCoverage.problems(impact, frames(-0.2, 4.0), impact - 2_500_000_000L, true)
        assertEquals(listOf("The camera's frames started only 0.2 s before the strike"), problems)
    }

    @Test
    fun aMissingVideoMattersOnlyWhenOneWasExpected() {
        assertEquals(listOf("No video was saved for this shot"), RecordingCoverage.problems(impact, frames(-0.85, 4.0), null, true))
        assertEquals(emptyList<String>(), RecordingCoverage.problems(impact, frames(-0.85, 4.0), null, false))
    }

    @Test
    fun aGapAfterTheFlightDoesntMatter() {
        assertEquals(emptyList<String>(), RecordingCoverage.problems(impact, frames(-0.85, 4.0, skip = 2.5..3.5), null, false))
    }
}
