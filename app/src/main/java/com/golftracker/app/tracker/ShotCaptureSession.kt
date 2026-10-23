package com.golftracker.app.tracker

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Downsampled luminance frame, already rotated upright and cropped to exactly what the
 * preview shows on screen, so grid coordinates normalized by width/height are screen coordinates.
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val timestampNs: Long
) {
    fun lum(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
}

/** A moving compact blob seen in one frame. x/y are screen-normalized (0..1). */
data class BallCandidate(
    val x: Float,
    val y: Float,
    val timestampNs: Long,
    val score: Float
)

/** The ball on the tee: center in screen fractions, radius as a fraction of screen height. */
data class TeeBall(val x: Float, val y: Float, val radius: Float)

/** Everything collected while recording, handed to [BallFlightFitter] after the shot. */
data class CapturedShot(
    val candidates: List<BallCandidate>,
    val teeX: Float,
    val teeY: Float,
    /** Ball radius as a fraction of screen height, or null if the ball couldn't be measured on the tee. */
    val ballRadius: Float?,
    /** Timestamp of the frame the ball left the tee, or null if launch wasn't detected. */
    val launchTimestampNs: Long?,
    /** Screen width / height. */
    val aspect: Float,
    /** Where the ball was first located, before snapping to its center. */
    val tapX: Float = teeX,
    val tapY: Float = teeY,
    val framesProcessed: Int = 0,
    /** Kept only when recording for diagnostics: the first few frames and the most recent ones. */
    val firstFrames: List<LumaFrame> = emptyList(),
    val recentFrames: List<LumaFrame> = emptyList(),
    /** When the club struck the ball, from the impact sound or from the ball vanishing, if known. */
    val impactTimestampNs: Long? = null,
    /** Whether the impact was heard, rather than only seen. */
    val heardImpact: Boolean = false,
    /** Tiny copies of every frame, to line the recording's video up with what was analyzed live. */
    val thumbnails: List<LumaFrame> = emptyList(),
    /** Where the golfer moved between before and after the swing (non-zero), in the first frame's coordinates. */
    val changedArea: LumaFrame? = null,
    /** Whether [launchTimestampNs] is when the ball was seen to go (to a frame), rather than when the strike was heard. */
    val launchSeen: Boolean = true
)

/**
 * Collects ball candidates while recording without trying to decide live which one is the ball.
 *
 * Per frame, differencing against the frames before and after finds things that moved, and connected
 * components turn those pixels into blobs. Small, isolated blobs are kept as candidates. Separately, the ball on
 * the tee is watched so the frame where it disappears (the launch) can be timestamped.
 *
 * Called from the shot detector's thread ([process]) and the UI thread (everything else).
 */
class ShotCaptureSession(
    private val maxCandidatesPerFrame: Int = 8,
    /**
     * How many frames apart the far neighbors are when looking for motion: about 0.13 s, so a ball
     * slowing near its apex still moves its own width. 4 at 30 fps.
     */
    private val far: Int = FAR,
    /** Keep raw frames so a shot can be saved and replayed offline. */
    private val keepFrames: Boolean = false,
    /** Called on the camera thread when the ball leaves the tee (true) or turns out to still be there (false). */
    private val onLaunchDetected: ShotCaptureSession.(Boolean) -> Unit = {},
    /** Called on the camera thread once the tapped spot has been checked: the ball found there, or null. */
    private val onTeeMeasured: ShotCaptureSession.(TeeBall?) -> Unit = {}
) {
    private val lock = Any()

    private var armed = false
    private var teeX = 0f
    private var teeY = 0f
    private var tapX = 0f
    private var tapY = 0f
    private var framesProcessed = 0
    private val firstFrames = ArrayList<LumaFrame>()
    private val recentFrames = ArrayDeque<LumaFrame>()
    private var teeRefined = false
    private var ballRadiusCells: Float? = null
    private var launchHintNs: Long? = null
    private var aspect = 0.5f
    private var gridHeight = 1

    /** A frame and how far the camera had moved by then since recording started, in grid cells. */
    private class Tracked(
        val frame: LumaFrame, val ox: Int, val oy: Int,
        /** Working space, and arrays from a frame no longer needed to fill in, to save allocating them per frame. */
        rowLo: IntArray, rowHi: IntArray, reuseLo: ByteArray?, reuseHi: ByteArray?
    ) {
        /**
         * Darkest and brightest value within ±1 pixel of each pixel. Comparing against this range
         * instead of the single pixel ignores sharp edges wobbling by a pixel, which stabilization
         * and hand tremor cause constantly and which would otherwise look like motion.
         */
        val lo: ByteArray
        val hi: ByteArray
        init {
            val w = frame.width
            val h = frame.height
            val d = frame.data
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var mn = d[i].toInt() and 0xFF
                var mx = mn
                if (x > 0) { val v = d[i - 1].toInt() and 0xFF; if (v < mn) mn = v; if (v > mx) mx = v }
                if (x < w - 1) { val v = d[i + 1].toInt() and 0xFF; if (v < mn) mn = v; if (v > mx) mx = v }
                rowLo[i] = mn
                rowHi[i] = mx
            }
            lo = reuseLo?.takeIf { it.size == w * h } ?: ByteArray(w * h)
            hi = reuseHi?.takeIf { it.size == w * h } ?: ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var mn = rowLo[i]
                var mx = rowHi[i]
                if (y > 0) { mn = min(mn, rowLo[i - w]); mx = max(mx, rowHi[i - w]) }
                if (y < h - 1) { mn = min(mn, rowLo[i + w]); mx = max(mx, rowHi[i + w]) }
                lo[i] = mn.toByte()
                hi[i] = mx.toByte()
            }
        }
    }

    /** Recent frames; candidates are found for the middle one, so it can be compared both ways. */
    private val history = ArrayDeque<Tracked>()
    private var cameraX = 0
    private var cameraY = 0
    private val candidates = ArrayList<BallCandidate>()

    private val launch = TeeLaunchDetector()

    val isArmed: Boolean get() = synchronized(lock) { armed }

    /**
     * How far the camera has shifted the picture since recording started, as a fraction of the screen.
     * Positions the session reports are relative to the scene at the start, so add this to draw them
     * over what the camera shows now.
     */
    fun cameraShift(): Pair<Float, Float> = synchronized(lock) {
        val last = history.lastOrNull()?.frame ?: return 0f to 0f
        cameraX.toFloat() / last.width to cameraY.toFloat() / last.height
    }

    /**
     * Starts a new capture with the ball at screen-normalized (x, y). The first frame is searched for the
     * ball there unless [ball] already gives its measurements. [launchHintNs] is when the impact was
     * heard: used for the launch if the ball isn't seen leaving, or is seen leaving far from it.
     */
    fun arm(x: Float, y: Float, ball: TeeBall? = null, launchHintNs: Long? = null) = synchronized(lock) {
        armed = true
        cameraX = 0
        cameraY = 0
        teeX = x
        teeY = y
        tapX = x
        tapY = y
        framesProcessed = 0
        firstFrames.clear()
        recentFrames.clear()
        teeRefined = false
        ballRadiusCells = null
        pendingBall = ball
        this.launchHintNs = launchHintNs
        history.clear()
        candidates.clear()
        launch.reset()
    }

    private var pendingBall: TeeBall? = null

    /** The detected launch, unless the impact was heard well away from it: then the sound is more reliable. */
    private fun launchTime(): Long? {
        val seen = launch.launchTimestampNs
        val heard = launchHintNs ?: return seen
        return if (seen != null && abs(seen - heard) <= MAX_LAUNCH_FROM_HINT_NS) seen else heard
    }

    /** Stops capturing and returns what was collected. */
    fun stop(): CapturedShot = synchronized(lock) {
        armed = false
        history.clear()
        CapturedShot(
            candidates = candidates.toList(),
            teeX = teeX,
            teeY = teeY,
            ballRadius = ballRadiusCells?.let { it / gridHeight },
            launchTimestampNs = launchTime(),
            launchSeen = launchTime().let { it != null && it == launch.launchTimestampNs },
            aspect = aspect,
            tapX = tapX,
            tapY = tapY,
            framesProcessed = framesProcessed,
            firstFrames = firstFrames.toList(),
            recentFrames = recentFrames.toList()
        ).also {
            firstFrames.clear()
            recentFrames.clear()
        }
    }

    fun reset() = synchronized(lock) {
        armed = false
        history.clear()
        candidates.clear()
        firstFrames.clear()
        recentFrames.clear()
        launch.reset()
    }

    // Working arrays kept between frames: allocating them anew for every frame keeps the phone's
    // garbage collector busy enough to slow the detector down.
    private var rowLo = IntArray(0)
    private var rowHi = IntArray(0)
    private var mask = BooleanArray(0)
    private var strength = IntArray(0)
    private var visited = BooleanArray(0)
    private var sat = IntArray(0)
    private var stack = IntArray(0)
    private var spareLo: ByteArray? = null
    private var spareHi: ByteArray? = null

    /**
     * Takes the next frame. [shift] is how far the camera moved since the frame before, if already known
     * (content at (x, y) there is at (x + first, y + second) here); otherwise it's worked out.
     */
    fun process(frame: LumaFrame, shift: Pair<Int, Int>? = null) = synchronized(lock) {
        if (!armed) return
        aspect = frame.width.toFloat() / frame.height
        gridHeight = frame.height
        framesProcessed++
        if (keepFrames) {
            if (firstFrames.size < KEPT_FIRST_FRAMES) {
                firstFrames.add(frame)
            } else {
                recentFrames.addLast(frame)
                if (recentFrames.size > KEPT_RECENT_FRAMES) recentFrames.removeFirst()
            }
        }

        if (!teeRefined) {
            val given = pendingBall
            if (given != null) {
                ballRadiusCells = max(1f, given.radius * frame.height)
            } else {
                BallMeasure.find(frame, teeX, teeY)?.let {
                    teeX = it.x
                    teeY = it.y
                    ballRadiusCells = it.radiusCells
                }
            }
            teeRefined = true
            onTeeMeasured(ballRadiusCells?.let { TeeBall(teeX, teeY, it / frame.height) })
        }

        if (history.isNotEmpty() && (history.last().frame.width != frame.width || history.last().frame.height != frame.height)) {
            history.clear()
        }
        // Follow camera shake so the scene can be compared like-for-like between frames.
        history.lastOrNull()?.let { prev ->
            val (sx, sy) = shift ?: FrameShift.estimate(prev.frame, frame)
            cameraX += sx
            cameraY += sy
        }

        val wasLaunched = launch.launchTimestampNs != null
        ballRadiusCells?.let { r ->
            launch.update(frame, teeX * frame.width + cameraX, teeY * frame.height + cameraY, r)
        }
        val isLaunched = launch.launchTimestampNs != null
        if (isLaunched != wasLaunched) onLaunchDetected(isLaunched)

        val size = frame.width * frame.height
        if (rowLo.size != size) { rowLo = IntArray(size); rowHi = IntArray(size) }
        history.addLast(Tracked(frame, cameraX, cameraY, rowLo, rowHi, spareLo, spareHi))
        spareLo = null
        spareHi = null
        if (history.size > 2 * far + 1) history.removeFirst().let { spareLo = it.lo; spareHi = it.hi }
        if (history.size == 2 * far + 1 && candidates.size < MAX_BUFFERED_CANDIDATES) {
            candidates.addAll(detectMovingBlobs(history))
        }
    }


    /**
     * Candidates for the middle frame of [frames]. A pixel is moving if it differs from both the frame
     * before and the frame after, which places the ball where it is now without a ghost where it was.
     * Neighbors one frame away catch a fast ball; neighbors [far] frames away catch a ball that has
     * slowed on screen near its apex and moves less than its own width per frame.
     */
    private fun detectMovingBlobs(frames: ArrayDeque<Tracked>): List<BallCandidate> {
        val mid = frames[far]
        val b = mid.frame
        val bd = b.data
        val w = b.width
        val h = b.height
        val size = w * h

        // Each neighbor's data plus how far to look back into it for the same bit of scene.
        class Ref(t: Tracked) {
            val lo = t.lo
            val hi = t.hi
            val dx = t.ox - mid.ox
            val dy = t.oy - mid.oy
            /** How far b falls outside the neighbor's ±1 pixel range there, or -1 where the neighbor didn't see it. */
            fun diff(x: Int, y: Int, lb: Int): Int {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) return -1
                val j = ny * w + nx
                val l = lo[j].toInt() and 0xFF
                val u = hi[j].toInt() and 0xFF
                return if (lb > u) lb - u else if (lb < l) l - lb else 0
            }
        }
        val nearA = Ref(frames[far - 1])
        val nearC = Ref(frames[far + 1])
        val farA = Ref(frames[0])
        val farC = Ref(frames[2 * far])

        if (mask.size != size) {
            mask = BooleanArray(size)
            strength = IntArray(size)
            visited = BooleanArray(size)
            stack = IntArray(size)
            sat = IntArray((w + 1) * (h + 1))
        }
        val mask = mask
        val strength = strength
        var moving = 0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val lb = bd[i].toInt() and 0xFF
            // Moving if it differs from both near neighbours, or from both far ones. Most pixels match
            // the first neighbour looked at, so the other needn't be.
            var d = 0
            val na = nearA.diff(x, y, lb)
            if (na > DIFF_THRESHOLD) d = min(na, nearC.diff(x, y, lb))
            val fa = farA.diff(x, y, lb)
            if (fa > DIFF_THRESHOLD) d = max(d, min(fa, farC.diff(x, y, lb)))
            if (d > DIFF_THRESHOLD) {
                mask[i] = true
                strength[i] = d
                moving++
            } else {
                mask[i] = false
            }
        }
        if (moving == 0) return emptyList()
        // Most of the frame changed: camera shake or an exposure jump, nothing useful to find.
        if (moving > size / 4) return emptyList()

        // Summed-area table of the mask so each blob's surroundings can be checked in O(1).
        val sat = sat
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                if (mask[y * w + x]) rowSum++
                sat[(y + 1) * (w + 1) + (x + 1)] = sat[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        fun maskCount(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val ax = max(0, x0); val ay = max(0, y0)
            val bx = min(w - 1, x1); val by = min(h - 1, y1)
            if (ax > bx || ay > by) return 0
            return sat[(by + 1) * (w + 1) + (bx + 1)] - sat[ay * (w + 1) + (bx + 1)] -
                sat[(by + 1) * (w + 1) + ax] + sat[ay * (w + 1) + ax]
        }

        val ballDiameter = (ballRadiusCells ?: (max(w, h) * 0.0075f)) * 2f
        val maxDim = max(8f, max(4f * ballDiameter, max(w, h) * 0.04f))
        val maxThickness = max(2f, 1.5f * ballDiameter)

        val visited = visited
        java.util.Arrays.fill(visited, false)
        val stack = stack
        val found = ArrayList<BallCandidate>()
        for (start in 0 until size) {
            if (!mask[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var area = 0
            var minX = w; var maxX = -1; var minY = h; var maxY = -1
            var sumW = 0f; var sumX = 0f; var sumY = 0f
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                val weight = strength[idx].toFloat()
                area++
                sumW += weight
                sumX += weight * x
                sumY += weight * y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                    val nIdx = ny * w + nx
                    if (!mask[nIdx] || visited[nIdx]) continue
                    visited[nIdx] = true
                    stack[sp++] = nIdx
                }
            }

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val dim = max(bw, bh).toFloat()
            if (dim > maxDim) continue
            if (area > dim * maxThickness) continue
            // Long, one-pixel-thin lines are edges of limbs or the club shaft, never a ball.
            if (dim >= 4 && area / dim < 1.5f) continue

            // A ball in flight is alone in the sky; fragments of a moving body are surrounded by more motion.
            val margin = max(4, dim.roundToInt())
            val ringCells = (min(w - 1, maxX + margin) - max(0, minX - margin) + 1) *
                (min(h - 1, maxY + margin) - max(0, minY - margin) + 1) - bw * bh
            val ringMoving = maskCount(minX - margin, minY - margin, maxX + margin, maxY + margin) -
                maskCount(minX, minY, maxX, maxY)
            val isolation = if (ringCells > 0) 1f - ringMoving.toFloat() / ringCells else 1f
            if (isolation < 0.5f || ringMoving > 2 * area + 4) continue

            val contrast = sumW / area
            // Reported where the scene was when recording started, so camera shake doesn't bend the path.
            found.add(
                BallCandidate(
                    x = (sumX / sumW + 0.5f - mid.ox) / w,
                    y = (sumY / sumW + 0.5f - mid.oy) / h,
                    timestampNs = b.timestampNs,
                    score = contrast * isolation * isolation
                )
            )
        }
        found.sortByDescending { it.score }
        return if (found.size > maxCandidatesPerFrame) found.subList(0, maxCandidatesPerFrame) else found
    }


    companion object {
        private const val DIFF_THRESHOLD = 18
        const val FAR = 4
        private const val MAX_LAUNCH_FROM_HINT_NS = 120_000_000L
        private const val KEPT_FIRST_FRAMES = 10
        private const val KEPT_RECENT_FRAMES = 200
        private const val MAX_BUFFERED_CANDIDATES = 60_000
    }
}

/**
 * Watches the ball on the tee and reports when it leaves.
 *
 * Presence is the ratio of the ball's brightness to its surroundings' median, which holds steady when
 * a shadow or exposure change darkens both. The ball counts as gone after several consecutive frames
 * without it, so a club passing over the ball during a waggle isn't mistaken for a launch.
 */
internal class TeeLaunchDetector {
    var launchTimestampNs: Long? = null
        private set

    private var referenceRatio = 0f
    private var referenceFrames = 0
    private var absentRun = 0
    private var absentStartNs = 0L
    private var presentRun = 0

    fun reset() {
        launchTimestampNs = null
        referenceRatio = 0f
        referenceFrames = 0
        absentRun = 0
        presentRun = 0
    }

    fun update(frame: LumaFrame, cx: Float, cy: Float, radius: Float) {
        val ratio = presenceRatio(frame, cx, cy, radius) ?: return

        if (referenceFrames < REFERENCE_FRAMES) {
            referenceRatio = (referenceRatio * referenceFrames + ratio) / (referenceFrames + 1)
            referenceFrames++
            return
        }
        // Too little contrast between ball and ground to tell when it's gone.
        if (referenceRatio < 1.15f) return

        val present = ratio - 1f >= 0.4f * (referenceRatio - 1f)
        if (present) {
            absentRun = 0
            presentRun++
            // The ball is back, so the earlier "launch" was something passing in front of it.
            if (launchTimestampNs != null && presentRun >= REAPPEAR_FRAMES) launchTimestampNs = null
        } else {
            presentRun = 0
            if (absentRun == 0) absentStartNs = frame.timestampNs
            absentRun++
            if (absentRun == ABSENT_FRAMES) launchTimestampNs = absentStartNs
        }
    }

    private fun presenceRatio(frame: LumaFrame, cx: Float, cy: Float, radius: Float): Float? {
        val inner = max(1f, radius * 0.7f)
        val ringIn = radius * 2f + 1f
        val ringOut = radius * 3f + 2f
        var innerSum = 0
        var innerCount = 0
        val ring = ArrayList<Int>()
        val reach = ringOut.toInt() + 1
        val px = cx.toInt()
        val py = cy.toInt()
        for (y in py - reach..py + reach) {
            if (y < 0 || y >= frame.height) continue
            for (x in px - reach..px + reach) {
                if (x < 0 || x >= frame.width) continue
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d <= inner) {
                    innerSum += frame.lum(x, y)
                    innerCount++
                } else if (d in ringIn..ringOut) {
                    ring.add(frame.lum(x, y))
                }
            }
        }
        if (innerCount == 0 || ring.size < 4) return null
        ring.sort()
        val ringMedian = ring[ring.size / 2]
        return (innerSum.toFloat() / innerCount + 1f) / (ringMedian + 1f)
    }

    companion object {
        private const val REFERENCE_FRAMES = 3
        private const val ABSENT_FRAMES = 3
        private const val REAPPEAR_FRAMES = 5
    }
}
