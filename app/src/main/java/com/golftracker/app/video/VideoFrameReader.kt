package com.golftracker.app.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.golftracker.app.tracker.LumaFrame
import com.golftracker.app.tracker.LumaSampler
import com.golftracker.app.tracker.sample
import java.io.File

/** Decodes a recorded video into [LumaFrame]s, the same kind of frames the camera analysis produces. */
class VideoFrameReader(private val file: File) {

    /** [rotation] is how far frames are turned to stand upright; [width]/[height] are as stored. */
    class Info(val durationUs: Long, val rotation: Int, val width: Int, val height: Int, val frameRate: Double?)

    fun info(): Info? = withTrack { extractor, track ->
        val format = extractor.getTrackFormat(track)
        Info(
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L,
            rotation = rotationOf(format),
            width = format.getInteger(MediaFormat.KEY_WIDTH),
            height = format.getInteger(MediaFormat.KEY_HEIGHT),
            frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) frameRateOf(format) else null
        )
    }

    /**
     * Calls [onFrame] for each frame from [fromUs] to [toUs] (video time), sampled with a long side of
     * [longSide], turned upright by [rotation] (the file's own when null) and center-cropped to
     * [uprightAspect]. Frame timestamps are the video time in nanoseconds. Returns how many were read.
     */
    fun read(
        fromUs: Long, toUs: Long, longSide: Int, uprightAspect: Float,
        rotation: Int? = null, onFrame: (LumaFrame) -> Unit
    ): Int = withTrack { extractor, track ->
        val format = extractor.getTrackFormat(track)
        val turn = rotation ?: rotationOf(format)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withTrack 0
        val codec = MediaCodec.createDecoderByType(mime)
        val sampler = LumaSampler(longSide)
        var count = 0
        try {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo(maxOf(0L, fromUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > toUs) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out < 0) {
                    // A decoder that never signals the end shouldn't hang the app.
                    if (inputDone && ++idle > MAX_IDLE_POLLS) break
                    continue
                }
                idle = 0
                val pts = info.presentationTimeUs
                if (info.size > 0 && pts in fromUs..toUs) {
                    codec.getOutputImage(out)?.use { image ->
                        val plane = image.planes[0]
                        val crop = image.cropRect
                        onFrame(
                            sampler.sample(
                                plane.buffer, crop.left, crop.top, crop.width(), crop.height(),
                                turn, plane.rowStride, plane.pixelStride, pts * 1000, uprightAspect
                            )
                        )
                        count++
                    }
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
        count
    } ?: 0

    private fun <T> withTrack(block: (MediaExtractor, Int) -> T): T? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(track)
            block(extractor, track)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_POLLS = 200

        fun rotationOf(format: MediaFormat) =
            if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0

        fun frameRateOf(format: MediaFormat): Double? = try {
            format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
        } catch (e: ClassCastException) {
            format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
        }
    }
}
