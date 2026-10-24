package com.golftracker.app

import com.golftracker.app.tracker.FramePacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    // A camera on the boot clock, hours after boot.
    private val start = 132_045_545_000_000L

    private fun frames(fps: Double, seconds: Double, from: Long = start) =
        (0 until (fps * seconds).toInt()).map { from + (it * 1e9 / fps).toLong() }

    @Test
    fun keepsEveryFrameUpTo60fps() {
        val p60 = FramePacer()
        assertEquals(60, frames(60.0, 1.0).count { p60.keep(it, 0) })
        val p30 = FramePacer()
        assertEquals(30, frames(30.0, 1.0).count { p30.keep(it, 0) })
    }

    @Test
    fun aFasterCameraIsCappedAt60() {
        val p = FramePacer()
        assertEquals(60, frames(120.0, 1.0).count { p.keep(it, 0) })
    }

    @Test
    fun fallingBehindDropsTo30AndRetriesLater() {
        val p = FramePacer()
        val camera = frames(60.0, 40.0)
        var queued = 0
        val kept = ArrayList<Long>()
        for (t in camera) {
            // Behind for the first 2 s (the queue grows), then keeping up.
            queued = if (t - start < 2_000_000_000L) queued + 1 else 0
            if (p.keep(t, queued)) kept += t
        }
        val firstSecond = kept.count { it - start < 1_000_000_000L }
        val third = kept.count { it - start in 3_000_000_000L until 4_000_000_000L }
        val last = kept.count { it - start >= 39_000_000_000L }
        assertEquals("full rate until a second behind", 60, firstSecond)
        assertEquals("every other frame once behind", 30, third)
        assertEquals("full rate again after keeping up", 60, last)
        assertEquals(60.0, p.fps, 0.0)
    }

    @Test
    fun aPhoneThatKeepsFallingBehindWaitsLongerEachTry() {
        val p = FramePacer()
        val fullRateAt = ArrayList<Long>()
        var queued = 0
        var wasFull = true
        for (t in frames(60.0, 300.0)) {
            // Too slow at 60 (one more frame waiting each kept frame), fine at 30.
            val full = p.fps > 30
            if (full && !wasFull) fullRateAt += t
            wasFull = full
            if (p.keep(t, queued)) queued = if (p.fps > 30) queued + 1 else 0
        }
        val gaps = fullRateAt.zipWithNext { a, b -> (b - a) / 1e9 }
        assertTrue("retries: $fullRateAt", fullRateAt.size >= 3)
        assertTrue("each retry waits longer: $gaps", gaps.zipWithNext().all { (a, b) -> b > a })
    }
}
