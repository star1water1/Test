package com.novelcharacter.app.util

import java.io.InputStream
import java.io.OutputStream

/**
 * 스트림을 복사하되 [limit]을 초과하면 즉시 중단한다.
 * 반환값이 [limit]보다 크면 상한 초과이므로 호출자가 결과 파일을 폐기해야 한다.
 * 크기를 미리 알 수 없는(statSize = -1) 콘텐츠 프로바이더에서도 상한을 강제할 수 있다.
 */
fun copyWithLimit(input: InputStream, output: OutputStream, limit: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        total += read
        if (total > limit) break
    }
    return total
}
