package com.golftracker.app

import com.golftracker.app.tracker.FrameRateLimiter
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateLimiterTest {
    @Test
    fun keepsTheFirstFrameAndThenOneEveryInterval() {
        val limiter = FrameRateLimiter(28_000_000L)
        // A 60 fps camera on the boot clock, hours after boot.
        val start = 132_045_545_000_000L
        val kept = (0 until 60).map { start + it * 16_666_667L }.filter { limiter.keep(it) }
        assertEquals(start, kept.first())
        assertEquals(30, kept.size)
    }
}
