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
