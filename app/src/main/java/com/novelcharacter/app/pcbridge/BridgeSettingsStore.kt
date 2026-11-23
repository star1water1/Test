package com.novelcharacter.app.pcbridge

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.SecureRandom
import kotlinx.coroutines.flow.first

/**
 * PC 동반 편집의 설정 저장소 — **이 저장소만의 파일을 쓴다**(R-28).
 *
 * ## 엑셀 '앱 설정' 시트에 싣지 않는다
 *
 * `AppSettingsKeys.EXCLUDED_STORES`에 사유와 함께 등재돼 있다. 근거가 둘인데 **뒤엣것이 더
 * 세다**:
 *
 * 1. 토큰은 비밀이다. 엑셀은 평문이고 사용자가 남에게 보내기도 하는 파일인데, 이 토큰은
 *    캐릭터 DB 전체에 대한 읽기·쓰기 권한이다.
 * 2. **[enabled]가 왕복하면 새 기기에서 서버가 저절로 켜진다.** 사용자가 그 기기에서 아무것도
 *    요청하지 않았는데 소켓이 열린다 — `backup_status`를 뺀 것과 같은 계열이고
 *    (*"하지도 않은 백업을 했다고 말한다"*), 이쪽은 그보다 결과가 무겁다.
 */
private val Context.pcBridgeDataStore: DataStore<Preferences> by preferencesDataStore(name = "pc_bridge_settings")

class BridgeSettingsStore(private val context: Context) {

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_TETHER_ONLY = booleanPreferencesKey("tether_only")
        private val KEY_PORT = intPreferencesKey("port")

        /** **기본은 꺼짐이다.** 소켓을 여는 기능은 사용자가 명시적으로 켠다. */
        const val DEFAULT_ENABLED = false

        /**
         * 케이블 전용이 기본이다 — 사용자 확정 ㄷ.
         *
         * 더 좁은 쪽을 기본으로 두는 것이 이 앱의 실패 처분과 같은 방향이다
         * (`ImagePathMatch.isInside`의 *"모르면 막는다"*). Wi-Fi로 쓰려면 사용자가 켠다.
         */
        const val DEFAULT_TETHER_ONLY = true

        /**
         * 기본 포트. 1024 이상이어야 권한 없이 열 수 있고, 흔한 개발 포트(8080·3000)를
         * 피해 골랐다 — 같은 기기의 다른 앱과 부딪히는 자리를 줄인다.
         */
        const val DEFAULT_PORT = 8787

        /** 사용자가 고칠 수 있는 범위. 1024 미만은 특권 포트라 열 수 없다. */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }

    data class BridgeSettings(
        val enabled: Boolean = DEFAULT_ENABLED,
        /** 빈 글자면 **아직 발급되지 않았다**. [ensureToken]이 그때 만든다. */
        val token: String = "",
        val tetherOnly: Boolean = DEFAULT_TETHER_ONLY,
        val port: Int = DEFAULT_PORT
    ) {
        /** 그물 판정에 넘길 모드 — 설정 한 칸이 모드 하나를 정한다. */
        val mode: BridgeAccessPolicy.Mode
            get() = if (tetherOnly) BridgeAccessPolicy.Mode.TETHER_ONLY
            else BridgeAccessPolicy.Mode.SAME_LINK
    }

    suspend fun get(): BridgeSettings {
        val prefs = context.pcBridgeDataStore.data.first()
        return BridgeSettings(
            enabled = prefs[KEY_ENABLED] ?: DEFAULT_ENABLED,
            token = prefs[KEY_TOKEN].orEmpty(),
            tetherOnly = prefs[KEY_TETHER_ONLY] ?: DEFAULT_TETHER_ONLY,
            port = (prefs[KEY_PORT] ?: DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT)
        )
    }

    suspend fun setEnabled(on: Boolean) {
        context.pcBridgeDataStore.edit { it[KEY_ENABLED] = on }
    }

    suspend fun setTetherOnly(on: Boolean) {
        context.pcBridgeDataStore.edit { it[KEY_TETHER_ONLY] = on }
    }

    suspend fun setPort(port: Int) {
        context.pcBridgeDataStore.edit { it[KEY_PORT] = port.coerceIn(MIN_PORT, MAX_PORT) }
    }

    /**
     * 토큰을 돌려준다. 없으면 **그 자리에서 만들어 저장한다.**
     *
     * `SecureRandom`을 쓴다 — 이 글자가 유일한 방벽인 모드(Wi-Fi 허용)가 있으므로
     * `Random`으로는 안 된다. 난수원을 여기서 고르고 [BridgeToken]은 받아 쓰기만 한다
     * (그 파일을 순수하게 두어 시험이 결정적 난수원을 넣을 수 있게 한 분업이다).
     */
    suspend fun ensureToken(): String {
        val existing = context.pcBridgeDataStore.data.first()[KEY_TOKEN].orEmpty()
        if (existing.isNotEmpty()) return existing
        val random = SecureRandom()
        val fresh = BridgeToken.generate { bound -> random.nextInt(bound) }
        context.pcBridgeDataStore.edit { it[KEY_TOKEN] = fresh }
        return fresh
    }

    /**
     * 토큰을 새로 발급한다 — **옛 토큰이 적힌 PC 즐겨찾기는 그 즉시 못 쓴다.**
     * 화면이 그 사실을 먼저 알리고 부른다(고지 없이 끊는 길을 두지 않는다).
     */
    suspend fun regenerateToken(): String {
        val random = SecureRandom()
        val fresh = BridgeToken.generate { bound -> random.nextInt(bound) }
        context.pcBridgeDataStore.edit { it[KEY_TOKEN] = fresh }
        return fresh
    }
}
