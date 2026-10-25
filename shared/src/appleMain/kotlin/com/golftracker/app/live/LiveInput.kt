@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.golftracker.app.live

import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.replay.ShotArchive
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.LumaSampler
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.ShotTrigger
import com.golftracker.app.tracker.OnsetDetector
import com.golftracker.app.replay.toNSData
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import platform.Foundation.NSData
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns the iPhone camera's frames into [LumaFrame]s for the shot detector: the luminance plane
 * (plane 0 of a 420 bi-planar pixel buffer) rotated upright, cropped to what the preview shows and
 * downsampled, as the Android app does. Pixels are read straight from the camera's buffer, in one copy.
 */
class CameraFrameSampler(targetLongSide: Int = 640) {
    private val sampler = LumaSampler(targetLongSide)

    /**
     * [rotation] is how far the buffer turns clockwise to stand upright (90 for the back camera held
     * upright); [uprightAspect] is the preview's width / height.
     */
    fun sample(
        plane: CPointer<UByteVar>, width: Int, height: Int, bytesPerRow: Int,
        rotation: Int, timestampNs: Long, uprightAspect: Float
    ): LumaFrame {
        val g = sampler.layout(0, 0, width, height, rotation, bytesPerRow, 1, uprightAspect)
        val data = ByteArray(g.gridWidth * g.gridHeight)
        val limit = height * bytesPerRow
        var i = 0
        for (gy in 0 until g.gridHeight) {
            val row = g.rowOffset[gy]
            for (gx in 0 until g.gridWidth) {
                val idx = row + g.colOffset[gx]
                data[i++] = if (idx < limit) plane[idx].toByte() else 0
            }
        }
        return LumaFrame(g.gridWidth, g.gridHeight, data, timestampNs)
    }
}

/** A club strike heard on the microphone: when (on the host clock, as camera frames are) and how loud. */
class Strike(val timestampNs: Long, val decibels: Float)

/**
 * Listens for club strikes in the microphone's samples (floats, as iOS gives them), with the same
 * [OnsetDetector] and thresholds as the Android app.
 */
class StrikeListener(private val sampleRate: Int) {
    private val onsets = OnsetDetector(sampleRate)
    private var shorts = ShortArray(0)
    private var fed = 0L

    /** The loudest sample in the last buffer, in dB below full scale. */
    var levelDb: Float = SILENCE_DB
        private set

    /** Feeds [count] samples whose first was captured at [firstSampleNs]; returns the strikes in them. */
    fun feed(samples: CPointer<FloatVar>, count: Int, firstSampleNs: Long): List<Strike> {
        if (shorts.size < count) shorts = ShortArray(count)
        var peak = 0f
        for (i in 0 until count) {
            val v = samples[i]
            peak = max(peak, abs(v))
            shorts[i] = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        levelDb = if (peak > 0f) (20 * log10(peak.toDouble())).toFloat() else SILENCE_DB
        val first = fed
        fed += count
        return onsets.feed(shorts, count, first).map { onset ->
            Strike(
                timestampNs = firstSampleNs + (onset.sample - first) * 1_000_000_000L / sampleRate,
                decibels = (20 * log10(onset.ratio.toDouble())).toFloat()
            )
        }
    }

    companion object {
        const val SILENCE_DB = -90f
    }
}

/**
 * The shared [ShotTrigger] for the iOS app, which calls it from one serial queue. A Kotlin exception
 * thrown into Swift would end the app, so each call catches its own (as the Android detector thread does)
 * and the last one is kept in [lastProblem].
 */
class LiveShotTrigger(onEvent: (ShotTrigger.Event) -> Unit) {
    private val trigger = ShotTrigger(captureSeconds = 3.0, keepFrames = true, onEvent = onEvent)

    /** Whether frames are wanted (listening or capturing); safe to read from any thread. */
    val wantsFrames: Boolean get() = trigger.wantsFrames

    val isCapturing: Boolean get() = trigger.isCapturing

    /** When swing-sized motion was last seen, on the frames' clock. */
    val lastActivityNs: Long get() = trigger.lastActivityNs

    /** Whether to look for shots in bursts of motion too, for when there's no microphone to hear them. */
    var watchWithoutSound: Boolean
        get() = trigger.watchWithoutSound
        set(value) { trigger.watchWithoutSound = value }

    var lastProblem: String? = null
        private set

    fun onFrame(frame: LumaFrame) = guard { trigger.onFrame(frame) }
    fun onImpactSound(timestampNs: Long) = guard { trigger.onImpactSound(timestampNs) }
    fun startListening() = guard { trigger.startListening() }
    fun stopListening() = guard { trigger.stopListening() }
    fun finishCapture() = guard { trigger.finishCapture() }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            lastProblem = e.toString()
            println("ShotTrigger: $e")
        }
    }
}

/** The ball's flight in a captured shot, as the Android app fits it; null if none (or on any error). */
fun fitShot(shot: CapturedShot, camera: LaunchPhysics.CameraPose?): BallFlightFitter.Flight? =
    try {
        BallFlightFitter.fitBest(shot, camera)
    } catch (e: Throwable) {
        println("BallFlightFitter: $e")
        null
    }

/**
 * A captured shot saved as a recording (the Android app's shot-debug zip), ready to write to a file:
 * it replays in either app and in the replay tests. Null if it couldn't be made.
 */
fun encodeShotRecording(shot: CapturedShot, flight: BallFlightFitter.Flight?, device: String, club: String?, golfer: Int): NSData? =
    try {
        ShotArchive.encode(shot, flight?.tracer ?: emptyList<ScreenPoint>(), device, club, golfer).toNSData()
    } catch (e: Throwable) {
        println("ShotArchive: $e")
        null
    }

/** For Swift: the camera's pose from its vertical field of view and a row-major phone-to-east/north/up rotation. */
fun cameraPose(verticalFovRadians: Double, m0: Float, m1: Float, m2: Float, m3: Float, m4: Float, m5: Float, m6: Float, m7: Float, m8: Float) =
    LaunchPhysics.CameraPose(verticalFovRadians, floatArrayOf(m0, m1, m2, m3, m4, m5, m6, m7, m8))
