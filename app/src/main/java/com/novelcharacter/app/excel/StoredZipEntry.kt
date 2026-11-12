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

    /** 큰 그림 사본의 이름 모양 — [sweepStaleScratch]가 이 모양만 지운다. */
    const val SCRATCH_PREFIX = "zipimg_"
    const val SCRATCH_SUFFIX = ".tmp"

    /**
     * [dir]에 남은 **오래된** 사본([SCRATCH_PREFIX]…[SCRATCH_SUFFIX])을 지우고 지운 수를 낸다.
     *
     * [write]는 사본을 `finally`에서 지우지만, 백업이 그 사이에 강제 종료되면(자동 백업 작업이
     * 죽는 흔한 갈래) 16 MiB 넘는 사본이 그대로 남는다(PR #432 리뷰). **오래된 것만** 지운다 —
     * 지금 함께 도는 다른 내보내기의 사본을 태우지 않으려는 것이다(백업 고아 청소와 같은 규약).
     */
    fun sweepStaleScratch(dir: File, olderThanMs: Long, now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - olderThanMs
        val stale = dir.listFiles { f ->
            f.isFile && f.name.startsWith(SCRATCH_PREFIX) && f.name.endsWith(SCRATCH_SUFFIX) &&
                f.lastModified() < cutoff
        } ?: return 0
        return stale.count { it.delete() }
    }

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
     * @param scratchDir 큰 그림([inMemoryLimit] 초과)의 사본을 둘 곳 — 쓰고 나면 지운다. 중단으로
     *   남은 사본은 [sweepStaleScratch]가 치우므로 **그 청소가 도는 곳**(앱 `cacheDir`)을 넘긴다.
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
            val buf = SnapshotBuffer(file.length().toInt().coerceAtLeast(32))
            val (size, crc) = file.inputStream().use { measure(it, buf) }
            // `toByteArray()`는 전량을 한 벌 더 복사한다 — 상한 근처 그림이면 잠깐 두 배(≈32 MiB)를
            // 쥐어, 워크북을 들고 있는 내보내기·자동 백업에서 힙이 모자랄 수 있다(PR #432 리뷰).
            writeBytesFrom(zip, name, size, crc) { buf.asInputStream() }
            return
        }
        scratchDir.mkdirs()
        val copy = File.createTempFile(SCRATCH_PREFIX, SCRATCH_SUFFIX, scratchDir)
        try {
            val (size, crc) = file.inputStream().use { input ->
                FileOutputStream(copy).use { out -> measure(input, out) }
            }
            writeBytesFrom(zip, name, size, crc) { copy.inputStream() }
        } finally {
            copy.delete()
        }
    }

    /** 메모리 사본 — 내부 배열을 복사 없이 그대로 읽어 낸다. */
    private class SnapshotBuffer(initial: Int) : ByteArrayOutputStream(initial) {
        fun asInputStream(): InputStream = ByteArrayInputStream(buf, 0, count)
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
