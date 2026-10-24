@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.golftracker.app.tracker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** Milliseconds since 1970: the clock shots are timestamped with. */
fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** A new random ID for a shot. */
fun newShotId(): String = kotlin.uuid.Uuid.random().toString()

fun toRadians(degrees: Double): Double = degrees * PI / 180.0
fun toDegrees(radians: Double): Double = radians * 180.0 / PI

/** This number with [digits] decimals, rounding halves up as "%.Nf" does: 0.86.fixed(1) is "0.9". */
fun Double.fixed(digits: Int): String {
    val scale = 10.0.pow(digits)
    val r = floor(abs(this) * scale + 0.5).toLong()
    val step = scale.toLong()
    val whole = r / step
    val sign = if (this < 0 && r != 0L) "-" else ""
    return if (digits == 0) "$sign$whole" else "$sign$whole." + (r % step).toString().padStart(digits, '0')
}

fun Float.fixed(digits: Int): String = toDouble().fixed(digits)

/** As [fixed], always with a sign: "+0.4", "-1.2". */
fun Double.signed(digits: Int): String = fixed(digits).let { if (it.startsWith("-")) it else "+$it" }

/**
 * A lock that the same thread can take again while holding it: the tracking code is driven from the
 * camera's thread and read from the screen's.
 */
expect class TrackerLock() {
    fun lock()
    fun unlock()
}

inline fun <T> TrackerLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
