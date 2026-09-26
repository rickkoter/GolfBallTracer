package com.golftracker.app.tracker

import com.golftracker.app.model.ScreenPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Saves a recorded shot (what the camera saw, what was detected, and the tracer that came out) as a
 * zip, so a shot that went wrong on the course can be replayed and debugged offline.
 *
 *   shot.json   – settings, detections and the result
 *   frames.bin  – the raw analysis frames: count, then per frame timestamp, width, height, pixels
 */
object ShotDebugArchive {

    @Serializable
    data class Info(
        val version: Int = 1,
        val device: String,
        val tapX: Float,
        val tapY: Float,
        val teeX: Float,
        val teeY: Float,
        val ballRadius: Float?,
        val launchTimestampNs: Long?,
        val aspect: Float,
        val framesProcessed: Int,
        val firstFrameCount: Int,
        /** Candidates as [x, y, score] with timestamps in a parallel list, to keep the file small. */
        val candidates: List<List<Float>>,
        val candidateTimestampsNs: List<Long>,
        val tracer: List<List<Float>>
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun write(shot: CapturedShot, tracer: List<ScreenPoint>, device: String, out: OutputStream) {
        val info = Info(
            device = device,
            tapX = shot.tapX,
            tapY = shot.tapY,
            teeX = shot.teeX,
            teeY = shot.teeY,
            ballRadius = shot.ballRadius,
            launchTimestampNs = shot.launchTimestampNs,
            aspect = shot.aspect,
            framesProcessed = shot.framesProcessed,
            firstFrameCount = shot.firstFrames.size,
            candidates = shot.candidates.map { listOf(it.x, it.y, it.score) },
            candidateTimestampsNs = shot.candidates.map { it.timestampNs },
            tracer = tracer.map { listOf(it.x, it.y) }
        )
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("shot.json"))
            zip.write(json.encodeToString(Info.serializer(), info).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("frames.bin"))
            val data = DataOutputStream(zip)
            val frames = shot.firstFrames + shot.recentFrames
            data.writeInt(frames.size)
            for (f in frames) {
                data.writeLong(f.timestampNs)
                data.writeInt(f.width)
                data.writeInt(f.height)
                data.write(f.data)
            }
            data.flush()
            zip.closeEntry()
        }
    }

    class Archive(val info: Info, val frames: List<LumaFrame>) {
        /** The shot as it was captured, for re-running just the fitter. */
        fun toCapturedShot() = CapturedShot(
            candidates = info.candidates.indices.map { i ->
                val c = info.candidates[i]
                BallCandidate(c[0], c[1], info.candidateTimestampsNs[i], c[2])
            },
            teeX = info.teeX,
            teeY = info.teeY,
            ballRadius = info.ballRadius,
            launchTimestampNs = info.launchTimestampNs,
            aspect = info.aspect,
            tapX = info.tapX,
            tapY = info.tapY,
            framesProcessed = info.framesProcessed,
            firstFrames = frames.take(info.firstFrameCount),
            recentFrames = frames.drop(info.firstFrameCount)
        )
    }

    fun read(input: InputStream): Archive {
        var info: Info? = null
        var frames: List<LumaFrame> = emptyList()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    "shot.json" -> info = json.decodeFromString(Info.serializer(), zip.readBytes().decodeToString())
                    "frames.bin" -> {
                        val data = DataInputStream(zip)
                        val count = data.readInt()
                        frames = List(count) {
                            val ts = data.readLong()
                            val w = data.readInt()
                            val h = data.readInt()
                            val bytes = ByteArray(w * h)
                            data.readFully(bytes)
                            LumaFrame(w, h, bytes, ts)
                        }
                    }
                }
            }
        }
        return Archive(requireNotNull(info) { "shot.json missing" }, frames)
    }
}
