package com.novelcharacter.app.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object BackupEncryptor {

    private const val KEYSTORE_ALIAS = "novel_character_backup_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
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

    /**
     * Encrypt a file to another file using streaming to avoid loading all data into memory.
     * Output format: [IV (12 bytes)] [encrypted data + GCM tag]
     */
    fun encryptFile(inputFile: File, outputFile: File) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv

        FileOutputStream(outputFile).use { fos ->
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos ->
                FileInputStream(inputFile).use { fis ->
                    fis.copyTo(cos, bufferSize = 8192)
                }
            }
        }
    }

    /**
     * Decrypt a file to another file using streaming to avoid loading all data into memory.
     * Input format: [IV (12 bytes)] [encrypted data + GCM tag]
     */
    fun decryptFile(inputFile: File, outputFile: File) {
        // Write to temp file first; rename only after successful GCM authentication
        val tempFile = File(outputFile.parentFile, outputFile.name + ".tmp")
        try {
            FileInputStream(inputFile).use { fis ->
                val iv = ByteArray(GCM_IV_LENGTH)
                var read = 0
                while (read < GCM_IV_LENGTH) {
                    val n = fis.read(iv, read, GCM_IV_LENGTH - read)
                    if (n == -1) throw IllegalArgumentException("Encrypted file too short")
                    read += n
                }

                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

                javax.crypto.CipherInputStream(fis, cipher).use { cis ->
                    FileOutputStream(tempFile).use { fos ->
                        cis.copyTo(fos, bufferSize = 8192)
                    }
                }
            }
            // GCM tag verified successfully — safe to commit output
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
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
}
