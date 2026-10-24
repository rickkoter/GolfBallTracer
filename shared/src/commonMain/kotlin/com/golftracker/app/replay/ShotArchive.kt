package com.golftracker.app.replay

import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.LumaFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Saves a shot as the Android app's shot recordings are saved (shot-debug zips): shot.json with what was
 * detected and the tracer, and frames.bin with the analysis frames, so it replays through [RecordingReplay]
 * and the Android replay tests.
 */
object ShotArchive {

    fun encode(
        shot: CapturedShot,
        tracer: List<ScreenPoint>,
        device: String,
        club: String? = null,
        golfer: Int? = null
    ): ByteArray {
        fun floats(vararg v: Float) = JsonArray(v.map { JsonPrimitive(it) })
        val info = buildJsonObject {
            put("version", 2)
            put("device", device)
            put("tapX", shot.tapX)
            put("tapY", shot.tapY)
            put("teeX", shot.teeX)
            put("teeY", shot.teeY)
            put("ballRadius", shot.ballRadius)
            put("launchTimestampNs", shot.launchTimestampNs)
            put("aspect", shot.aspect)
            put("framesProcessed", shot.framesProcessed)
            put("firstFrameCount", shot.firstFrames.size)
            put("candidates", JsonArray(shot.candidates.map { floats(it.x, it.y, it.score) }))
            put("candidateTimestampsNs", JsonArray(shot.candidates.map { JsonPrimitive(it.timestampNs) }))
            put("tracer", JsonArray(tracer.map { floats(it.x, it.y) }))
            put("impactTimestampNs", shot.impactTimestampNs)
            put("heardImpact", shot.heardImpact)
            if (club != null) put("club", club)
            if (golfer != null) put("golfer", golfer)
        }
        val zip = ZipWriter()
        zip.add(SHOT, info.toString().encodeToByteArray())
        zip.add(FRAMES, frames(shot.firstFrames + shot.recentFrames))
        return zip.finish()
    }

    /** frames.bin: a count, then each frame's timestamp, width, height and pixels, big-endian. */
    fun frames(frames: List<LumaFrame>): ByteArray {
        val out = ByteArray(4 + frames.sumOf { 16 + it.data.size })
        var p = 0
        fun int(v: Int) { for (s in 24 downTo 0 step 8) out[p++] = (v ushr s).toByte() }
        fun long(v: Long) { int((v ushr 32).toInt()); int(v.toInt()) }
        int(frames.size)
        for (f in frames) {
            long(f.timestampNs); int(f.width); int(f.height)
            f.data.copyInto(out, p)
            p += f.data.size
        }
        return out
    }

    private const val SHOT = "shot.json"
    private const val FRAMES = "frames.bin"
}
