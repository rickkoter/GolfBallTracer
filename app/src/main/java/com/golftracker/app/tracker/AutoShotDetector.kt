package com.golftracker.app.tracker

import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue

/**
 * Runs a [ShotTrigger] on its own thread, so the camera thread only hands frames over and is never held
 * up while a shot is being found or captured. Frames queue up meanwhile rather than being dropped,
 * so the start of the flight is never lost. Events are delivered on the detector's thread.
 */
class AutoShotDetector(onEvent: (ShotTrigger.Event) -> Unit) : AutoCloseable {

    private sealed interface Message {
        class Frame(val frame: LumaFrame) : Message
        class Sound(val timestampNs: Long) : Message
        data object Listen : Message
        class WatchWithoutSound(val on: Boolean) : Message
        data object StopListening : Message
        data object Restart : Message
        data object Finish : Message
        data object Quit : Message
    }

    private val trigger = ShotTrigger(onEvent = onEvent)
    private val queue = LinkedBlockingQueue<Message>()
    private val worker = Thread({ run() }, "shot-detector").apply {
        isDaemon = true
        start()
    }

    /** Whether frames are wanted; the analyzer can skip converting them otherwise. */
    val wantsFrames: Boolean get() = trigger.wantsFrames

    /** When swing-sized motion was last seen, on the frames' clock. */
    val lastActivityNs: Long get() = trigger.lastActivityNs

    /** The newest frame looked at so far, on the frames' clock; 0 before any. */
    @Volatile
    private var lastSeenFrameNs = 0L

    /** Frames waiting to be looked at. */
    val queuedFrames: Int get() = queue.count { it is Message.Frame }

    /** Frames thrown away because the detector had fallen too far behind. */
    @Volatile
    var droppedFrames = 0
        private set

    /** How far behind the camera the detector is, or 0 when it's keeping up (or idle). */
    val lagNs: Long get() {
        val last = lastSeenFrameNs
        if (last == 0L || queue.none { it is Message.Frame }) return 0L
        return (SystemClock.elapsedRealtimeNanos() - last).coerceAtLeast(0L)
    }

    fun offerFrame(frame: LumaFrame) {
        // About 10 s of frames: beyond that the detector has fallen hopelessly behind.
        if (queue.size < MAX_QUEUED) queue.offer(Message.Frame(frame)) else droppedFrames++
    }

    fun onImpactSound(timestampNs: Long) {
        queue.offer(Message.Sound(timestampNs))
    }

    fun startListening() {
        queue.offer(Message.Listen)
    }

    /** Whether to look for shots in bursts of motion too, for when there's no microphone to hear them. */
    fun watchWithoutSound(on: Boolean) {
        queue.offer(Message.WatchWithoutSound(on))
    }

    fun stopListening() {
        queue.offer(Message.StopListening)
    }

    /**
     * Starts listening afresh: any shot being captured is abandoned, and frames and sounds still waiting
     * to be looked at are thrown away, so listening picks up from what the camera sees now.
     */
    fun restart() {
        queue.removeIf { it is Message.Frame || it is Message.Sound }
        queue.offer(Message.Restart)
    }

    /** Ends the shot being captured now. */
    fun finishCapture() {
        queue.offer(Message.Finish)
    }

    override fun close() {
        queue.offer(Message.Quit)
    }

    private fun run() {
        while (true) {
            try {
                when (val m = queue.take()) {
                    is Message.Frame -> {
                        lastSeenFrameNs = m.frame.timestampNs
                        trigger.onFrame(m.frame)
                    }
                    is Message.Sound -> trigger.onImpactSound(m.timestampNs)
                    Message.Listen -> trigger.startListening()
                    is Message.WatchWithoutSound -> trigger.watchWithoutSound = m.on
                    Message.StopListening -> trigger.stopListening()
                    Message.Restart -> {
                        trigger.stopListening()
                        trigger.startListening()
                    }
                    Message.Finish -> trigger.finishCapture()
                    Message.Quit -> {
                        trigger.stopListening()
                        return
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private companion object {
        const val MAX_QUEUED = 300
    }
}
