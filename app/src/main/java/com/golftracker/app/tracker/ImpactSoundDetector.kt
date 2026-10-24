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

/**
 * Listens on the microphone for club strikes and reports when they happened, on the
 * [SystemClock.elapsedRealtimeNanos] clock the camera frames are also put on.
 */
class ImpactSoundDetector(
    private val context: Context,
    /** When a strike was heard, and how far above the background it stood out in dB. */
    private val onImpact: (timestampNs: Long, strengthDb: Float) -> Unit
) {

    @Volatile
    private var running = false

    /** How loud it is right now: the loudest sample of the last read, in dB below full scale (0 is the loudest possible). */
    @Volatile
    var levelDb = SILENCE_DB
        private set

    /**
     * Gets every buffer of sound as it's read, with when its first sample was captured and the sample
     * rate, for a debug recording.
     */
    @Volatile
    var audioTap: ((samples: ShortArray, count: Int, firstSampleNs: Long, sampleRate: Int) -> Unit)? = null

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
                audioTap?.let { tap ->
                    val first = if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        stamp.nanoTime + (read - stamp.framePosition) * 1_000_000_000L / rate
                    } else {
                        SystemClock.elapsedRealtimeNanos() - n * 1_000_000_000L / rate
                    }
                    tap(buffer, n, first, rate)
                }
                var peak = 1
                for (i in 0 until n) peak = max(peak, abs(buffer[i].toInt()))
                levelDb = (20 * log10(peak / 32768.0)).toFloat()
                for (onset in onsets.feed(buffer, n, read)) {
                    val strength = (20 * log10(onset.ratio.toDouble())).toFloat()
                    lastStrikeDb = strength
                    val t = if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        stamp.nanoTime + (onset.sample - stamp.framePosition) * 1_000_000_000L / rate
                    } else {
                        // Without a timestamp, assume the last sample read was just captured.
                        SystemClock.elapsedRealtimeNanos() - (read + n - onset.sample) * 1_000_000_000L / rate
                    }
                    onImpact(t, strength)
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
