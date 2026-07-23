package com.novelcharacter.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.novelcharacter.app.util.AppLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API 키 전용 저장소 — Android Keystore(AES-256-GCM)로 암호화해 SharedPreferences에 보관한다.
 *
 * 보안 경계(엑셀 왕복 무결성과 별개로 지켜야 할 절대 규칙):
 * - 키 원문은 이 클래스 밖으로 저장되지 않는다. 설정 JSON([AiProviderStore])에도 절대 안 들어간다.
 * - 인앱 백업은 DB→xlsx 경로라 prefs 파일을 건드리지 않고, 매니페스트 allowBackup=false라
 *   OS 백업에도 실리지 않는다 → 키가 기기를 떠나는 경로가 없다.
 * - 복호화 실패(기기 이전·Keystore 초기화 등)는 조용히 삼키지 않고 null을 돌려 UI가
 *   "키 재등록 필요"를 안내하게 한다(변수 제어).
 */
class AiKeyStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasKey(configId: String): Boolean = sp.contains(dataKey(configId))

    /** 저장. 붙여넣기 시 흔한 앞뒤 공백·개행은 여기서 정리한다(잘못된 입력 교정). */
    fun putKey(configId: String, rawKey: String) {
        val key = rawKey.trim()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted // IV(12B) || ciphertext
        sp.edit()
            .putString(dataKey(configId), Base64.encodeToString(packed, Base64.NO_WRAP))
            .putString(hintKey(configId), makeHint(key))
            .apply()
    }

    /** 복호화된 키. 미등록이거나 복호화 불가면 null — 호출측은 [AiErrorKind.NO_KEY]로 안내한다. */
    fun getKey(configId: String): String? {
        val packed = sp.getString(dataKey(configId), null) ?: return null
        return try {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, masterKey(),
                GCMParameterSpec(GCM_TAG_BITS, bytes, 0, IV_LENGTH)
            )
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            // 기기 이전·Keystore 손상 등 — 데이터는 남기되 사용 불가로 보고해 재등록을 유도.
            AppLogger.error(TAG, "API 키 복호화 실패 (configId=$configId)", e)
            null
        }
    }

    /** 식별용 마스킹 힌트(예: "····abcd"). 끝 4자만 평문 보관 — 통상적 관례 수준의 노출. */
    fun keyHint(configId: String): String? = sp.getString(hintKey(configId), null)

    fun removeKey(configId: String) {
        sp.edit().remove(dataKey(configId)).remove(hintKey(configId)).apply()
    }

    private fun makeHint(key: String): String =
        if (key.length <= 4) "····" else "····" + key.takeLast(4)

    /** Keystore 마스터 키 — 최초 사용 시 생성, 이후 재사용. */
    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun dataKey(configId: String) = "key_$configId"
    private fun hintKey(configId: String) = "hint_$configId"

    companion object {
        private const val TAG = "AiKeyStore"
        private const val PREFS_NAME = "ai_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ai_keys_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
