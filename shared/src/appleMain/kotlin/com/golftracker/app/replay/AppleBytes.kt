@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.golftracker.app.replay

import com.golftracker.app.tracker.LumaFrame
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** For Swift: a file's contents as Kotlin bytes, in one copy. */
fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** For Swift: a frame's grey pixels, row by row, ready to make an image from. */
fun LumaFrame.pixels(): NSData = data.usePinned { NSData.create(bytes = it.addressOf(0), length = data.size.toULong()) }

/** Replays a recording read from a file, for the iOS app. */
fun replayRecording(contents: NSData): RecordingReplay.Result = RecordingReplay.replay(contents.toByteArray())

/** For Swift: Kotlin bytes as NSData, in one copy. */
fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
