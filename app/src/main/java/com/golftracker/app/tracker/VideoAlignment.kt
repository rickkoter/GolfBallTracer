package com.golftracker.app.tracker

import kotlin.math.abs

/**
 * Lines up a shot's recorded video with the frames analyzed live, in time, and checks they show the same
 * view.
 *
 * The video's own timestamps start from zero, and the recorder only roughly says when that was. Each live
 * frame from the swing, where the picture changes most from frame to frame and so is easiest to tell
 * apart, is looked up among the video's frames near the rough guess. The best matches agree on one offset
 * if the two really show the same thing; if the video were cropped or turned differently, nothing would
 * match well.
 */
object VideoAlignment {

    /** Live time = video time + [offsetNs]. [difference] is how closely the matched thumbnails agree (grey levels). */
    class Result(val offsetNs: Long, val difference: Float, val matched: Int)

    private const val FRAMES_TO_MATCH = 16
    private const val MAX_DIFFERENCE = 12f

    /**
     * [live] thumbnails on the live clock, [video] thumbnails timed from the start of the video, both in
     * time order. Matches are searched within [searchNs] of [guessOffsetNs].
     */
    fun align(live: List<LumaFrame>, video: List<LumaFrame>, guessOffsetNs: Long, searchNs: Long): Result? {
        if (live.size < 3 || video.size < 3) return null
        val videoInterval = medianInterval(video)
        val distinctive = (1 until live.size)
            .map { it to Thumbnails.difference(live[it - 1], live[it]) }
            .filter { it.second < Float.MAX_VALUE }
            .sortedByDescending { it.second }
            .take(FRAMES_TO_MATCH)
            .map { live[it.first] }
        if (distinctive.size < 3) return null

        val matches = distinctive.mapNotNull { l ->
            var best: LumaFrame? = null
            var bestDiff = Float.MAX_VALUE
            for (v in video) {
                if (abs(v.timestampNs + guessOffsetNs - l.timestampNs) > searchNs) continue
                val d = Thumbnails.difference(l, v)
                if (d < bestDiff) { bestDiff = d; best = v }
            }
            best?.let { Triple(l.timestampNs - it.timestampNs, bestDiff, l) }
        }
        if (matches.size < 3) return null
        val offset = matches.map { it.first }.sorted()[matches.size / 2]
        // Live frames fall between video frames, so allow a little over one video frame either way.
        val tolerance = (videoInterval * 1.5).toLong() + 5_000_000L
        val agreeing = matches.filter { abs(it.first - offset) <= tolerance }
        if (agreeing.size * 2 < matches.size) return null
        val difference = agreeing.map { it.second }.average().toFloat()
        if (difference > MAX_DIFFERENCE) return null
        return Result(agreeing.map { it.first }.average().toLong(), difference, agreeing.size)
    }

    fun medianInterval(frames: List<LumaFrame>): Long {
        if (frames.size < 2) return 33_333_333L
        val gaps = (1 until frames.size).map { frames[it].timestampNs - frames[it - 1].timestampNs }.sorted()
        return gaps[gaps.size / 2]
    }
}
