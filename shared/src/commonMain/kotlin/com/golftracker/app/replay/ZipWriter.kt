package com.golftracker.app.replay

/**
 * Writes a zip in memory, each entry deflated (or stored as it is), for saving recordings the way the
 * Android app does.
 * Every entry is complete when added, so sizes and checksums go in its header (no trailing descriptors).
 */
class ZipWriter {
    private class Written(val name: ByteArray, val method: Int, val crc: Int, val compressedSize: Int, val size: Int, val offset: Int)

    private val out = Bytes()
    private val written = ArrayList<Written>()

    /** Adds an entry; [compress] false stores it as it is (for video, which is compressed already). */
    fun add(name: String, data: ByteArray, compress: Boolean = true) {
        val packed = if (compress) deflateRaw(data) else data
        val method = if (compress) ZipArchive.DEFLATED else ZipArchive.STORED
        val entry = Written(name.encodeToByteArray(), method, crc32(data), packed.size, data.size, out.size)
        out.u32(ZipArchive.LOCAL_SIGNATURE.toInt())
        out.u16(VERSION); out.u16(0); out.u16(method); out.u16(DOS_TIME); out.u16(DOS_DATE)
        out.u32(entry.crc); out.u32(entry.compressedSize); out.u32(entry.size)
        out.u16(entry.name.size); out.u16(0)
        out.put(entry.name)
        out.put(packed)
        written += entry
    }

    fun finish(): ByteArray {
        val directory = out.size
        for (e in written) {
            out.u32(ZipArchive.CENTRAL_SIGNATURE.toInt())
            out.u16(VERSION); out.u16(VERSION); out.u16(0); out.u16(e.method); out.u16(DOS_TIME); out.u16(DOS_DATE)
            out.u32(e.crc); out.u32(e.compressedSize); out.u32(e.size)
            out.u16(e.name.size); out.u16(0); out.u16(0); out.u16(0); out.u16(0); out.u32(0); out.u32(e.offset)
            out.put(e.name)
        }
        val directorySize = out.size - directory
        out.u32(ZipArchive.END_SIGNATURE.toInt())
        out.u16(0); out.u16(0); out.u16(written.size); out.u16(written.size)
        out.u32(directorySize); out.u32(directory); out.u16(0)
        return out.toByteArray()
    }

    /** A growing byte buffer, little-endian as zip headers are. */
    private class Bytes {
        private var data = ByteArray(1 shl 16)
        var size = 0
            private set

        private fun room(n: Int) {
            if (size + n <= data.size) return
            var cap = data.size
            while (cap < size + n) cap *= 2
            data = data.copyOf(cap)
        }

        fun put(b: ByteArray) {
            room(b.size)
            b.copyInto(data, size)
            size += b.size
        }

        fun u16(v: Int) {
            room(2)
            data[size++] = v.toByte(); data[size++] = (v ushr 8).toByte()
        }

        fun u32(v: Int) {
            u16(v and 0xFFFF); u16(v ushr 16)
        }

        fun toByteArray() = data.copyOf(size)
    }

    internal companion object {
        const val VERSION = 20
        // 1 Jan 1980, the earliest a zip can say: the recording's own name carries when it was made.
        const val DOS_TIME = 0
        const val DOS_DATE = (0 shl 9) or (1 shl 5) or 1

        val CRC_TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        fun crc32(data: ByteArray): Int = crc32Update(0, data, data.size)

        /** Carries a CRC-32 on over [count] more bytes, for data read in pieces. */
        fun crc32Update(crc: Int, data: ByteArray, count: Int): Int {
            var c = crc.inv()
            for (i in 0 until count) c = CRC_TABLE[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
            return c.inv()
        }
    }
}

/** Raw deflate (no zlib header), as zip entries are compressed. */
expect fun deflateRaw(data: ByteArray): ByteArray
