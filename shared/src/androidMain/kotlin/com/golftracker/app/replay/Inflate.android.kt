package com.golftracker.app.replay

actual fun inflateRaw(data: ByteArray, size: Int): ByteArray {
    val inflater = java.util.zip.Inflater(true)
    try {
        inflater.setInput(data)
        val out = ByteArray(size)
        var done = 0
        while (done < size && !inflater.finished()) {
            val n = inflater.inflate(out, done, size - done)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            done += n
        }
        require(done == size) { "Zip entry ended after $done of $size bytes" }
        return out
    } finally {
        inflater.end()
    }
}

actual fun deflateRaw(data: ByteArray): ByteArray {
    val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream(data.size / 2 + 64)
        val chunk = ByteArray(1 shl 16)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}
