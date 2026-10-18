package com.golftracker.app.debug

import android.content.Context
import android.os.SystemClock
import com.golftracker.app.export.ShotDebugExporter
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.LumaFrame
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Records a [DebugSession]. Frames and sound arrive on the camera and audio threads and are written to
 * temporary files on a thread of its own, so nothing has to stay in memory; [finish] puts them together
 * with the video into one zip in Downloads.
 */
class DebugSessionRecorder(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(context.cacheDir, "debug_session")
    private val framesFile = File(dir, "frames.raw")
    private val audioFile = File(dir, "audio.pcm")

    private class Audio(val samples: ShortArray, val firstSampleNs: Long, val sampleRate: Int)
    private object Stop

    @Volatile
    var isActive = false
        private set

    @Volatile
    var startedNs = 0L
        private set

    private val queue = LinkedBlockingQueue<Any>()
    private var writer: Thread? = null
    private val frames = AtomicInteger()
    private val dropped = AtomicInteger()
    private val events = ArrayList<DebugSession.Event>()
    @Volatile private var audioRate: Int? = null
    @Volatile private var audioStartNs: Long? = null
    @Volatile private var audioSamples = 0L

    /** About how big the recording is so far, in bytes. */
    @Volatile
    var bytes = 0L
        private set

    fun start(): Boolean {
        if (isActive) return true
        dir.deleteRecursively()
        if (!dir.mkdirs()) return false
        frames.set(0)
        dropped.set(0)
        synchronized(events) { events.clear() }
        audioRate = null
        audioStartNs = null
        audioSamples = 0
        bytes = 0
        queue.clear()
        startedNs = SystemClock.elapsedRealtimeNanos()
        writer = Thread({ write() }, "debug-recording").apply { start() }
        isActive = true
        log("start")
        return true
    }

    fun onFrame(frame: LumaFrame) {
        if (!isActive) return
        // Several seconds behind: the storage can't keep up, so this one goes missing rather than memory running out.
        if (queue.size > MAX_QUEUED) dropped.incrementAndGet() else queue.offer(frame)
    }

    fun onAudio(samples: ShortArray, count: Int, firstSampleNs: Long, sampleRate: Int) {
        if (isActive) queue.offer(Audio(samples.copyOf(count), firstSampleNs, sampleRate))
    }

    /** Notes something the app did, for the session's timeline. */
    fun log(kind: String, detail: String = "", timestampNs: Long = SystemClock.elapsedRealtimeNanos()) {
        if (!isActive) return
        synchronized(events) { events += DebugSession.Event(timestampNs, kind, detail) }
    }

    /** Stops recording, so the video can be stopped too before [finish]. */
    fun stop() {
        if (!isActive) return
        log("stop")
        isActive = false
        queue.offer(Stop)
        writer?.join()
        writer = null
    }

    /**
     * Writes the recording to Downloads/GolfBallTracer/debug-session_*.zip with [video], if any. Slow:
     * call off the main thread. Returns where it was saved, or null.
     */
    fun finish(video: File?, videoStartNs: Long?, micEnabled: Boolean, camera: LaunchPhysics.CameraPose?): String? {
        stop()
        val info = DebugSession.Info(
            device = ShotDebugExporter.device(),
            startedNs = startedNs,
            endedNs = SystemClock.elapsedRealtimeNanos(),
            frameCount = frames.get(),
            framesDropped = dropped.get(),
            audioSampleRate = audioRate,
            audioStartNs = audioStartNs,
            audioSamples = audioSamples,
            videoStartNs = videoStartNs,
            micEnabled = micEnabled,
            events = synchronized(events) { events.toList() },
            verticalFovRadians = camera?.verticalFovRadians,
            deviceToWorld = camera?.deviceToWorld?.toList()
        )
        return try {
            ShotDebugExporter.writeToDownloads(appContext, "debug-session_${ShotDebugExporter.stamp()}.zip") { out ->
                ZipOutputStream(BufferedOutputStream(out, 1 shl 16)).use { zip ->
                    zip.putNextEntry(ZipEntry(DebugSession.INFO))
                    zip.write(DebugSession.json.encodeToString(DebugSession.Info.serializer(), info).toByteArray())
                    zip.closeEntry()
                    // Sound before frames, so a replay knows the strikes before it reaches them.
                    audioRate?.let { rate ->
                        zip.putNextEntry(ZipEntry(DebugSession.AUDIO))
                        DebugSession.writeWavHeader(zip, rate, audioFile.length())
                        audioFile.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    // Frames are big and squeeze poorly; the fastest compression is plenty.
                    zip.setLevel(Deflater.BEST_SPEED)
                    zip.putNextEntry(ZipEntry(DebugSession.FRAMES))
                    DataOutputStream(zip).writeInt(frames.get())
                    framesFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    if (video != null && video.exists()) {
                        zip.setLevel(Deflater.NO_COMPRESSION)
                        zip.putNextEntry(ZipEntry(DebugSession.VIDEO))
                        video.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        } finally {
            video?.delete()
            dir.deleteRecursively()
        }
    }

    /** Throws the recording away. */
    fun abandon() {
        stop()
        dir.deleteRecursively()
    }

    private fun write() {
        DataOutputStream(BufferedOutputStream(framesFile.outputStream(), 1 shl 20)).use { frameOut ->
            BufferedOutputStream(audioFile.outputStream(), 1 shl 16).use { audioOut ->
                var pcm = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    when (val item = queue.take()) {
                        Stop -> return
                        is LumaFrame -> {
                            frameOut.writeLong(item.timestampNs)
                            frameOut.writeInt(item.width)
                            frameOut.writeInt(item.height)
                            frameOut.write(item.data)
                            frames.incrementAndGet()
                            bytes += 16L + item.data.size
                        }
                        is Audio -> {
                            if (audioRate == null) {
                                audioRate = item.sampleRate
                                audioStartNs = item.firstSampleNs
                            }
                            if (item.samples.size * 2 > pcm.capacity()) pcm = ByteBuffer.allocate(item.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                            pcm.clear()
                            for (s in item.samples) pcm.putShort(s)
                            audioOut.write(pcm.array(), 0, pcm.position())
                            audioSamples += item.samples.size
                            bytes += item.samples.size * 2L
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Longest debug recording. At 30 analysis frames a second that's about 250 MB before compression. */
        const val MAX_SECONDS = 45
        private const val MAX_QUEUED = 150
    }
}
