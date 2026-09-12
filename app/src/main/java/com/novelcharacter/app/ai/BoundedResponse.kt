package com.novelcharacter.app.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Detect overflow with one extra byte; never parse a silently shortened JSON document. */
object BoundedResponse {
    const val AI_BYTES = 20_000_000
    const val SPEECH_BYTES = 2_000_000
    class TooLarge : java.io.IOException()
    fun read(input: InputStream, limit: Int): ByteArray {
        require(limit >= 0)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        var remaining = limit.toLong() + 1
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        if (output.size() > limit) throw TooLarge()
        return output.toByteArray()
    }
}
