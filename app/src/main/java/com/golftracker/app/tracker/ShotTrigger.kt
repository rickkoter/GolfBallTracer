package com.golftracker.app.tracker

import kotlin.math.max

/**
 * Notices shots on its own: no need to point out the ball first.
 *
 * While listening, the last few seconds of frames are kept. A shot is the sound of a strike together with
 * a ball vanishing, at that moment, from where it sat still ([ImpactBallLocator]). The sound alone could be
 * anything (the next bay, a clap) and so could the motion (a hand picking up a ball, a shoe), but the two
 * together are a shot. Then a [ShotCaptureSession] is started on that ball from just before the strike,
 * fed the kept frames and then the live ones until the flight has had time to play out.
 *
 * With [watchWithoutSound] (when there's no microphone), bursts of motion are checked instead every so
 * often. That finds unheard shots, but also more things that aren't shots.
 *
 * Not thread-safe: call everything from one thread (see [AutoShotDetector]).
 */
class ShotTrigger(
    private val captureSeconds: Double = 5.0,
    private val keepFrames: Boolean = true,
    var watchWithoutSound: Boolean = false,
    private val onEvent: (Event) -> Unit
) {
    sealed interface Event {
        /**
         * A shot was spotted and is being captured. [stillShiftX]/[stillShiftY] are how far the camera has
         * moved since the capture's first frame, as screen fractions, for drawing over what it shows now.
         */
        data class Started(
            val ball: TeeBall, val impactNs: Long, val heard: Boolean,
            val stillShiftX: Float, val stillShiftY: Float
        ) : Event

        /** The capture is over; the shot is ready for [BallFlightFitter]. */
        data class Captured(val shot: CapturedShot) : Event

        /**
         * A strike was heard but no ball was seen leaving. [duringSwing] says whether anything moved
         * around then; if so, [frames] are the frames around it, for working out later what went wrong.
         */
        class Missed(val impactNs: Long, val duringSwing: Boolean, val frames: List<LumaFrame>) : Event
    }

    private class Buffered(val frame: LumaFrame, val thumb: LumaFrame, val motion: Float)

    private class Capture(
        val session: ShotCaptureSession,
        val impactNs: Long,
        val heard: Boolean,
        val endNs: Long,
        val changed: LumaFrame?,
        val thumbs: ArrayList<LumaFrame> = ArrayList()
    )

    /** Whether frames are wanted: while listening for a shot or capturing one. */
    @Volatile
    var wantsFrames = false
        private set

    /** When the last swing-sized motion was seen, so a recording can be restarted while nothing is going on. */
    @Volatile
    var lastActivityNs = 0L
        private set

    private var listening = false
    private var listenSinceNs = Long.MAX_VALUE
    private val buffer = ArrayDeque<Buffered>()
    private val sounds = ArrayDeque<Long>()
    private var lastLookNs = 0L
    private var capture: Capture? = null

    fun startListening() {
        if (listening || capture != null) return
        listening = true
        wantsFrames = true
        listenSinceNs = Long.MAX_VALUE
        lastLookNs = 0L
        buffer.clear()
        sounds.clear()
    }

    /** Stops listening, abandoning any shot being captured. */
    fun stopListening() {
        listening = false
        capture?.session?.reset()
        capture = null
        wantsFrames = false
        buffer.clear()
        sounds.clear()
    }

    /** Ends the capture under way now rather than waiting out the flight. */
    fun finishCapture() {
        if (capture != null) finish()
    }

    fun onImpactSound(timestampNs: Long) {
        if (!listening || capture != null) return
        if (sounds.lastOrNull()?.let { timestampNs - it < MIN_SOUND_GAP_NS } == true) return
        sounds.addLast(timestampNs)
    }

    fun onFrame(frame: LumaFrame) {
        capture?.let { c ->
            feed(c, frame, Thumbnails.of(frame))
            if (frame.timestampNs >= c.endNs) finish()
            return
        }
        if (!listening) return
        if (listenSinceNs == Long.MAX_VALUE) listenSinceNs = frame.timestampNs

        val thumb = Thumbnails.of(frame)
        val motion = buffer.lastOrNull()?.let { Thumbnails.motion(it.thumb, thumb) } ?: 0f
        if (buffer.isNotEmpty() && (buffer.last().frame.width != frame.width || buffer.last().frame.height != frame.height)) buffer.clear()
        buffer.addLast(Buffered(frame, thumb, motion))
        while (buffer.first().frame.timestampNs < frame.timestampNs - BUFFER_NS) buffer.removeFirst()
        if (motion >= swingMotion()) lastActivityNs = frame.timestampNs

        // Heard impacts first: they give the exact moment. A strike plus a ball gone from where it sat is
        // enough, even if the swing was lost in a shaking hand.
        while (sounds.isNotEmpty() && frame.timestampNs >= sounds.first() + secondsNs(ImpactBallLocator.NEEDS_AFTER_S) + CLOCK_SLACK_NS) {
            val t = sounds.removeFirst()
            if (t < listenSinceNs) continue
            val frames = buffer.map { it.frame }
            // The sound's clock and the camera's may disagree a little; look either side if need be.
            val found = listOf(0L, CLOCK_SLACK_NS, -CLOCK_SLACK_NS).firstNotNullOfOrNull { ImpactBallLocator.locate(frames, t + it, heardNs = t) }
            if (found != null) {
                start(found, t, heard = true)
                return
            }
            val swing = swingAround(t)
            onEvent(Event.Missed(t, swing, if (swing) frames else emptyList()))
        }

        // Without a microphone, every so often a look for a strike during a burst of motion.
        if (watchWithoutSound && frame.timestampNs - lastLookNs >= LOOK_INTERVAL_NS) {
            lastLookNs = frame.timestampNs
            val t = frame.timestampNs - secondsNs(ImpactBallLocator.NEEDS_AFTER_S + 0.05)
            val heardSoon = sounds.any { it in t - LOOK_INTERVAL_NS * 2..frame.timestampNs + LOOK_INTERVAL_NS }
            if (!heardSoon && t - secondsNs(ImpactBallLocator.FEED_BEFORE_S) >= listenSinceNs && swingAround(t)) {
                ImpactBallLocator.locate(buffer.map { it.frame }, t)?.let { start(it, it.vanishedNs, heard = false) }
            }
        }
    }

    private fun start(found: ImpactBallLocator.Found, impactNs: Long, heard: Boolean) {
        val frames = buffer.toList()
        val ref = frames[found.referenceIndex].frame
        val latest = frames.last().frame
        val (sx, sy) = FrameShift.estimate(ref, latest)
        val session = ShotCaptureSession(keepFrames = keepFrames)
        session.arm(found.ball.x, found.ball.y, found.ball, launchHintNs = if (heard) impactNs else found.vanishedNs)
        val c = Capture(session, impactNs, heard, impactNs + secondsNs(captureSeconds), found.changed)
        capture = c
        buffer.clear()
        sounds.clear()
        onEvent(Event.Started(found.ball, impactNs, heard, sx.toFloat() / ref.width, sy.toFloat() / ref.height))
        for (i in found.referenceIndex until frames.size) feed(c, frames[i].frame, frames[i].thumb)
        if (latest.timestampNs >= c.endNs) finish()
    }

    private fun feed(c: Capture, frame: LumaFrame, thumb: LumaFrame) {
        c.session.process(frame)
        c.thumbs += thumb
    }

    private fun finish() {
        val c = capture ?: return
        capture = null
        listening = false
        wantsFrames = false
        val shot = c.session.stop().copy(impactTimestampNs = c.impactNs, heardImpact = c.heard, thumbnails = c.thumbs, changedArea = c.changed)
        onEvent(Event.Captured(shot))
    }

    /** How much a thumbnail has to change from one frame to the next to count as a swing rather than the usual flicker. */
    private fun swingMotion(): Float {
        if (buffer.size < 5) return MIN_SWING_MOTION
        val sorted = buffer.map { it.motion }.sorted()
        return max(MIN_SWING_MOTION, SWING_OVER_USUAL * sorted[sorted.size / 2])
    }

    private fun swingAround(t: Long): Boolean {
        val threshold = swingMotion()
        return buffer.any { it.frame.timestampNs in t - SWING_BEFORE_NS..t + SWING_AFTER_NS && it.motion >= threshold }
    }

    private fun secondsNs(s: Double) = (s * 1e9).toLong()

    companion object {
        private const val BUFFER_NS = 3_000_000_000L
        private const val LOOK_INTERVAL_NS = 250_000_000L
        private const val MIN_SOUND_GAP_NS = 300_000_000L
        private const val CLOCK_SLACK_NS = 100_000_000L
        private const val SWING_BEFORE_NS = 500_000_000L
        private const val SWING_AFTER_NS = 250_000_000L
        /** Grey levels of mean change per thumbnail pixel between frames. */
        private const val MIN_SWING_MOTION = 0.5f
        /** A swing changes the picture this many times more than the usual flicker and sway. */
        private const val SWING_OVER_USUAL = 2f
    }
}
