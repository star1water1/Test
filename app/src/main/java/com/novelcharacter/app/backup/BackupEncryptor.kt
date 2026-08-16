package com.novelcharacter.app.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 백업 암·복호화의 **기기 키 계층** — AndroidKeyStore에서 키를 얻고, 형식 일은
 * [BackupChunkFormat]에 넘긴다.
 *
 * ## 왜 이 파일에는 형식이 없는가 (B-230 ⓐ, 2026.08.16)
 *
 * 종전에는 이 파일 하나가 426줄로 세 형식(NCB2 청크 · v1 레거시 · NCP1 패스프레이즈)의 틀 짜기와
 * 키 조달을 함께 들었고, **감사도 시험도 0**이었다. 닿지 못한 이유는 아래 `android.security.keystore`
 * import 둘이다 — 그 둘이 있으면 순수 JVM 하네스가 이 파일을 컴파일조차 못 한다.
 *
 * 그래서 **형식은 [BackupChunkFormat]으로 내렸다.** 그쪽은 `javax.crypto`만 쓰므로 하네스가 통째로
 * 잰다(`BackupChunkFormatTest`). 이 파일에 남은 것은 *키를 어디서 얻는가* 하나이고, 그 부분은
 * 원리적으로 실기기에서만 증명된다.
 *
 * **공개 API는 종전 그대로다** — 부르는 자리(설정 화면·자동 백업 워커)는 바뀌지 않았다.
 */
object BackupEncryptor {

    private const val KEYSTORE_ALIAS = "novel_character_backup_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** 패스프레이즈 최소 길이 — 화면이 입력 검증에 그대로 쓴다. */
    const val MIN_PASSPHRASE_LENGTH = BackupChunkFormat.MIN_PASSPHRASE_LENGTH

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

    // ── 기기 키 형식 (v2 NCB2 / v1 레거시) ──

    fun encrypt(data: ByteArray): ByteArray = BackupChunkFormat.encrypt(data, getOrCreateKey())

    fun decrypt(data: ByteArray): ByteArray = BackupChunkFormat.decrypt(data, getOrCreateKey())

    /** 청크 GCM(v2)으로 파일을 암호화한다. 피크 메모리는 청크 하나 몫이다. */
    fun encryptFile(inputFile: File, outputFile: File) =
        BackupChunkFormat.encryptFileWithKey(inputFile, outputFile, getOrCreateKey())

    /**
     * v1(레거시)/v2(청크)를 자동 감지해 복호화한다.
     *
     * @param onProgress 지금까지 **읽은 입력 바이트**(누적). 총량은 `inputFile.length()`다.
     *   복호화는 수백 MB짜리 백업에서 분 단위가 걸리는데 종전에는 불확정 스피너뿐이라
     *   "도는 중"과 "멈춤"이 구분되지 않았다(규약 R-26 · B-51).
     */
    fun decryptFile(inputFile: File, outputFile: File, onProgress: ((Long) -> Unit)? = null) =
        BackupChunkFormat.decryptFileWithKey(inputFile, outputFile, ::getOrCreateKey, onProgress)

    // ── 이식 가능(패스프레이즈) 형식 — 키가 기기에 매이지 않아 전부 순수 계층에 있다 ──

    /** 파일이 이식 가능(NCP1) 형식인지 검사한다. 복원 진입 시 암호 입력 여부 분기에 쓴다. */
    fun isPortableFormat(file: File): Boolean = BackupChunkFormat.isPortableFormat(file)

    /** 패스프레이즈 기반 이식 가능 암호화. 기기 이전/공유용. */
    fun encryptFilePortable(
        inputFile: File,
        outputFile: File,
        passphrase: CharArray,
        onProgress: ((Long) -> Unit)? = null
    ) = BackupChunkFormat.encryptFilePortable(
        inputFile, outputFile, passphrase, BackupChunkFormat.PBKDF2_ITERATIONS, onProgress
    )

    /**
     * 이식 가능(NCP1) 형식 복호화. 잘못된 암호는 첫 청크의 GCM 태그 검증 실패
     * (`javax.crypto.AEADBadTagException`)로 감지된다.
     */
    fun decryptFilePortable(
        inputFile: File,
        outputFile: File,
        passphrase: CharArray,
        onProgress: ((Long) -> Unit)? = null
    ) = BackupChunkFormat.decryptFilePortable(inputFile, outputFile, passphrase, onProgress)
}
