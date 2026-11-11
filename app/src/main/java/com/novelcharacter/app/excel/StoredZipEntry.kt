package com.novelcharacter.app.excel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 이미지 엔트리를 **무압축(STORED)**으로 쓴다 — 설계 D8(이미 압축된 JPEG·PNG에 deflate를 걸어
 * 얻는 것이 없다)의 구현.
 *
 * **종전 구현은 `ZipOutputStream.setLevel(NO_COMPRESSION)`을 엔트리 사이에서 바꿨다.** 안드로이드
 * 런타임에서는 그 전환 직후 엔트리의 deflate 스트림이 깨졌다(*"invalid code lengths set"*) —
 * 실측으로 이미지 포함 전체 백업마다 **첫 이미지 한 장**이 깨졌고, 복원은 그 한 장에서 예외로
 * 끝나 **백업 전체가 되살아나지 않았다**(S25 4-ㄴ2, 2026-11-11 에뮬레이터). JVM에서는 재현되지
 * 않아 단위 시험이 잡지 못했다.
 *
 * STORED는 압축 수준을 건드리지 않는다 — 엔트리마다 크기·CRC를 미리 적어야 하므로 원본을
 * **먼저 굳힌 사본**(작으면 메모리, 크면 [scratchDir]의 임시 파일)으로 뜨며 재고, 엔트리는 그
 * 사본에서 쓴다. 원본을 두 번 열면 그 사이에 바뀐 장 하나가 크기·CRC 불일치로 **열린 엔트리를
 * 남겨 이후 ZIP 전체를 망친다**(PR #432 리뷰) — 사본은 이 함수만 쥐고 있어 바뀌지 않는다.
 * 수준 전환이 없으므로 앞뒤 deflate 엔트리(data.xlsx·JSON)도 기본 수준 그대로다.
 */
object StoredZipEntry {

    /** 이 크기 이하는 메모리 사본으로 굳힌다 — 대부분의 그림이 여기 든다(디스크 왕복 없음). */
    const val IN_MEMORY_LIMIT: Long = 16L * 1024 * 1024

    /** 원본의 (크기, CRC32) — 한 번 끝까지 훑어서 잰다. [sink]가 있으면 읽은 바이트를 그대로 흘린다. */
    fun measure(input: InputStream, sink: OutputStream? = null): Pair<Long, Long> {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            crc.update(buf, 0, n)
            sink?.write(buf, 0, n)
            size += n
        }
        return size to crc.value
    }

    /**
     * [file]을 STORED 엔트리 [name]으로 쓴다.
     *
     * **굳힌 사본을 뜨는 것이 엔트리를 열기 전이다** — 원본을 못 여는·못 읽는 흔한 실패(방금
     * 지워졌다·권한이 없다·읽다 끊겼다)는 엔트리를 하나도 남기지 않고 여기서 던진다(종전 규약:
     * 실패한 장이 0바이트·반쪽 엔트리를 남기지 않는다). 엔트리는 사본에서 쓰므로 원본이 그 사이에
     * 바뀌어도 크기·CRC가 어긋나지 않는다.
     *
     * @param scratchDir 큰 그림([inMemoryLimit] 초과)의 사본을 둘 곳 — 쓰고 나면 지운다.
     * @param inMemoryLimit 메모리 사본 상한 — 시험이 임시 파일 갈래를 고르려고 낮춘다.
     */
    fun write(
        zip: ZipOutputStream,
        name: String,
        file: File,
        scratchDir: File,
        inMemoryLimit: Long = IN_MEMORY_LIMIT
    ) {
        if (file.length() <= inMemoryLimit) {
            val buf = ByteArrayOutputStream(file.length().toInt().coerceAtLeast(32))
            val (size, crc) = file.inputStream().use { measure(it, buf) }
            val bytes = buf.toByteArray()
            writeBytesFrom(zip, name, size, crc) { ByteArrayInputStream(bytes) }
            return
        }
        scratchDir.mkdirs()
        val copy = File.createTempFile("zipimg_", ".tmp", scratchDir)
        try {
            val (size, crc) = file.inputStream().use { input ->
                FileOutputStream(copy).use { out -> measure(input, out) }
            }
            writeBytesFrom(zip, name, size, crc) { copy.inputStream() }
        } finally {
            copy.delete()
        }
    }

    /** [open]이 내는 바이트를 STORED 엔트리로 쓴다 — 크기·CRC는 부르는 쪽이 잰 값. */
    fun writeBytesFrom(zip: ZipOutputStream, name: String, size: Long, crc: Long, open: () -> InputStream) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc
        }
        open().use { input ->
            zip.putNextEntry(entry)
            input.copyTo(zip)
            zip.closeEntry()
        }
    }
}
