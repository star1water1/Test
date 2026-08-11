package com.novelcharacter.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelcharacter.app.data.repository.TrashRetentionPolicy
import kotlinx.coroutines.flow.first

private val Context.trashSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "trash_settings")

/**
 * 휴지통 보관 정책 저장소 — 보관 **작업 수**와 보관 **기간**(B-74).
 *
 * 종전에는 둘 다 상수(30건·30일)로 박혀 있어 사용자가 고를 수 있는 것이 하나도 없었는데,
 * 초과분의 결과는 **영구 삭제**다. 같은 앱의 자동 백업이 '보관 개수 선택'을 이미 내주고 있어
 * 이 앱의 결에 맞음을 스스로 보여 주고 있었다(원칙 01 — 고정된 틀을 두지 않는다).
 *
 * ## 읽으면 곧 [TrashRetentionPolicy]에 싣는다
 *
 * 정리 코드는 `Context`가 없어 이 저장소를 직접 읽지 못한다(그 근거는 정책 파일의 KDoc).
 * 그래서 **읽는 모든 경로가 읽은 값을 그대로 정책에 싣는다** — 읽고도 안 실으면 화면에는 새 값이
 * 보이는데 정리는 옛 값으로 도는, 눈으로는 찾을 수 없는 어긋남이 생긴다.
 */
class TrashSettingsStore(private val context: Context) {

    companion object {
        private val KEY_MAX_OPERATIONS = intPreferencesKey("max_operations")
        private val KEY_RETENTION_DAYS = intPreferencesKey("retention_days")
    }

    /**
     * 현재 설정. 읽는 데 성공하면 [TrashRetentionPolicy]에 실어 정리 경로가 같은 값을 쓰게 한다.
     *
     * **실패는 삼키지 않는다** — 부르는 쪽이 처분을 정한다. 삼키고 기본값을 돌려주면 정책에
     * 추측한 값이 실려 동의 없는 영구 삭제로 이어질 수 있다.
     */
    suspend fun getSettings(): TrashRetentionPolicy.Settings {
        val prefs = context.trashSettingsDataStore.data.first()
        val settings = TrashRetentionPolicy.sanitize(
            TrashRetentionPolicy.Settings(
                maxOperations = prefs[KEY_MAX_OPERATIONS] ?: TrashRetentionPolicy.Settings().maxOperations,
                retentionDays = prefs[KEY_RETENTION_DAYS] ?: TrashRetentionPolicy.Settings().retentionDays
            )
        )
        TrashRetentionPolicy.publish(settings)
        return settings
    }

    /**
     * 앱 시작 시 정책을 채운다 — **실패해도 앱을 죽이지 않는다.**
     *
     * 실패하면 정책은 비어 있는 채로 남고 정리는 그냥 건너뛴다(휴지통이 조금 커질 뿐이다).
     * 반대로 여기서 기본값을 실으면 *모르는 채로 영구 삭제*가 되므로, 비워 두는 쪽이 안전하다.
     */
    suspend fun primeQuietly() {
        runCatching { getSettings() }
    }

    suspend fun setMaxOperations(count: Int) {
        val value = TrashRetentionPolicy.sanitize(
            TrashRetentionPolicy.Settings(maxOperations = count)
        ).maxOperations
        context.trashSettingsDataStore.edit { it[KEY_MAX_OPERATIONS] = value }
        getSettings()
    }

    suspend fun setRetentionDays(days: Int) {
        val value = TrashRetentionPolicy.sanitize(
            TrashRetentionPolicy.Settings(retentionDays = days)
        ).retentionDays
        context.trashSettingsDataStore.edit { it[KEY_RETENTION_DAYS] = value }
        getSettings()
    }
}
