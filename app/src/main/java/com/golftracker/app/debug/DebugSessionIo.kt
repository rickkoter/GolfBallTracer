package com.golftracker.app.debug

import com.golftracker.app.tracker.LumaFrame
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Writes a 44-byte WAV header for [dataBytes] of 16-bit mono sound. */
fun DebugSession.writeWavHeader(out: OutputStream, sampleRate: Int, dataBytes: Long) = out.write(wavHeader(sampleRate, dataBytes))

/** A debug recording read back: its info and sound. */
class LoadedDebugSession(val info: DebugSession.Info, val audio: ShortArray?)

/** Reads a debug recording. Frames are handed to [onFrame] as they're read, so they needn't all fit in memory. */
fun DebugSession.read(input: InputStream, onFrame: (LumaFrame) -> Unit = {}): LoadedDebugSession {
    var info: DebugSession.Info? = null
    var audio: ShortArray? = null
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            when (entry.name) {
                DebugSession.INFO -> info = json.decodeFromString(DebugSession.Info.serializer(), zip.readBytes().decodeToString())
                DebugSession.AUDIO -> {
                    val bytes = zip.readBytes()
                    val samples = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    audio = ShortArray(samples.remaining()).also { samples.get(it) }
                }
                DebugSession.FRAMES -> {
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
    return LoadedDebugSession(requireNotNull(info) { "${DebugSession.INFO} missing" }, audio)
}
