package com.golftracker.app.debug

import com.golftracker.app.tracker.LumaFrame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * A debug recording: everything the app saw and heard for a while, whether or not a shot was found,
 * for working out later why a shot was missed and for tuning how strikes are heard.
 *
 *   session.json – timing, and what the detector did ([Info.events])
 *   audio.wav    – the microphone, 16-bit mono
 *   frames.bin   – every analysis frame, as in ShotDebugArchive: count, then per frame timestamp, width, height, pixels
 *   video.mp4    – the camera's video
 *
 * All timestamps are on the [android.os.SystemClock.elapsedRealtimeNanos] clock, like the frames'.
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
    fun writeWavHeader(out: OutputStream, sampleRate: Int, dataBytes: Long) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        out.write(b.array())
    }

    /** A recording read back: its info and sound. Frames are handed to [onFrame] as they're read, so they needn't all fit in memory. */
    class Loaded(val info: Info, val audio: ShortArray?)

    fun read(input: InputStream, onFrame: (LumaFrame) -> Unit = {}): Loaded {
        var info: Info? = null
        var audio: ShortArray? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    INFO -> info = json.decodeFromString(Info.serializer(), zip.readBytes().decodeToString())
                    AUDIO -> {
                        val bytes = zip.readBytes()
                        val samples = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        audio = ShortArray(samples.remaining()).also { samples.get(it) }
                    }
                    FRAMES -> {
                        val data = DataInputStream(zip)
                        repeat(data.readInt()) {
                            val ts = data.readLong()
                            val w = data.readInt()
                            val h = data.readInt()
                            val bytes = ByteArray(w * h)
                            data.readFully(bytes)
                            onFrame(LumaFrame(w, h, bytes, ts))
                        }
                    }
                }
            }
        }
        return Loaded(requireNotNull(info) { "$INFO missing" }, audio)
    }
}
