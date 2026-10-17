package com.golftracker.app.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer

/**
 * Keeps the camera recording video all the time the tracker is listening, so whenever a shot happens
 * the swing before it is already on video. After a shot the recording is stopped and handed over, and a
 * new one started. A recording that runs long with no shot is restarted, to keep files small.
 *
 * Call from the main thread.
 */
class ShotVideoRecorder(private val context: Context) {

    /** A finished recording. [startNs] is roughly when its first frame was taken, on the frames' clock. */
    class Clip(val file: File, val startNs: Long?)

    private val dir = File(context.cacheDir, "rolling_video").apply {
        // Whatever is left from before was never handed over.
        deleteRecursively()
        mkdirs()
    }
    // Finished recordings move here, out of the way of the next one, until a shot's processing takes them.
    private val handedOver = File(context.cacheDir, "shot_clips").apply {
        mkdirs()
        listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > STALE_CLIP_MS) it.delete() }
    }
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var file: File? = null
    private var finalized: CompletableDeferred<Boolean>? = null
    private var startOffsetNs = Long.MAX_VALUE
    private var startedNs = 0L
    private var counter = 0

    val isRecording: Boolean get() = recording != null

    /** How long the current recording has been running. */
    val ageNs: Long get() = if (recording == null) 0L else SystemClock.elapsedRealtimeNanos() - startedNs

    /** The camera's video use case, once bound (null when the camera couldn't record video alongside analysis). */
    fun attach(capture: VideoCapture<Recorder>?) {
        if (capture === videoCapture) return
        discard()
        videoCapture = capture
    }

    val canRecord: Boolean get() = videoCapture != null

    fun start(): Boolean {
        if (recording != null) return true
        val capture = videoCapture ?: return false
        val target = File(dir, "rolling_${System.currentTimeMillis()}_${counter++}.mp4")
        val done = CompletableDeferred<Boolean>()
        startOffsetNs = Long.MAX_VALUE
        startedNs = SystemClock.elapsedRealtimeNanos()
        return try {
            recording = capture.output
                .prepareRecording(context, FileOutputOptions.Builder(target).build())
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Status -> {
                            // Events arrive a little after the frame they report, so the earliest-looking
                            // estimate of when recording began is the best.
                            val duration = event.recordingStats.recordedDurationNanos
                            if (duration > 0) {
                                startOffsetNs = minOf(startOffsetNs, SystemClock.elapsedRealtimeNanos() - duration)
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) android.util.Log.w(TAG, "Recording ended with error ${event.error}", event.cause)
                            done.complete(target.exists() && target.length() > 0)
                        }
                    }
                }
            file = target
            finalized = done
            true
        } catch (e: Exception) {
            e.printStackTrace()
            recording = null
            false
        }
    }

    /** Stops recording and returns the video, once it has been written out. */
    suspend fun stop(): Clip? {
        val active = recording ?: return null
        val target = file
        val done = finalized
        val start = startOffsetNs.takeIf { it != Long.MAX_VALUE }
        recording = null
        file = null
        finalized = null
        active.stop()
        val ok = withTimeoutOrNull(5_000) { done?.await() } ?: false
        if (!ok || target == null) {
            target?.delete()
            return null
        }
        val moved = File(handedOver, target.name)
        return Clip(if (target.renameTo(moved)) moved else target, start)
    }

    /** Stops recording and throws the video away. */
    fun discard() {
        val active = recording ?: return
        val target = file
        recording = null
        file = null
        val done = finalized
        finalized = null
        active.stop()
        done?.invokeOnCompletion { target?.delete() }
    }

    companion object {
        private const val TAG = "ShotVideoRecorder"
        private const val STALE_CLIP_MS = 60 * 60 * 1000L

        /**
         * Copies [src] to [dst] from the key frame at or before [fromUs] onward, without re-encoding.
         * Returns where in [src] the copy starts (µs), or null if it couldn't be done.
         */
        fun trim(src: File, dst: File, fromUs: Long): Long? {
            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null
            return try {
                extractor.setDataSource(src.path)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return null
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                dst.parentFile?.mkdirs()
                val mux = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mux
                if (format.containsKey(MediaFormat.KEY_ROTATION)) mux.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                val out = mux.addTrack(format)
                mux.start()
                extractor.seekTo(maxOf(0L, fromUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                val buffer = ByteBuffer.allocate(maxOf(capacity, 4 shl 20))
                val info = MediaCodec.BufferInfo()
                var first = -1L
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val t = extractor.sampleTime
                    if (first < 0) first = t
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, maxOf(0L, t - first), flags)
                    mux.writeSampleData(out, buffer, info)
                    extractor.advance()
                }
                mux.stop()
                if (first < 0) null else first
            } catch (e: Exception) {
                e.printStackTrace()
                dst.delete()
                null
            } finally {
                try { muxer?.release() } catch (_: Exception) {}
                extractor.release()
            }
        }
    }
}
