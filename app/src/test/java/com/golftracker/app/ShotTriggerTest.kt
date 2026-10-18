package com.golftracker.app

import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.ShotTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Synthetic swings run through [ShotTrigger]: the shot has to be found from its sound and motion alone. */
class ShotTriggerTest {

    private val w = 288
    private val h = 640
    private val frameNs = 33_333_333L
    private val startNs = 10_000_000_000L
    private val impactFrame = 70
    private val impactNs = startNs + impactFrame * frameNs
    private val teeX = 0.55f
    private val teeY = 0.8f
    private val ballRadius = 4f

    /** Behind-the-golfer flight in screen-normalized coordinates, t seconds after launch. */
    private fun flight(t: Double) = Pair(
        teeX - 0.02 * t,
        teeY - 0.6 * (1 - exp(-t / 0.7)) + 0.03 * t * t
    )

    private class Scene(val w: Int, val h: Int, seed: Int) {
        private val rnd = Random(seed)
        val background = IntArray(w * h) { i -> (if (i / w > h * 0.55) 70 else 150) + rnd.nextInt(-12, 13) }
        private val noise = Random(seed + 1)

        fun render(camX: Int, camY: Int, draw: (IntArray) -> Unit): ByteArray {
            val px = IntArray(w * h) { background[it] + noise.nextInt(-3, 4) }
            draw(px)
            return ByteArray(w * h) { i ->
                val sx = (i % w - camX).coerceIn(0, w - 1)
                val sy = (i / w - camY).coerceIn(0, h - 1)
                px[sy * w + sx].coerceIn(0, 255).toByte()
            }
        }

        fun disk(px: IntArray, cx: Float, cy: Float, r: Float, lum: Int) {
            for (y in (cy - r - 1).toInt()..(cy + r + 1).toInt()) for (x in (cx - r - 1).toInt()..(cx + r + 1).toInt()) {
                if (x !in 0 until w || y !in 0 until h) continue
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                if (sqrt(dx * dx + dy * dy) <= r) px[y * w + x] = lum
            }
        }

        fun rect(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, lum: Int) {
            for (y in maxOf(0, y0)..minOf(h - 1, y1)) for (x in maxOf(0, x0)..minOf(w - 1, x1)) px[y * w + x] = lum
        }
    }

    /**
     * A golfer beside the ball who stands still, swings (body turning, club sweeping back and through
     * past the ball) and hits it at [impactFrame]. With [ballFlies] false it's a practice swing: no ball.
     */
    private fun swing(
        seed: Int, ballFlies: Boolean = true, swings: Boolean = true, shake: Int = 0, frames: Int = 260,
        radius: Float = ballRadius
    ): List<LumaFrame> {
        val scene = Scene(w, h, seed)
        val hand = Random(seed + 2)
        return List(frames) { f ->
            val t = (f - impactFrame) / 30.0
            val camX = if (shake == 0) 0 else hand.nextInt(-shake, shake + 1)
            val camY = if (shake == 0) 0 else hand.nextInt(-shake, shake + 1)
            val data = scene.render(camX, camY) { px ->
                // Swing from about a second before impact to half a second after.
                val phase = if (!swings) 0.0 else ((f - (impactFrame - 30)) / 45.0).coerceIn(0.0, 1.0)
                val turn = (40 * sin(phase * Math.PI)).toInt()
                scene.rect(px, 30 + turn, 260, 110 + turn, 560, 40)
                // The club: a bar sweeping round from behind the golfer, past the ball and on through.
                if (phase in 0.01..0.99) {
                    val angle = Math.PI * (0.2 + 1.6 * phase)
                    val cx = 120 + 90 * sin(angle)
                    val cy = 420 - 80 * kotlin.math.cos(angle)
                    scene.rect(px, cx.toInt() - 6, cy.toInt() - 6, cx.toInt() + 6, cy.toInt() + 6, 25)
                }
                if (ballFlies && t <= 0) {
                    scene.disk(px, teeX * w, teeY * h, radius, 235)
                } else if (ballFlies) {
                    val (x, y) = flight(t)
                    val r = maxOf(1.2f, radius * (1 - t / 3).toFloat())
                    scene.disk(px, x.toFloat() * w, y.toFloat() * h, r, 235)
                }
            }
            LumaFrame(w, h, data, startNs + f * frameNs)
        }
    }

    private fun run(frames: List<LumaFrame>, soundAtNs: Long?, watchWithoutSound: Boolean = false): List<ShotTrigger.Event> {
        val events = ArrayList<ShotTrigger.Event>()
        val trigger = ShotTrigger(keepFrames = false, watchWithoutSound = watchWithoutSound) { events += it }
        trigger.startListening()
        var heard = false
        for (frame in frames) {
            // The sound arrives as the audio is read, a little after it happened.
            if (soundAtNs != null && !heard && frame.timestampNs >= soundAtNs + 20_000_000L) {
                trigger.onImpactSound(soundAtNs)
                heard = true
            }
            trigger.onFrame(frame)
        }
        return events
    }

    /** [shake]: positions are relative to the shot's first frame, which a shaking hand has already moved. */
    private fun assertTraced(events: List<ShotTrigger.Event>, heard: Boolean, label: String, shake: Int = 0) {
        val started = events.filterIsInstance<ShotTrigger.Event.Started>()
        assertEquals("$label: shots started", 1, started.size)
        val ball = started.single().ball
        assertEquals("$label: tee x", teeX, ball.x, (2f + shake) / w)
        assertEquals("$label: tee y", teeY, ball.y, (2f + shake) / h)
        assertEquals("$label: heard", heard, started.single().heard)

        val shot = events.filterIsInstance<ShotTrigger.Event.Captured>().single().shot
        assertEquals(heard, shot.heardImpact)
        assertNotNull("$label: launch", shot.launchTimestampNs)
        assertTrue("$label: launch off by ${(shot.launchTimestampNs!! - impactNs) / 1e6} ms", abs(shot.launchTimestampNs - impactNs) <= 2 * frameNs)
        assertTrue("$label: thumbnails kept", shot.thumbnails.size > 100)

        val tracer = BallFlightFitter.fit(shot)
        assertTrue("$label: no tracer", tracer.size >= 10)
        val launchMs = impactNs / 1_000_000L
        for (p in tracer.drop(1)) {
            val t = (p.timestampMs - launchMs) / 1000.0
            if (t < 0.05) continue
            val (x, y) = flight(t)
            assertEquals("$label: x at t=$t", x, p.x.toDouble(), 0.02 + shake.toDouble() / w)
            assertEquals("$label: y at t=$t", y, p.y.toDouble(), 0.02 + shake.toDouble() / h)
        }
    }

    @Test
    fun heardShotIsFoundAndTraced() {
        for (seed in 1..3) assertTraced(run(swing(seed), impactNs), heard = true, label = "seed $seed")
    }

    @Test
    fun farAwayBallHandHeldIsFound() {
        // Filmed from across the tee box: the ball is a couple of pixels across and the phone isn't still.
        for (seed in 18..19) {
            val started = run(swing(seed, shake = 2, radius = 2f), impactNs).filterIsInstance<ShotTrigger.Event.Started>()
            assertEquals("seed $seed", 1, started.size)
            assertEquals("seed $seed: tee x", teeX, started.single().ball.x, 4f / w)
            assertEquals("seed $seed: tee y", teeY, started.single().ball.y, 4f / h)
        }
    }

    @Test
    fun heardShotIsFoundWhenTheSoundsClockIsSlightlyOff() {
        for ((seed, skewMs) in listOf(16 to 90L, 17 to -90L)) {
            val events = run(swing(seed), impactNs + skewMs * 1_000_000L)
            assertEquals("seed $seed, sound off by $skewMs ms", 1, events.filterIsInstance<ShotTrigger.Event.Started>().size)
        }
    }

    @Test
    fun unheardShotIsFoundFromMotionWithoutAMicrophone() {
        for (seed in 4..6) assertTraced(run(swing(seed), soundAtNs = null, watchWithoutSound = true), heard = false, label = "seed $seed")
    }

    @Test
    fun unheardShotIsLeftAloneWithAMicrophone() {
        // Heard nothing, so it wasn't a shot (or not one worth a false start).
        assertTrue(run(swing(4), soundAtNs = null).isEmpty())
    }

    @Test
    fun heardShotHandHeld() {
        for (seed in 7..8) assertTraced(run(swing(seed, shake = 4), impactNs), heard = true, label = "seed $seed", shake = 4)
    }

    @Test
    fun practiceSwingWithASoundIsNotAShot() {
        for (seed in 10..12) {
            val events = run(swing(seed, ballFlies = false), impactNs)
            assertTrue("seed $seed", events.none { it is ShotTrigger.Event.Started })
            val missed = events.filterIsInstance<ShotTrigger.Event.Missed>().singleOrNull()
            assertNotNull("seed $seed: the strike should be reported as missed", missed)
            assertTrue("seed $seed: during the swing", missed!!.duringSwing)
            assertTrue("seed $seed: with the frames around it", missed.frames.size > 60)
        }
    }

    @Test
    fun soundWithoutASwingIsIgnored() {
        // A clap or a shot from the next bay: the ball sits there and nobody moves.
        val events = run(swing(13, ballFlies = false, swings = false), impactNs)
        val missed = events.single() as ShotTrigger.Event.Missed
        assertTrue(!missed.duringSwing && missed.frames.isEmpty())
    }

    @Test
    fun nothingHappensWhileTheGolferStandsStill() {
        val scene = swing(14, ballFlies = false, swings = false)
        val events = run(scene, soundAtNs = null, watchWithoutSound = true)
        assertTrue(events.isEmpty())
    }

    @Test
    fun stopsAfterTheCaptureWindowAndWaitsToBeRestarted() {
        val events = ArrayList<ShotTrigger.Event>()
        val trigger = ShotTrigger(captureSeconds = 2.0, keepFrames = false) { events += it }
        trigger.startListening()
        for (frame in swing(15)) {
            if (frame.timestampNs == impactNs + frameNs) trigger.onImpactSound(impactNs)
            trigger.onFrame(frame)
        }
        val captured = events.filterIsInstance<ShotTrigger.Event.Captured>()
        assertEquals(1, captured.size)
        assertTrue(captured.single().shot.thumbnails.last().timestampNs >= impactNs + 2_000_000_000L)
        assertTrue(!trigger.wantsFrames)
    }
}
