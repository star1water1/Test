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
     * Decrypt a file to another file.
     * Uses doFinal()-based decryption to guarantee GCM authentication tag verification.
     * CipherInputStream may silently skip tag validation on some Android versions,
     * so we read the ciphertext fully and use doFinal() which always verifies the tag.
     * Input format: [IV (12 bytes)] [encrypted data + GCM tag]
     */
    private const val MAX_DECRYPT_FILE_SIZE = 256L * 1024 * 1024 // 256MB

    fun decryptFile(inputFile: File, outputFile: File) {
        val tempFile = File(outputFile.parentFile, outputFile.name + ".tmp")
        try {
            val fileSize = inputFile.length()
            require(fileSize > GCM_IV_LENGTH) {
                "Encrypted file too short: expected at least ${GCM_IV_LENGTH + 1} bytes"
            }
            require(fileSize <= MAX_DECRYPT_FILE_SIZE) {
                "Encrypted file too large: ${fileSize / (1024 * 1024)}MB exceeds ${MAX_DECRYPT_FILE_SIZE / (1024 * 1024)}MB limit"
            }
            val fileBytes = inputFile.readBytes()

            val iv = fileBytes.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = fileBytes.copyOfRange(GCM_IV_LENGTH, fileBytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

            // doFinal() guarantees GCM authentication tag verification;
            // throws AEADBadTagException if the file has been tampered with
            val decrypted = cipher.doFinal(ciphertext)

            FileOutputStream(tempFile).use { fos ->
                fos.write(decrypted)
            }

            // Tag verified successfully — safe to commit output
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
}
