package com.golftracker.app

import com.golftracker.app.replay.RecordingReplay
import com.golftracker.app.replay.ZipArchive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The shared replay the iOS app uses: its zip reader against Java's zip writer, and (with
 * GOLF_SHOTS_DIR) every recording replayed, to compare with ShotReplayTest and DebugSessionReplayTest.
 */
class RecordingReplayTest {

    @Test
    fun readsStoredAndDeflatedEntries() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val small = "{\"impactTimestampNs\":5}".toByteArray()
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("frames.bin"))
                z.write(big)
                z.closeEntry()
                val stored = ZipEntry("shot.json").apply {
                    method = ZipEntry.STORED
                    size = small.size.toLong()
                    crc = CRC32().apply { update(small) }.value
                }
                z.putNextEntry(stored)
                z.write(small)
                z.closeEntry()
            }
        }.toByteArray()
        val archive = ZipArchive(zip)
        assertEquals(listOf("frames.bin", "shot.json"), archive.entries.map { it.name })
        assertArrayEquals(big, archive.read("frames.bin"))
        assertArrayEquals(small, archive.read("shot.json"))
        assertNull(archive.read("video.mp4"))
    }

    @Test
    fun replaySavedRecordings() {
        val dir = File(System.getenv("GOLF_SHOTS_DIR") ?: "shots")
        val files = dir.listFiles { f -> f.name.endsWith(".zip") }?.sortedBy { it.name } ?: return
        for (file in files) {
            val result = RecordingReplay.replay(file.readBytes())
            println("=== ${file.name}: ${result.kind}, ${result.frameCount} frames over ${"%.1f".format(result.seconds)} s, ${result.strikes} strikes")
            for (shot in result.shots) {
                println("   " + (shot.ball?.let { "ball at (%.3f, %.3f): ".format(it.x, it.y) } ?: "") + shot.summary)
            }
            result.notes.forEach { println("   note at %.1f s: %s".format(it.atSeconds, it.text)) }
        }
    }
}
