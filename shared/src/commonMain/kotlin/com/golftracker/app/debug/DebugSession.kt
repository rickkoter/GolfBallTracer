package com.golftracker.app.debug

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A debug recording: everything the app saw and heard for a while, whether or not a shot was found,
 * for working out later why a shot was missed and for tuning how strikes are heard.
 *
 *   session.json – timing, and what the detector did ([Info.events])
 *   audio.wav    – the microphone, 16-bit mono
 *   frames.bin   – every analysis frame, as in ShotDebugArchive: count, then per frame timestamp, width, height, pixels
 *   video.mp4    – the camera's video
 *
 * All timestamps are on the clock the camera frames are on (Android: elapsedRealtimeNanos; iOS: the host clock).
 */
object DebugSession {

    const val INFO = "session.json"
    const val AUDIO = "audio.wav"
    const val FRAMES = "frames.bin"
    const val VIDEO = "video.mp4"

    @Serializable
    data class Event(val timestampNs: Long, val kind: String, val detail: String = "")

    @Serializable
    data class Info(
        val version: Int = 1,
        val device: String,
        val startedNs: Long,
        val endedNs: Long,
        val frameCount: Int,
        /** Frames that couldn't be written in time and are missing. */
        val framesDropped: Int,
        val audioSampleRate: Int? = null,
        /** When the first audio sample was captured. */
        val audioStartNs: Long? = null,
        val audioSamples: Long = 0,
        /** Roughly when the video's first frame was taken. */
        val videoStartNs: Long? = null,
        val micEnabled: Boolean,
        val events: List<Event> = emptyList(),
        /** The camera's field of view across the screen's height and its orientation (phone to east/north/up), if known. */
        val verticalFovRadians: Double? = null,
        val deviceToWorld: List<Float>? = null
    )

    val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** A 44-byte WAV header for [dataBytes] of 16-bit mono sound. */
    fun wavHeader(sampleRate: Int, dataBytes: Long): ByteArray {
        val out = ByteArray(44)
        var p = 0
        fun text(s: String) { s.encodeToByteArray().copyInto(out, p); p += s.length }
        fun int(v: Int) { for (k in 0 until 4) out[p++] = (v ushr (8 * k)).toByte() }
        fun short(v: Int) { out[p++] = v.toByte(); out[p++] = (v ushr 8).toByte() }
        text("RIFF"); int((36 + dataBytes).toInt()); text("WAVE")
        text("fmt "); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
        text("data"); int(dataBytes.toInt())
        return out
    }
}
