@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.golftracker.app.replay

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithContentsOfFile
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Debug recordings written entry by entry to a file read back whole, compressed or stored. */
class NativeZipFileWriterTest {
    @Test
    fun entriesReadBack() {
        val dir = NSTemporaryDirectory()
        val source = "${dir}frames.raw"
        val big = ByteArray(700_000) { ((it / 13) % 251).toByte() }
        fopen(source, "wb")!!.also { f -> big.usePinned { fwrite(it.addressOf(0), 1u, big.size.toULong(), f) }; fclose(f) }
        val zipPath = "${dir}test.zip"
        val zip = ZipFileWriter(zipPath)
        val json = "{\"device\":\"test\"}".encodeToByteArray()
        assertTrue(zip.addData("session.json", json.toNSData(), compress = true))
        assertTrue(zip.addFile("frames.bin", source, byteArrayOf(0, 0, 0, 7).toNSData(), compress = true))
        assertTrue(zip.addFile("video.mp4", source, null, compress = false))
        assertTrue(zip.close())

        val archive = ZipArchive(NSData.dataWithContentsOfFile(zipPath)!!.toByteArray())
        assertEquals(listOf("session.json", "frames.bin", "video.mp4"), archive.entries.map { it.name })
        same("session.json", json, archive.read("session.json")!!)
        same("frames.bin", byteArrayOf(0, 0, 0, 7) + big, archive.read("frames.bin")!!)
        same("video.mp4", big, archive.read("video.mp4")!!)
        remove(source)
        remove(zipPath)
    }

    private fun same(name: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "$name size")
        val at = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals(null, at, "$name first difference")
    }
}
