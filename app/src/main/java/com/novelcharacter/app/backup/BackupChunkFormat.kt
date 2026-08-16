package com.novelcharacter.app.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * **백업 암호 형식의 순수 계층** (B-230 ⓐ, 2026.08.16) — 세 형식(NCB2 청크 · v1 레거시 ·
 * NCP1 패스프레이즈)의 틀 짜기와 읽기를 전부 든다. `javax.crypto`만 쓰고 `android.*`를
 * 하나도 import 하지 않는 것이 이 파일의 존재 이유다.
 *
 * ## 왜 갈랐는가 — 이 저장소가 이미 두 번 쓴 처방이다
 *
 * [BackupEncryptor]는 426줄에 **감사도 시험도 0**이었다(엑셀 전수 감사 B-230 ⓐ). 닿지 못한
 * 이유는 로직이 어려워서가 아니라 파일 머리의 `android.security.keystore` import 둘 때문이다
 * — 그 둘이 있으면 순수 JVM 하네스가 이 파일을 **컴파일조차 못 한다**(스텁이 없다).
 * 그래서 *키를 어디서 얻는가*(AndroidKeyStore — 하네스 밖)와 *형식을 어떻게 쓰고 읽는가*
 * (순수)를 갈랐다. 같은 처방을 `DropdownListSheet`가 `ExcelExporter`에서, `ImportLookupIndex`가
 * `ExcelImportService`에서 이미 썼다.
 *
 * **복호화 결함 하나가 전 백업 복원 불가**라 이 계층이 로컬에서 잠기는 것의 값이 특히 크다.
 *
 * ## 형식
 *
 * ```
 * NCB2 : MAGIC "NCB2"(4B) + chunkSize(4B)                          + 청크*
 * NCP1 : MAGIC "NCP1"(4B) + iterations(4B) + salt(16B) + chunkSize(4B) + 청크*
 * v1   : IV(12B) + 전체 암호문 + GCM 태그                            (레거시 — 전체 로드)
 * 청크 : IV(12B) + 암호문 + GCM 태그(16B)
 * ```
 *
 * ## ⚠️ 이 형식이 기대는 불변식 하나 — **마지막을 뺀 모든 청크는 정확히 `chunkSize`다**
 *
 * 청크에는 **길이 칸이 없다.** 읽는 쪽은 `chunkSize + 16`을 통째로 집어 그것을 한 청크로 보므로,
 * 쓰는 쪽이 중간에 **짧은 청크**를 하나라도 흘리면 읽는 쪽은 그 뒤 청크의 IV까지 함께 삼켜
 * **GCM 태그 검증이 깨지고 그 지점부터 파일 전체가 복원 불가**가 된다. 그리고 그 파손은
 * *백업을 뜨는 시점에는 아무 증상이 없다* — 몇 달 뒤 복원할 때 처음 드러난다.
 *
 * 그래서 [writeChunks]는 `InputStream.read`의 반환값을 그대로 믿지 않고 [readFully]로 채운다.
 * `read(buffer)`는 **버퍼를 채운다고 약속하지 않는다** — 규격이 "1개 이상 maxSize 이하"만
 * 보장하고, FUSE로 얹힌 외부 저장소(안드로이드 11+의 `/sdcard`)는 실제로 짧게 준다.
 * 종전 구현은 `fis.read(buffer)` 한 번의 반환값을 그대로 청크 길이로 삼았다(B-230 ⓐ에서 수리).
 */
object BackupChunkFormat {

    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_LENGTH = 128                      // bits
    const val GCM_TAG_BYTES = GCM_TAG_LENGTH / 8        // 16 bytes
    const val GCM_IV_LENGTH = 12                        // bytes

    /** v2(NCB2) 기본 청크 크기. 피크 메모리는 청크 하나 + 그 복호화 결과다. */
    const val CHUNK_SIZE = 1024 * 1024
    const val FORMAT_HEADER_SIZE = 8                    // MAGIC(4) + chunkSize(4)

    /** v1 레거시 상한 — 전체 로드 방식이라 메모리 보호용. */
    const val MAX_LEGACY_FILE_SIZE = 256L * 1024 * 1024

    const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val PBKDF2_ITERATIONS = 200_000
    const val MAX_PBKDF2_ITERATIONS = 5_000_000         // 헤더 조작으로 인한 CPU 소진 방지
    const val SALT_LENGTH = 16
    const val MIN_PASSPHRASE_LENGTH = 6

    /**
     * 매직은 **부를 때마다 새 배열로 준다.**
     *
     * 종전 `BackupEncryptor`에서는 `private val`이라 밖에서 건드릴 수 없었다. 여기서 공개로
     * 올린 것은 **시험이 "이 네 바이트는 이미 디스크에 쓰여 있다"를 못박아야 하기 때문**이고
     * (`formatConstants_areFrozen`), 공개된 `val ByteArray`는 그 순간부터 **object의 공유 가변
     * 상태**가 된다 — 누가 한 바이트만 고쳐도 그 프로세스의 모든 백업이 조용히 다른 형식이 된다.
     * 즉 함수인 이유는 공개했기 때문이지, 종전 구현에 그 결함이 있었기 때문이 아니다.
     */
    fun formatMagic(): ByteArray = byteArrayOf(0x4E, 0x43, 0x42, 0x32)      // "NCB2"

    fun portableMagic(): ByteArray = byteArrayOf(0x4E, 0x43, 0x50, 0x31)    // "NCP1"

    // ──────────────────────────────────────────────────────────────────────
    // 청크 틀 — 이 파일의 심장. 쓰기와 읽기가 서로의 거울이어야 한다.
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 청크 단위로 암호화해 [out]에 쓴다. 청크마다 새 IV로 `[IV(12B)][암호문+GCM 태그]`.
     *
     * **마지막을 뺀 모든 청크가 정확히 [chunkSize]임을 이 함수가 보장한다** — 위 클래스 주석의
     * 불변식이고, 그래서 [readFully]로 버퍼를 채운 뒤에만 청크를 닫는다.
     *
     * @param onProgress 지금까지 읽은 **입력(평문) 바이트** 누적.
     */
    fun writeChunks(
        input: InputStream,
        out: OutputStream,
        key: SecretKey,
        chunkSize: Int = CHUNK_SIZE,
        onProgress: ((Long) -> Unit)? = null
    ) {
        require(chunkSize > 0) { "Chunk size must be positive: $chunkSize" }
        var consumed = 0L
        val buffer = ByteArray(chunkSize)
        while (true) {
            // read() 한 번이 아니라 readFully — 짧은 읽기가 청크 경계를 어긋내면
            // 그 백업은 복원 시점에 통째로 죽는다(클래스 주석의 불변식).
            val bytesRead = readFully(input, buffer)
            if (bytesRead == 0) break
            consumed += bytesRead
            onProgress?.invoke(consumed)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv                          // 12 bytes, 청크마다 새 IV
            val encrypted = cipher.doFinal(buffer, 0, bytesRead)

            out.write(iv)
            out.write(encrypted)                        // 암호문 + GCM 태그
            if (bytesRead < buffer.size) break          // 마지막 청크 — 입력이 끝났다
        }
    }

    /**
     * 청크 단위 복호화. 청크마다 GCM 태그를 검증한다.
     * 피크 메모리: 암호문 청크 + 복호화 결과.
     *
     * @param consumedBefore 헤더로 이미 읽은 바이트 — [onProgress]가 **입력 파일 기준**
     *   누적을 내도록 맞춘다(총량은 파일 길이다. R-26 · B-51).
     */
    fun readChunks(
        input: InputStream,
        out: OutputStream,
        chunkSize: Int,
        key: SecretKey,
        onProgress: ((Long) -> Unit)? = null,
        consumedBefore: Long = 0L
    ) {
        val maxEncryptedChunkSize = chunkSize + GCM_TAG_BYTES
        // 청크는 이미 MB급이라 청크마다 보고해도 갱신이 작업보다 비싸지지 않는다.
        var consumed = consumedBefore
        while (true) {
            val iv = ByteArray(GCM_IV_LENGTH)
            val ivRead = readFully(input, iv)
            if (ivRead == 0) break                      // EOF — 모든 청크 처리 완료
            require(ivRead == GCM_IV_LENGTH) { "Incomplete chunk IV: read $ivRead bytes" }

            // 마지막 청크는 chunkSize보다 짧을 수 있다.
            val encryptedChunk = readChunkData(input, maxEncryptedChunkSize)
            require(encryptedChunk.isNotEmpty()) { "Empty encrypted chunk" }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            out.write(cipher.doFinal(encryptedChunk))

            consumed += ivRead + encryptedChunk.size
            onProgress?.invoke(consumed)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // NCB2 (기기 키) — 키 조달만 밖에서 받는다
    // ──────────────────────────────────────────────────────────────────────

    /**
     * v2(NCB2) 형식으로 파일을 암호화한다. 키는 부르는 쪽이 댄다.
     *
     * **헤더에 적는 크기와 실제로 자르는 크기는 반드시 같은 값이어야 한다** — 갈리면 읽는 쪽이
     * 남의 청크 경계로 집어 이 판이 고친 것과 **똑같은 모양의 복원 불가**가 된다. 그래서 둘 다
     * `chunkSize` 지역 변수 하나에서 나온다(기본값에 두 번 기대지 않는다).
     */
    fun encryptFileWithKey(inputFile: File, outputFile: File, key: SecretKey) {
        val chunkSize = CHUNK_SIZE
        FileOutputStream(outputFile).use { fos ->
            fos.write(formatMagic())
            fos.write(ByteBuffer.allocate(4).putInt(chunkSize).array())
            FileInputStream(inputFile).use { fis -> writeChunks(fis, fos, key, chunkSize) }
        }
    }

    /**
     * v1/v2를 자동 감지해 복호화한다. 성공한 뒤에만 [outputFile]을 확정한다(임시 파일 경유).
     *
     * @param keyProvider 키는 **필요한 순간에** 받는다 — 헤더가 깨져 갈래에 닿지 못하면
     *   KeyStore를 건드리지 않는다(없는 키를 만들어 두는 부작용을 피한다).
     */
    fun decryptFileWithKey(
        inputFile: File,
        outputFile: File,
        keyProvider: () -> SecretKey,
        onProgress: ((Long) -> Unit)? = null
    ) {
        val tempFile = File(outputFile.parentFile, outputFile.name + ".tmp")
        try {
            val fis = FileInputStream(inputFile)
            try {
                // 최소 8바이트: v2 헤더 또는 v1 IV의 앞부분
                val header = ByteArray(FORMAT_HEADER_SIZE)
                val headerRead = readFully(fis, header)
                val magic = formatMagic()

                if (headerRead >= FORMAT_HEADER_SIZE &&
                    header[0] == magic[0] && header[1] == magic[1] &&
                    header[2] == magic[2] && header[3] == magic[3]
                ) {
                    val chunkSize = ByteBuffer.wrap(header, 4, 4).int
                    require(chunkSize in 1..CHUNK_SIZE * 2) {
                        "Invalid chunk size in backup header: $chunkSize"
                    }
                    FileOutputStream(tempFile).use { fos ->
                        readChunks(fis, fos, chunkSize, keyProvider(), onProgress, headerRead.toLong())
                    }
                } else {
                    // v1: 헤더를 이미 소비했으므로 새로 열어야 한다.
                    fis.close()
                    decryptLegacy(inputFile, tempFile, keyProvider)
                    // 레거시는 한 번의 doFinal이라 중간 보고 지점이 없다 — 끝난 사실만 알린다.
                    onProgress?.invoke(inputFile.length())
                }
            } finally {
                try { fis.close() } catch (_: Exception) { }
            }
            commit(tempFile, outputFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * v1 레거시 복호화. 기존 백업 파일과의 하위호환.
     * 피크 메모리: ~2x 파일 크기 (암호문 + 복호화 결과) — 그래서 [MAX_LEGACY_FILE_SIZE] 상한이 있다.
     *
     * @param keyProvider **크기 검사를 통과한 뒤에** 부른다 — 인자로 미리 받아 두면 3바이트짜리
     *   깨진 파일에도 KeyStore가 새 키를 만들어, 그 뒤의 진짜 백업이 열리지 않는다
     *   (`BackupChunkFormatTest`의 `deviceFormat_badHeaderDoesNotAskForKey`가 그 자리를 잠근다 —
     *   B-230 ⓐ의 갈라내기에서 실제로 한 번 어긋났고 그 시험이 잡았다).
     */
    private fun decryptLegacy(inputFile: File, outputFile: File, keyProvider: () -> SecretKey) {
        val fileSize = inputFile.length()
        require(fileSize > GCM_IV_LENGTH) {
            "Encrypted file too short: expected at least ${GCM_IV_LENGTH + 1} bytes"
        }
        require(fileSize <= MAX_LEGACY_FILE_SIZE) {
            "Encrypted file too large: ${fileSize / (1024 * 1024)}MB exceeds ${MAX_LEGACY_FILE_SIZE / (1024 * 1024)}MB limit. " +
                "Re-export backup with the latest app version to use the efficient chunked format."
        }

        FileInputStream(inputFile).use { fis ->
            val iv = ByteArray(GCM_IV_LENGTH)
            val ivRead = readFully(fis, iv)
            if (ivRead != GCM_IV_LENGTH) {
                throw java.io.IOException(
                    "Incomplete IV: expected $GCM_IV_LENGTH bytes but read $ivRead. The backup file may be corrupted."
                )
            }

            val ciphertextSize = (fileSize - GCM_IV_LENGTH).toInt()
            val ciphertext = ByteArray(ciphertextSize)
            val totalRead = readFully(fis, ciphertext)
            if (totalRead != ciphertextSize) {
                throw java.io.IOException(
                    "Incomplete ciphertext: expected $ciphertextSize bytes but read $totalRead. The backup file may be corrupted or truncated."
                )
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = cipher.doFinal(ciphertext)

            FileOutputStream(outputFile).use { fos -> fos.write(decrypted) }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // NCP1 (패스프레이즈) — 기기 KeyStore 비의존이라 통째로 순수하다
    // ──────────────────────────────────────────────────────────────────────

    /** 파일이 이식 가능(NCP1) 형식인지 검사한다. 복원 진입 시 암호 입력 여부 분기에 쓴다. */
    fun isPortableFormat(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val expected = portableMagic()
                val magic = ByteArray(expected.size)
                readFully(fis, magic) == expected.size && magic.contentEquals(expected)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 패스프레이즈에서 AES-256 키를 유도한다. */
    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * 패스프레이즈 기반 이식 가능 암호화. 기기 이전/공유용.
     *
     * @param iterations PBKDF2 반복 수 — 헤더에 실려 복호화가 그대로 따른다. 기본값을 낮추지
     *   말 것(시험이 빠르라고 낮춰 받는 자리다).
     */
    fun encryptFilePortable(
        inputFile: File,
        outputFile: File,
        passphrase: CharArray,
        iterations: Int = PBKDF2_ITERATIONS,
        onProgress: ((Long) -> Unit)? = null
    ) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }
        require(iterations in 1..MAX_PBKDF2_ITERATIONS) { "Invalid iteration count: $iterations" }
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, iterations)
        // 헤더에 적는 크기와 실제로 자르는 크기는 한 값에서 나온다 — 갈리면 복원이 남의
        // 청크 경계로 집는다([encryptFileWithKey]의 같은 주석).
        val chunkSize = CHUNK_SIZE
        FileOutputStream(outputFile).use { fos ->
            fos.write(portableMagic())
            fos.write(ByteBuffer.allocate(4).putInt(iterations).array())
            fos.write(salt)
            fos.write(ByteBuffer.allocate(4).putInt(chunkSize).array())
            FileInputStream(inputFile).use { fis -> writeChunks(fis, fos, key, chunkSize, onProgress) }
        }
    }

    /**
     * 이식 가능(NCP1) 형식 복호화. 잘못된 암호는 첫 청크의 GCM 태그 검증 실패
     * (`javax.crypto.AEADBadTagException`)로 감지된다.
     *
     * @param onProgress 지금까지 읽은 입력 바이트(누적). 총량은 `inputFile.length()`다(R-26 · B-51).
     */
    fun decryptFilePortable(
        inputFile: File,
        outputFile: File,
        passphrase: CharArray,
        onProgress: ((Long) -> Unit)? = null
    ) {
        val tempFile = File(outputFile.parentFile, outputFile.name + ".tmp")
        try {
            FileInputStream(inputFile).use { fis ->
                val expectedMagic = portableMagic()
                val magic = ByteArray(expectedMagic.size)
                require(readFully(fis, magic) == expectedMagic.size && magic.contentEquals(expectedMagic)) {
                    "Not a portable backup file"
                }
                val intBuf = ByteArray(4)
                require(readFully(fis, intBuf) == 4) { "Corrupted portable backup header (iterations)" }
                val iterations = ByteBuffer.wrap(intBuf).int
                require(iterations in 1..MAX_PBKDF2_ITERATIONS) { "Invalid iteration count: $iterations" }

                val salt = ByteArray(SALT_LENGTH)
                require(readFully(fis, salt) == SALT_LENGTH) { "Corrupted portable backup header (salt)" }
                require(readFully(fis, intBuf) == 4) { "Corrupted portable backup header (chunk size)" }
                val chunkSize = ByteBuffer.wrap(intBuf).int
                require(chunkSize in 1..CHUNK_SIZE * 2) { "Invalid chunk size in backup header: $chunkSize" }

                val key = deriveKey(passphrase, salt, iterations)
                // 헤더는 MAGIC(4) + iterations(4) + salt(16) + chunkSize(4)까지 읽었다.
                val headerBytes = (expectedMagic.size + 4 + SALT_LENGTH + 4).toLong()
                FileOutputStream(tempFile).use { fos ->
                    readChunks(fis, fos, chunkSize, key, onProgress, headerBytes)
                }
            }
            commit(tempFile, outputFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 바이트 배열 한 벌 (작은 값 — 설정 스냅샷 등)
    // ──────────────────────────────────────────────────────────────────────

    fun encrypt(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encryptedData = cipher.doFinal(data)
        // [IV (12 bytes)][encrypted data + GCM tag]
        return ByteBuffer.allocate(iv.size + encryptedData.size)
            .put(iv)
            .put(encryptedData)
            .array()
    }

    fun decrypt(data: ByteArray, key: SecretKey): ByteArray {
        require(data.size > GCM_IV_LENGTH) {
            "Encrypted data too short: expected at least ${GCM_IV_LENGTH + 1} bytes"
        }
        val buffer = ByteBuffer.wrap(data)
        val iv = ByteArray(GCM_IV_LENGTH)
        buffer.get(iv)
        val encryptedData = ByteArray(buffer.remaining())
        buffer.get(encryptedData)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encryptedData)
    }

    // ── 유틸리티 ──

    /** 복호화가 성공한 뒤에만 결과를 제자리에 놓는다 — 반쪽 파일을 남기지 않는다. */
    private fun commit(tempFile: File, outputFile: File) {
        if (!tempFile.renameTo(outputFile)) {
            tempFile.copyTo(outputFile, overwrite = true)
            tempFile.delete()
        }
    }

    /**
     * 스트림에서 정확히 `buffer.size` 바이트를 읽거나, EOF에 도달할 때까지 읽는다.
     *
     * **`InputStream.read(b)`를 한 번 부르는 것으로 갈음하지 말 것** — 규격이 버퍼를 채운다고
     * 약속하지 않는다. 이 파일의 청크 형식은 *마지막을 뺀 모든 청크가 꽉 찼다*에 통째로
     * 기대므로, 그 약속을 지키는 자리가 여기 하나다.
     *
     * @return 실제 읽은 바이트 수 (0이면 EOF)
     */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }

    /**
     * 스트림에서 최대 [maxSize] 바이트를 읽는다.
     * 마지막 청크는 [maxSize]보다 짧을 수 있으므로, 실제 읽은 크기만큼의 배열을 반환.
     */
    private fun readChunkData(input: InputStream, maxSize: Int): ByteArray {
        val buffer = ByteArray(maxSize)
        val read = readFully(input, buffer)
        return if (read == maxSize) buffer else buffer.copyOfRange(0, read)
    }
}
