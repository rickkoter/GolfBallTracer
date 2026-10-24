package com.golftracker.app.replay

/**
 * Reads the files in a zip held in memory, as the apps save recordings: each entry stored or deflated.
 * Sizes come from the zip's central directory at its end, since the apps write them after each entry's data.
 */
class ZipArchive(private val bytes: ByteArray) {

    class Entry internal constructor(val name: String, internal val method: Int, internal val offset: Long, internal val compressedSize: Long, val size: Long)

    val entries: List<Entry> = readDirectory()

    fun entry(name: String): Entry? = entries.firstOrNull { it.name == name }

    /** The entry's contents, or null if the zip has no such entry. */
    fun read(name: String): ByteArray? = entry(name)?.let { read(it) }

    fun read(entry: Entry): ByteArray {
        // Local header: 30 bytes, then the name and an extra field whose lengths are given there.
        val local = entry.offset.toInt()
        require(u32(local) == LOCAL_SIGNATURE) { "Not a zip entry at $local" }
        val start = local + 30 + u16(local + 26) + u16(local + 28)
        val data = bytes.copyOfRange(start, start + entry.compressedSize.toInt())
        return when (entry.method) {
            STORED -> data
            DEFLATED -> inflateRaw(data, entry.size.toInt())
            else -> error("${entry.name}: compression method ${entry.method} isn't supported")
        }
    }

    private fun readDirectory(): List<Entry> {
        // The end-of-directory record is in the last 64 KB (its comment can be up to 65535 bytes).
        var end = bytes.size - 22
        val stop = maxOf(0, bytes.size - 22 - 65_535)
        while (end >= stop && u32(end) != END_SIGNATURE) end--
        require(end >= stop) { "Not a zip file" }
        val count = u16(end + 10)
        var p = u32(end + 16).toInt()
        val out = ArrayList<Entry>(count)
        repeat(count) {
            require(u32(p) == CENTRAL_SIGNATURE) { "Broken zip directory" }
            val method = u16(p + 10)
            val compressed = u32(p + 20)
            val size = u32(p + 24)
            val nameLength = u16(p + 28)
            val extraLength = u16(p + 30)
            val commentLength = u16(p + 32)
            val offset = u32(p + 42)
            val name = bytes.copyOfRange(p + 46, p + 46 + nameLength).decodeToString()
            out += Entry(name, method, offset, compressed, size)
            p += 46 + nameLength + extraLength + commentLength
        }
        return out
    }

    private fun u16(i: Int): Int = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(i: Int): Long = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)

    private companion object {
        const val LOCAL_SIGNATURE = 0x04034b50L
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val END_SIGNATURE = 0x06054b50L
        const val STORED = 0
        const val DEFLATED = 8
    }
}

/** Undoes raw deflate (no zlib header), as zip entries are compressed, into [size] bytes. */
expect fun inflateRaw(data: ByteArray, size: Int): ByteArray
