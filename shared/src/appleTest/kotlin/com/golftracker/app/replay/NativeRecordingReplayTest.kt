@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.golftracker.app.replay

import kotlinx.cinterop.toKString
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.posix.getenv
import kotlin.test.Test

/**
 * The shared replay run natively, as on iOS (zlib for the zips), over the recordings in GOLF_SHOTS_DIR:
 *   GOLF_SHOTS_DIR=… ./gradlew :shared:macosArm64Test
 * Compare its output with RecordingReplayTest's on the JVM.
 */
class NativeRecordingReplayTest {
    @Test
    fun replaySavedRecordings() {
        val dir = getenv("GOLF_SHOTS_DIR")?.toKString() ?: return
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)?.map { it.toString() }
            ?.filter { it.endsWith(".zip") }?.sorted() ?: return
        for (name in names) {
            val data = NSData.dataWithContentsOfFile("$dir/$name") ?: continue
            val result = replayRecording(data)
            println("=== $name: ${result.kind}, ${result.frameCount} frames, ${result.strikes} strikes")
            for (shot in result.shots) {
                println("   " + (shot.ball?.let { "ball at (${it.x.toDouble().let { v -> (v * 1000).toInt() / 1000.0 }}, ${it.y.toDouble().let { v -> (v * 1000).toInt() / 1000.0 }}): " } ?: "") + shot.summary)
            }
        }
    }
}
