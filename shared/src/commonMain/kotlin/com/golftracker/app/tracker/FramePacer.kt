package com.golftracker.app.tracker

import kotlin.math.min

/**
 * Picks which camera frames the shot detector looks at: as many as it keeps up with, up to [maxFps].
 *
 * More frames see more of the ball's first moments, when it moves fastest. The tracking was checked at
 * 30 and 60 frames a second (live phone frames, and range sessions decoded from 60 fps video). A phone
 * that can't keep up at the full rate falls behind; once it's more than [behindSeconds] behind, it drops
 * to [fallbackFps] (every other frame of a 60 fps camera). It tries the full rate again after keeping up for
 * a while, waiting twice as long each time that fails, so a slow phone settles at the lower rate.
 */
class FramePacer(
    private val maxFps: Double = 60.0,
    private val fallbackFps: Double = 30.0,
    private val behindSeconds: Double = 1.0,
    private val retrySeconds: Double = 10.0
) {
    /** The rate frames are let through at now. */
    var fps: Double = maxFps
        private set

    private var lastKeptNs: Long? = null
    private var keptUpSinceNs: Long? = null
    private var retryNs = seconds(retrySeconds)

    /** Whether to look at the frame taken at [timestampNs], with [queuedFrames] still waiting for the detector. */
    fun keep(timestampNs: Long, queuedFrames: Int): Boolean {
        if (fps > fallbackFps && queuedFrames > behindSeconds * fps) {
            fps = fallbackFps
            keptUpSinceNs = null
        } else if (fps < maxFps) {
            if (queuedFrames > 1) {
                keptUpSinceNs = null
            } else {
                val since = keptUpSinceNs ?: timestampNs.also { keptUpSinceNs = it }
                if (timestampNs - since >= retryNs) {
                    fps = maxFps
                    keptUpSinceNs = null
                    // If it falls behind again, the next try waits longer.
                    retryNs = min(retryNs * 2, seconds(MAX_RETRY_S))
                }
            }
        }
        // Frames a little early still count (camera timing jitters): a 60 fps camera at 30 keeps every other one.
        val last = lastKeptNs
        if (last != null && timestampNs - last < 0.8 * 1e9 / fps) return false
        lastKeptNs = timestampNs
        return true
    }

    private fun seconds(s: Double) = (s * 1e9).toLong()

    private companion object {
        const val MAX_RETRY_S = 160.0
    }
}
