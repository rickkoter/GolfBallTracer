package com.golftracker.app.tracker

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds sudden sharp sounds, like a club striking a ball, in a stream of samples.
 *
 * The sound is cut into windows a few milliseconds long. A strike is a window far louder than the
 * background level, coming straight out of a quiet one: the crack of impact rises within a millisecond
 * or two, while voices, wind and engines swell. The background follows the quiet between sounds,
 * dropping quickly and rising slowly so a strike doesn't raise it.
 */
class OnsetDetector(
    private val sampleRate: Int,
    /**
     * For working out thresholds offline: called for every window with where it starts, how loud it
     * was, the background level and the window before it.
     */
    private val onWindow: ((sample: Long, rms: Float, background: Float, previous: Float) -> Unit)? = null
) {

    /** Where a strike starts, as a sample count since the stream began, and how far above the background it was. */
    data class Onset(val sample: Long, val ratio: Float)

    private val window = max(16, sampleRate / 400)
    private var background = -1f
    private var previous = 0f
    private var lastOnset = Long.MIN_VALUE / 2
    private var sumSquares = 0.0
    private var inWindow = 0
    private var windowStart = 0L

    fun feed(samples: ShortArray, count: Int, firstSample: Long): List<Onset> {
        var found: MutableList<Onset>? = null
        for (i in 0 until count) {
            if (inWindow == 0) windowStart = firstSample + i
            val v = samples[i].toDouble()
            sumSquares += v * v
            inWindow++
            if (inWindow < window) continue
            val rms = sqrt(sumSquares / window).toFloat()
            sumSquares = 0.0
            inWindow = 0

            if (background < 0) background = rms
            onWindow?.invoke(windowStart, rms, background, previous)
            val loud = rms >= MIN_RMS && rms >= RATIO * max(background, 1f)
            val sudden = rms >= RISE * max(previous, 1f)
            if (loud && sudden && windowStart - lastOnset >= sampleRate * REFRACTORY_S) {
                lastOnset = windowStart
                (found ?: ArrayList<Onset>().also { found = it }) += Onset(windowStart, rms / max(background, 1f))
            }
            background = if (rms < background) 0.8f * background + 0.2f * rms else 0.995f * background + 0.005f * rms
            previous = rms
        }
        return found ?: emptyList()
    }

    companion object {
        /** Quieter than this (out of 32768) is silence, whatever the background. */
        const val MIN_RMS = 6f
        /**
         * How far above the background a strike is: 25 dB. Real strikes recorded at a range (driver, phone
         * a few metres away) were 53–56 dB above it; voices and the range's noise stayed under 30.
         */
        const val RATIO = 18f
        /** How much louder than the moment just before: 12 dB. Those strikes rose 21–42 dB at once. */
        const val RISE = 4f
        const val REFRACTORY_S = 0.3
    }
}
