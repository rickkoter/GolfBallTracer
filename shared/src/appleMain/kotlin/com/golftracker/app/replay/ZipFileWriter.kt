@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.golftracker.app.replay

import kotlinx.cinterop.*
import platform.Foundation.NSData
import platform.posix.*
import platform.zlib.*

/**
 * Writes a zip to a file, entry by entry, for recordings too big to hold in memory (debug recordings run
 * to hundreds of megabytes). Each entry's size and checksum go in its header, as Java's zip reader needs:
 * a compressed entry is squeezed into a temporary file first, then copied in.
 */
class ZipFileWriter(private val path: String) {
    private class Written(val name: ByteArray, val method: Int, val crc: Int, val compressedSize: Long, val size: Long, val offset: Long)

    private val out: CPointer<FILE>? = fopen(path, "wb")
    private val written = ArrayList<Written>()
    private var offset = 0L
    private val buffer = ByteArray(1 shl 16)

    val isOpen: Boolean get() = out != null

    /** Adds [data] as an entry. */
    fun addData(name: String, data: NSData, compress: Boolean): Boolean {
        val tmp = "$path.part"
        val f = fopen(tmp, "wb") ?: return false
        val bytes = data.toByteArray()
        if (bytes.isNotEmpty()) bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), f) }
        fclose(f)
        return try { addFile(name, tmp, null, compress) } finally { remove(tmp) }
    }

    /** Adds the file at [sourcePath] as an entry, after [prefix] (such as a header the file lacks). */
    fun addFile(name: String, sourcePath: String, prefix: NSData?, compress: Boolean): Boolean {
        val zip = out ?: return false
        val head = prefix?.toByteArray() ?: ByteArray(0)
        val source = fopen(sourcePath, "rb") ?: return false
        // The checksum and size cover the prefix and the file (the deflater reports both as it reads them).
        var crc = 0
        var size = 0L
        val method: Int
        val packedPath: String?
        val packedSize: Long
        if (compress) {
            packedPath = "$path.deflate"
            val packed = fopen(packedPath, "wb") ?: run { fclose(source); return false }
            val result = deflateTo(packed, head, source) { chunk, n -> crc = ZipWriter.crc32Update(crc, chunk, n); size += n }
            fclose(packed)
            fclose(source)
            if (result < 0) { remove(packedPath); return false }
            packedSize = result
            method = ZipArchive.DEFLATED
        } else {
            crc = ZipWriter.crc32Update(crc, head, head.size)
            size += head.size
            while (true) {
                val n = read(source)
                if (n <= 0) break
                crc = ZipWriter.crc32Update(crc, buffer, n)
                size += n
            }
            fclose(source)
            packedPath = null
            packedSize = size
            method = ZipArchive.STORED
        }
        val entry = Written(name.encodeToByteArray(), method, crc, packedSize, size, offset)
        val header = Header()
        header.u32(ZipArchive.LOCAL_SIGNATURE.toInt())
        header.u16(ZipWriter.VERSION); header.u16(0); header.u16(method); header.u16(ZipWriter.DOS_TIME); header.u16(ZipWriter.DOS_DATE)
        header.u32(crc); header.u32(packedSize.toInt()); header.u32(size.toInt())
        header.u16(entry.name.size); header.u16(0)
        header.put(entry.name)
        write(zip, header.bytes())
        if (packedPath != null) {
            copy(packedPath, zip)
            remove(packedPath)
        } else {
            write(zip, head)
            copy(sourcePath, zip)
        }
        written += entry
        return true
    }

    /** Writes the zip's directory and closes it. */
    fun close(): Boolean {
        val zip = out ?: return false
        val directory = offset
        for (e in written) {
            val h = Header()
            h.u32(ZipArchive.CENTRAL_SIGNATURE.toInt())
            h.u16(ZipWriter.VERSION); h.u16(ZipWriter.VERSION); h.u16(0); h.u16(e.method); h.u16(ZipWriter.DOS_TIME); h.u16(ZipWriter.DOS_DATE)
            h.u32(e.crc); h.u32(e.compressedSize.toInt()); h.u32(e.size.toInt())
            h.u16(e.name.size); h.u16(0); h.u16(0); h.u16(0); h.u16(0); h.u32(0); h.u32(e.offset.toInt())
            h.put(e.name)
            write(zip, h.bytes())
        }
        val end = Header()
        end.u32(ZipArchive.END_SIGNATURE.toInt())
        end.u16(0); end.u16(0); end.u16(written.size); end.u16(written.size)
        end.u32((offset - directory).toInt()); end.u32(directory.toInt()); end.u16(0)
        write(zip, end.bytes())
        return fclose(zip) == 0
    }

    private fun read(f: CPointer<FILE>): Int = buffer.usePinned { fread(it.addressOf(0), 1u, buffer.size.convert(), f).toInt() }

    private fun write(f: CPointer<FILE>, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), f) }
        offset += bytes.size
    }

    private fun copy(fromPath: String, to: CPointer<FILE>) {
        val f = fopen(fromPath, "rb") ?: return
        while (true) {
            val n = read(f)
            if (n <= 0) break
            buffer.usePinned { fwrite(it.addressOf(0), 1u, n.convert(), to) }
            offset += n
        }
        fclose(f)
    }

    /** Deflates [head] then [source] into [packed], reporting each piece of input; returns the packed size, or -1. */
    private fun deflateTo(packed: CPointer<FILE>, head: ByteArray, source: CPointer<FILE>, onInput: (ByteArray, Int) -> Unit): Long = memScoped {
        val stream = alloc<z_stream>()
        if (deflateInit2_(stream.ptr, Z_BEST_SPEED, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) != Z_OK) return -1
        val outBuf = ByteArray(1 shl 16)
        var total = 0L
        fun pump(input: ByteArray, n: Int, finish: Boolean): Boolean {
            input.usePinned { inPin ->
                outBuf.usePinned { outPin ->
                    stream.next_in = if (n > 0) inPin.addressOf(0).reinterpret() else null
                    stream.avail_in = n.convert()
                    do {
                        stream.next_out = outPin.addressOf(0).reinterpret()
                        stream.avail_out = outBuf.size.convert()
                        val r = deflate(stream.ptr, if (finish) Z_FINISH else Z_NO_FLUSH)
                        if (r == Z_STREAM_ERROR) return false
                        val produced = outBuf.size - stream.avail_out.toInt()
                        if (produced > 0) fwrite(outPin.addressOf(0), 1u, produced.convert(), packed)
                        total += produced
                    } while (stream.avail_out.toInt() == 0 || (finish && r != Z_STREAM_END))
                }
            }
            return true
        }
        try {
            if (head.isNotEmpty()) {
                onInput(head, head.size)
                if (!pump(head, head.size, false)) return -1
            }
            while (true) {
                val n = read(source)
                if (n <= 0) break
                onInput(buffer, n)
                if (!pump(buffer, n, false)) return -1
            }
            if (!pump(ByteArray(1), 0, true)) return -1
            total
        } finally {
            deflateEnd(stream.ptr)
        }
    }

    /** A little-endian header being put together. */
    private class Header {
        private val data = ArrayList<Byte>()
        fun u16(v: Int) { data += v.toByte(); data += (v ushr 8).toByte() }
        fun u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
        fun put(b: ByteArray) { b.forEach { data += it } }
        fun bytes() = data.toByteArray()
    }
}
