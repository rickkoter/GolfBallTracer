package com.golftracker.app.tracker

/**
 * Whether a shot was recorded over the whole time its flight needs: the camera frames the detector
 * looked at, and the video it's traced from again afterwards. When part is missing (the video started
 * late, frames were dropped while the detector was behind), the ball flight drawn may be wrong or
 * missing, and it's worth asking whether the shot should be kept.
 */
object RecordingCoverage {

    /** The ball is seen sitting still on the tee for this long before the strike. */
    private const val NEEDS_BEFORE_NS = 500_000_000L
    /** The part of the flight that matters most: the launch, and the climb after it. */
    private const val NEEDS_AFTER_NS = 1_500_000_000L
    /** A break longer than this between frames lost part of the flight. */
    private const val MAX_GAP_NS = 250_000_000L

    /**
     * What's missing, in words for the user; empty when the recording covers the shot.
     * [frameTimesNs] are the camera frames looked at, in order; [videoStartNs] is when the video began
     * (null without one), and [videoExpected] whether there should have been a video at all.
     */
    fun problems(
        impactNs: Long?,
        frameTimesNs: List<Long>,
        videoStartNs: Long?,
        videoExpected: Boolean
    ): List<String> {
        impactNs ?: return emptyList()
        val found = ArrayList<String>()

        if (frameTimesNs.isEmpty()) {
            found += "The camera's frames weren't recorded"
        } else {
            val first = frameTimesNs.first()
            val last = frameTimesNs.last()
            if (first > impactNs - NEEDS_BEFORE_NS) {
                found += if (first > impactNs) {
                    "The camera's frames started ${((first - impactNs) / 1e9).fixed(1)} s after the strike"
                } else {
                    "The camera's frames started only ${((impactNs - first) / 1e9).fixed(1)} s before the strike"
                }
            }
            // Breaks in the frames over the launch and the start of the flight.
            val from = impactNs - NEEDS_BEFORE_NS
            val to = minOf(last, impactNs + NEEDS_AFTER_NS)
            var missing = 0L
            var at: Long? = null
            for (i in 1 until frameTimesNs.size) {
                val a = frameTimesNs[i - 1]
                val b = frameTimesNs[i]
                if (b < from || a > to) continue
                val gap = b - a
                if (gap > MAX_GAP_NS) {
                    missing += gap
                    if (at == null) at = a
                }
            }
            if (at != null) {
                found += "${(missing / 1e9).fixed(1)} s of camera frames were missed, from ${((at - impactNs) / 1e9).signed(1)} s after the strike"
            }
        }

        if (videoExpected) {
            when {
                videoStartNs == null -> found += "No video was saved for this shot"
                videoStartNs > impactNs -> found += "The video started ${((videoStartNs - impactNs) / 1e9).fixed(1)} s after the strike"
                videoStartNs > impactNs - NEEDS_BEFORE_NS ->
                    found += "The video started only ${((impactNs - videoStartNs) / 1e9).fixed(1)} s before the strike"
            }
        }
        return found
    }
}
