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
