package com.novelcharacter.app.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object BackupEncryptor {

    private const val KEYSTORE_ALIAS = "novel_character_backup_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128        // bits
    private const val GCM_TAG_BYTES = GCM_TAG_LENGTH / 8  // 16 bytes
    private const val GCM_IV_LENGTH = 12           // bytes

    // v2 청크 암호화 형식
    private const val CHUNK_SIZE = 1024 * 1024     // 1MB
    private val FORMAT_MAGIC = byteArrayOf(0x4E, 0x43, 0x42, 0x32)  // "NCB2"
    private const val FORMAT_HEADER_SIZE = 8        // MAGIC(4) + chunkSize(4)

    // v1 레거시 제한 (전체 로드 방식이므로 메모리 보호용)
    private const val MAX_LEGACY_FILE_SIZE = 256L * 1024 * 1024 // 256MB

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let { entry ->
            if (entry is KeyStore.SecretKeyEntry) {
                return entry.secretKey
            }
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encryptedData = cipher.doFinal(data)

        // Prepend IV to ciphertext: [IV (12 bytes)] [encrypted data + GCM tag]
        return ByteBuffer.allocate(iv.size + encryptedData.size)
            .put(iv)
            .put(encryptedData)
            .array()
    }

    // Encrypt a file using chunked GCM encryption (v2 format).
    // 각 청크를 독립적으로 암호화하여 복호화 시 청크 단위로 처리 가능.
    // 피크 메모리: ~2MB (CHUNK_SIZE + 암호화 결과)
    //
    // Output format (v2):
    //   MAGIC "NCB2"(4B) + chunkSize(4B)
    //   chunk1_IV(12B) + chunk1_encrypted + GCM tag
    //   chunk2_IV(12B) + chunk2_encrypted + GCM tag
    //   ...
    fun encryptFile(inputFile: File, outputFile: File) {
        FileOutputStream(outputFile).use { fos ->
            // v2 헤더
            fos.write(FORMAT_MAGIC)
            fos.write(ByteBuffer.allocate(4).putInt(CHUNK_SIZE).array())

            // 청크 단위 암호화
            FileInputStream(inputFile).use { fis ->
                val buffer = ByteArray(CHUNK_SIZE)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                    val iv = cipher.iv  // 12 bytes, 청크마다 새 IV

                    val encrypted = cipher.doFinal(buffer, 0, bytesRead)

                    fos.write(iv)
                    fos.write(encrypted)  // 암호문 + GCM 태그
                }
            }
        }
    }

    // Decrypt a file. 자동으로 v1(레거시)/v2(청크) 형식을 감지한다.
    //
    // v1: IV(12B) + 전체 암호문 + GCM tag — 전체 로드, 기존 백업 호환
    // v2: MAGIC "NCB2"(4B) + chunkSize(4B) + chunks — 청크 단위 복호화
    //
    // doFinal() 기반으로 GCM 인증 태그를 항상 검증.
    // CipherInputStream은 일부 Android 버전에서 태그 검증을 건너뛸 수 있으므로 사용하지 않음.
    fun decryptFile(inputFile: File, outputFile: File) {
        val tempFile = File(outputFile.parentFile, outputFile.name + ".tmp")
        try {
            val fis = FileInputStream(inputFile)
            try {
                // 헤더 읽기 (최소 8바이트: v2 헤더 또는 v1 IV의 앞부분)
                val header = ByteArray(FORMAT_HEADER_SIZE)
                val headerRead = readFully(fis, header)

                if (headerRead >= FORMAT_HEADER_SIZE &&
                    header[0] == FORMAT_MAGIC[0] && header[1] == FORMAT_MAGIC[1] &&
                    header[2] == FORMAT_MAGIC[2] && header[3] == FORMAT_MAGIC[3]
                ) {
                    // v2: 청크 단위 복호화
                    val chunkSize = ByteBuffer.wrap(header, 4, 4).int
                    require(chunkSize in 1..CHUNK_SIZE * 2) {
                        "Invalid chunk size in backup header: $chunkSize"
                    }
                    decryptChunked(fis, tempFile, chunkSize)
                } else {
                    // v1: 레거시 전체 로드 (헤더를 이미 소비했으므로 새로 열어야 함)
                    fis.close()
                    decryptLegacy(inputFile, tempFile)
                }
            } finally {
                try { fis.close() } catch (_: Exception) { }
            }

            // 복호화 성공 — 출력 파일 확정
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * v2 청크 복호화. 각 청크를 독립적으로 복호화하여 메모리 사용 최소화.
     * 피크 메모리: ~2MB (암호문 청크 + 복호화 결과)
     */
    private fun decryptChunked(fis: InputStream, outputFile: File, chunkSize: Int) {
        val maxEncryptedChunkSize = chunkSize + GCM_TAG_BYTES
        FileOutputStream(outputFile).use { fos ->
            while (true) {
                // IV 읽기
                val iv = ByteArray(GCM_IV_LENGTH)
                val ivRead = readFully(fis, iv)
                if (ivRead == 0) break  // EOF — 모든 청크 처리 완료
                require(ivRead == GCM_IV_LENGTH) { "Incomplete chunk IV: read $ivRead bytes" }

                // 암호문+태그 읽기 (마지막 청크는 chunkSize보다 짧을 수 있음)
                val encryptedChunk = readChunkData(fis, maxEncryptedChunkSize)
                require(encryptedChunk.isNotEmpty()) { "Empty encrypted chunk" }

                // 복호화 + GCM 태그 검증
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                val decrypted = cipher.doFinal(encryptedChunk)
                fos.write(decrypted)
            }
        }
    }

    /**
     * v1 레거시 복호화. 기존 백업 파일과의 하위호환.
     * readBytes() 대신 직접 읽기로 불필요한 배열 복사 제거.
     * 피크 메모리: ~2x 파일 크기 (암호문 + 복호화 결과)
     */
    private fun decryptLegacy(inputFile: File, outputFile: File) {
        val fileSize = inputFile.length()
        require(fileSize > GCM_IV_LENGTH) {
            "Encrypted file too short: expected at least ${GCM_IV_LENGTH + 1} bytes"
        }
        require(fileSize <= MAX_LEGACY_FILE_SIZE) {
            "Encrypted file too large: ${fileSize / (1024 * 1024)}MB exceeds ${MAX_LEGACY_FILE_SIZE / (1024 * 1024)}MB limit. " +
                "Re-export backup with the latest app version to use the efficient chunked format."
        }

        FileInputStream(inputFile).use { fis ->
            // IV 읽기
            val iv = ByteArray(GCM_IV_LENGTH)
            readFully(fis, iv)

            // 암호문 직접 읽기 (copyOfRange 제거)
            val ciphertextSize = (fileSize - GCM_IV_LENGTH).toInt()
            val ciphertext = ByteArray(ciphertextSize)
            var totalRead = 0
            while (totalRead < ciphertextSize) {
                val read = fis.read(ciphertext, totalRead, ciphertextSize - totalRead)
                if (read == -1) break
                totalRead += read
            }

            // doFinal()로 GCM 태그 검증 + 복호화
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = cipher.doFinal(ciphertext)

            FileOutputStream(outputFile).use { fos -> fos.write(decrypted) }
        }
    }

    /**
     * Check whether the backup encryption key exists in Android KeyStore.
     * Returns false if the key has been lost (e.g. after factory reset or KeyStore wipe).
     */
    fun isKeyAvailable(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.containsAlias(KEYSTORE_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    fun decrypt(data: ByteArray): ByteArray {
        require(data.size > GCM_IV_LENGTH) { "Encrypted data too short: expected at least ${GCM_IV_LENGTH + 1} bytes" }
        val buffer = ByteBuffer.wrap(data)
        val iv = ByteArray(GCM_IV_LENGTH)
        buffer.get(iv)
        val encryptedData = ByteArray(buffer.remaining())
        buffer.get(encryptedData)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
        return cipher.doFinal(encryptedData)
    }

    // ── 유틸리티 ──

    /**
     * 스트림에서 정확히 buffer.size 바이트를 읽거나, EOF에 도달할 때까지 읽는다.
     * @return 실제 읽은 바이트 수
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
     * 스트림에서 최대 maxSize 바이트를 읽는다.
     * 마지막 청크는 maxSize보다 짧을 수 있으므로, 실제 읽은 크기만큼의 배열을 반환.
     */
    private fun readChunkData(input: InputStream, maxSize: Int): ByteArray {
        val buffer = ByteArray(maxSize)
        val read = readFully(input, buffer)
        return if (read == maxSize) buffer else buffer.copyOfRange(0, read)
    }
}
