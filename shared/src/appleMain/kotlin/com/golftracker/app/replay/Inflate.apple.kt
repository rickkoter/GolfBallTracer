@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.golftracker.app.replay

import kotlinx.cinterop.*
import platform.zlib.*

actual fun inflateRaw(data: ByteArray, size: Int): ByteArray {
    val out = ByteArray(size)
    if (size == 0) return out
    memScoped {
        val stream = alloc<z_stream>()
        // Negative window bits: raw deflate, as in zip entries.
        check(inflateInit2_(stream.ptr, -15, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) == Z_OK) { "zlib wouldn't start" }
        try {
            data.usePinned { input ->
                out.usePinned { output ->
                    stream.next_in = input.addressOf(0).reinterpret()
                    stream.avail_in = data.size.convert()
                    stream.next_out = output.addressOf(0).reinterpret()
                    stream.avail_out = size.convert()
                    val result = inflate(stream.ptr, Z_FINISH)
                    check(result == Z_STREAM_END) { "Zip entry didn't inflate (zlib $result)" }
                }
            }
        } finally {
            inflateEnd(stream.ptr)
        }
    }
    return out
}

actual fun deflateRaw(data: ByteArray): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    // Fastest compression: frames are saved right after a shot, and grey frames shrink well anyway.
    check(deflateInit2_(stream.ptr, Z_BEST_SPEED, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) == Z_OK) { "zlib wouldn't start" }
    try {
        val out = ByteArray(deflateBound(stream.ptr, data.size.convert()).toInt() + 16)
        val written = (if (data.isEmpty()) byteArrayOf(0) else data).usePinned { input ->
            out.usePinned { output ->
                stream.next_in = input.addressOf(0).reinterpret()
                stream.avail_in = data.size.convert()
                stream.next_out = output.addressOf(0).reinterpret()
                stream.avail_out = out.size.convert()
                val result = deflate(stream.ptr, Z_FINISH)
                check(result == Z_STREAM_END) { "Couldn't deflate (zlib $result)" }
                stream.total_out.toInt()
            }
        }
        out.copyOf(written)
    } finally {
        deflateEnd(stream.ptr)
    }
}
