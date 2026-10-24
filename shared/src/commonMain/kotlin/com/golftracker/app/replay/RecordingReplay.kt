package com.golftracker.app.replay

import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.ShotTrigger
import com.golftracker.app.tracker.TeeBall
import com.golftracker.app.tracker.fixed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Plays a saved recording back through the shot detection the apps use live, so a shot can be looked at
 * again (and the tracking checked) on any phone: a shot's recording (shot-debug, missed-strike, or a
 * shared golf-shot bundle) or a debug recording (debug-session), all zips holding frames.bin.
 */
object RecordingReplay {

    /** What a recording is. */
    enum class Kind { SHOT, DEBUG_SESSION }

    /** One shot found in the replay: the ball, its flight (if any), and the frame they're drawn over. */
    class ReplayedShot(
        val strikeNs: Long,
        val heard: Boolean,
        val ball: TeeBall?,
        val flight: BallFlightFitter.Flight?,
        /** The frame the ball's and flight's positions are in: the first one the capture was given. */
        val frame: LumaFrame?
    ) {
        val summary: String get() = when {
            ball == null -> "Strike heard, but no ball seen leaving"
            flight == null -> "Ball found, but no flight"
            flight.estimated -> "Ball seen for ${seconds(flight.trackedSeconds)} s, the rest estimated"
            else -> "Ball followed for ${seconds(flight.trackedSeconds)} s"
        }
    }

    class Result(
        val kind: Kind,
        val frameCount: Int,
        /** How long the recording's frames span, in seconds. */
        val seconds: Double,
        val strikes: Int,
        val shots: List<ReplayedShot>,
        /** Notes written during a debug recording. */
        val notes: List<Note>
    )

    /** A note from a debug recording, [atSeconds] from its start. */
    class Note(val atSeconds: Double, val text: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun replay(zip: ByteArray): Result {
        val archive = ZipArchive(zip)
        val frames = readFrames(archive.read(FRAMES) ?: error("This recording has no frames"))
        val shotInfo = archive.read(SHOT)?.let { json.parseToJsonElement(it.decodeToString()).jsonObject }
        val sessionInfo = archive.read(SESSION)?.let { json.parseToJsonElement(it.decodeToString()).jsonObject }
        val kind = if (sessionInfo != null) Kind.DEBUG_SESSION else Kind.SHOT

        // When strikes were heard: the shot's own, or every one a debug recording logged.
        val strikes: List<Long> = when {
            sessionInfo != null -> events(sessionInfo).filter { it.first == "strike-heard" }.map { it.second }
            else -> listOfNotNull(shotInfo?.get("impactTimestampNs")?.jsonPrimitive?.longOrNull)
        }.sorted()
        val heard = shotInfo?.get("heardImpact")?.jsonPrimitive?.booleanOrNull ?: true
        val camera = sessionInfo?.let { cameraPose(it) }
        val startNs = sessionInfo?.get("startedNs")?.jsonPrimitive?.longOrNull ?: frames.firstOrNull()?.timestampNs ?: 0L
        val notes = sessionInfo?.let { events(it).filter { e -> e.first == "note" }.map { e -> Note((e.second - startNs) / 1e9, e.third) } } ?: emptyList()

        val shots = ArrayList<ReplayedShot>()
        var started: ShotTrigger.Event.Started? = null
        // A shot whose strike wasn't heard (the microphone was off) is looked for in movement, as on the phone.
        val trigger = ShotTrigger(keepFrames = false, watchWithoutSound = !heard) { event ->
            when (event) {
                is ShotTrigger.Event.Started -> started = event
                is ShotTrigger.Event.Captured -> {
                    val shot = event.shot
                    val firstNs = shot.thumbnails.firstOrNull()?.timestampNs
                    shots += ReplayedShot(
                        strikeNs = shot.impactTimestampNs ?: 0L,
                        heard = shot.heardImpact,
                        ball = started?.ball,
                        flight = BallFlightFitter.fitBest(shot, camera),
                        frame = firstNs?.let { ns -> frames.firstOrNull { it.timestampNs == ns } }
                    )
                    started = null
                }
                is ShotTrigger.Event.Missed -> shots += ReplayedShot(event.impactNs, true, null, null, null)
            }
        }
        trigger.startListening()
        val pending = ArrayDeque(strikes)
        for (frame in frames) {
            // A strike reaches the detector a moment after it happened, as on the phone.
            while (pending.isNotEmpty() && frame.timestampNs >= pending.first() + 20_000_000L) {
                trigger.onImpactSound(pending.removeFirst())
            }
            trigger.onFrame(frame)
            // In a debug recording, listening carries on after each shot.
            if (!trigger.wantsFrames) trigger.startListening()
        }
        // A strike right at the end of a shot's recording is still heard.
        while (pending.isNotEmpty()) trigger.onImpactSound(pending.removeFirst())
        trigger.finishCapture()

        val seconds = if (frames.size > 1) (frames.last().timestampNs - frames.first().timestampNs) / 1e9 else 0.0
        return Result(kind, frames.size, seconds, strikes.size, shots, notes)
    }

    /** frames.bin: a count, then each frame's timestamp, width, height and pixels, big-endian. */
    fun readFrames(bytes: ByteArray): List<LumaFrame> {
        var p = 0
        fun int(): Int {
            val v = ((bytes[p].toInt() and 0xFF) shl 24) or ((bytes[p + 1].toInt() and 0xFF) shl 16) or
                ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)
            p += 4
            return v
        }
        fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xFFFFFFFFL)
        val count = int()
        val out = ArrayList<LumaFrame>(count)
        repeat(count) {
            val ts = long()
            val w = int()
            val h = int()
            out += LumaFrame(w, h, bytes.copyOfRange(p, p + w * h), ts)
            p += w * h
        }
        return out
    }

    /** A debug recording's log: (kind, when, detail). */
    private fun events(info: JsonObject): List<Triple<String, Long, String>> =
        info["events"]?.jsonArray?.mapNotNull { e ->
            val o = e.jsonObject
            val kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val at = o["timestampNs"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            Triple(kind, at, o["detail"]?.jsonPrimitive?.contentOrNull ?: "")
        } ?: emptyList()

    /** The camera's field of view and orientation, which debug recordings save, for estimating flights. */
    private fun cameraPose(info: JsonObject): LaunchPhysics.CameraPose? {
        val fov = info["verticalFovRadians"]?.jsonPrimitive?.doubleOrNull ?: return null
        val matrix = info["deviceToWorld"]?.jsonArray?.mapNotNull { it.jsonPrimitive.floatOrNull } ?: return null
        return if (matrix.size == 9) LaunchPhysics.CameraPose(fov, matrix.toFloatArray()) else null
    }

    private fun seconds(s: Double): String = s.fixed(2)

    private const val FRAMES = "frames.bin"
    private const val SHOT = "shot.json"
    private const val SESSION = "session.json"
}
