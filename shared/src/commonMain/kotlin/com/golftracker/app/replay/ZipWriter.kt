package com.golftracker.app.replay

/**
 * Writes a zip in memory, each entry deflated, for saving recordings the way the Android app does.
 * Every entry is complete when added, so sizes and checksums go in its header (no trailing descriptors).
 */
class ZipWriter {
    private class Written(val name: ByteArray, val crc: Int, val compressedSize: Int, val size: Int, val offset: Int)

    private val out = Bytes()
    private val written = ArrayList<Written>()

    fun add(name: String, data: ByteArray) {
        val packed = deflateRaw(data)
        val entry = Written(name.encodeToByteArray(), crc32(data), packed.size, data.size, out.size)
        out.u32(ZipArchive.LOCAL_SIGNATURE.toInt())
        out.u16(VERSION); out.u16(0); out.u16(ZipArchive.DEFLATED); out.u16(DOS_TIME); out.u16(DOS_DATE)
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
            out.u16(VERSION); out.u16(VERSION); out.u16(0); out.u16(ZipArchive.DEFLATED); out.u16(DOS_TIME); out.u16(DOS_DATE)
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

    private companion object {
        const val VERSION = 20
        // 1 Jan 1980, the earliest a zip can say: the recording's own name carries when it was made.
        const val DOS_TIME = 0
        const val DOS_DATE = (0 shl 9) or (1 shl 5) or 1

        val CRC_TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        fun crc32(data: ByteArray): Int {
            var c = -1
            for (b in data) c = CRC_TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
            return c.inv()
        }
    }
}

/** Raw deflate (no zlib header), as zip entries are compressed. */
expect fun deflateRaw(data: ByteArray): ByteArray
