package com.novelcharacter.app.util

import java.io.InputStream
import java.security.MessageDigest

/** 파일 내용의 SHA-256 — 같은 바이트인지 견주는 데만 쓴다(B-53 ①). */
object ContentHash {
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
