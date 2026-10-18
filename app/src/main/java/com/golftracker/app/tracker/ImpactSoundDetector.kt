package com.golftracker.app.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.log10
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
class OnsetDetector(private val sampleRate: Int) {

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

    private companion object {
        /** Quieter than this (out of 32768) is silence, whatever the background. */
        const val MIN_RMS = 6f
        /** How far above the background a strike is: 16 dB. */
        const val RATIO = 6f
        /** How much louder than the window just before. */
        const val RISE = 3f
        const val REFRACTORY_S = 0.3
    }
}

/**
 * Listens on the microphone for club strikes and reports when they happened, on the
 * [SystemClock.elapsedRealtimeNanos] clock the camera frames are also put on.
 */
class ImpactSoundDetector(private val context: Context, private val onImpact: (Long) -> Unit) {

    @Volatile
    private var running = false

    /** How loud it is right now: the loudest sample of the last read, in dB below full scale (0 is the loudest possible). */
    @Volatile
    var levelDb = SILENCE_DB
        private set

    /** How far above the background the last strike heard stood out, in dB. */
    @Volatile
    var lastStrikeDb: Float? = null
        private set
    private var thread: Thread? = null

    /** Starts listening; false if there's no microphone permission or it couldn't be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val record = open() ?: return false
        running = true
        thread = Thread({ listen(record) }, "impact-sound").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        levelDb = SILENCE_DB
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        // Unprocessed sound keeps the strike's sharp edge; voice processing smooths and gates it.
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val source = if (audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        for (rate in intArrayOf(48_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            try {
                val record = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, rate / 5 * 2))
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    private fun listen(record: AudioRecord) {
        val rate = record.sampleRate
        val onsets = OnsetDetector(rate)
        val buffer = ShortArray(rate / 100)
        val stamp = AudioTimestamp()
        var read = 0L
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                var peak = 1
                for (i in 0 until n) peak = max(peak, abs(buffer[i].toInt()))
                levelDb = (20 * log10(peak / 32768.0)).toFloat()
                for (onset in onsets.feed(buffer, n, read)) {
                    lastStrikeDb = (20 * log10(onset.ratio.toDouble())).toFloat()
                    val t = if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        stamp.nanoTime + (onset.sample - stamp.framePosition) * 1_000_000_000L / rate
                    } else {
                        // Without a timestamp, assume the last sample read was just captured.
                        SystemClock.elapsedRealtimeNanos() - (read + n - onset.sample) * 1_000_000_000L / rate
                    }
                    onImpact(t)
                }
                read += n
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    companion object {
        const val SILENCE_DB = -90f
    }
}
