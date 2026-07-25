package com.novelcharacter.app.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.backupDataStore: DataStore<Preferences> by preferencesDataStore(name = "backup_status")

class BackupStatusStore(private val context: Context) {

    companion object {
        private val KEY_LAST_SUCCESS_AT = longPreferencesKey("last_success_at")
        private val KEY_LAST_FAILURE_AT = longPreferencesKey("last_failure_at")
        private val KEY_LAST_FAILURE_REASON = stringPreferencesKey("last_failure_reason")
        private val KEY_LAST_IMAGE_WARNING = stringPreferencesKey("last_image_warning")
    }

    data class BackupStatus(
        val lastSuccessAt: Long = 0,
        val lastFailureAt: Long = 0,
        val lastFailureReason: String = "",
        /** 마지막 성공 백업에서 이미지가 일부/전부 빠졌을 때의 고지. 없으면 빈 문자열. */
        val lastImageWarning: String = ""
    )

    /**
     * @param imageWarning 이번 회차의 이미지 제외 고지. null/빈 값이면 이전 고지를 **지운다** —
     *        지난 회차 경고가 남아 현재 백업 상태와 어긋나는 것을 막는다(사실과 다른 경고 금지).
     */
    suspend fun recordSuccess(imageWarning: String? = null) {
        context.backupDataStore.edit { prefs ->
            prefs[KEY_LAST_SUCCESS_AT] = System.currentTimeMillis()
            if (imageWarning.isNullOrBlank()) prefs.remove(KEY_LAST_IMAGE_WARNING)
            else prefs[KEY_LAST_IMAGE_WARNING] = imageWarning
        }
    }

    suspend fun recordFailure(reason: String) {
        context.backupDataStore.edit { prefs ->
            prefs[KEY_LAST_FAILURE_AT] = System.currentTimeMillis()
            prefs[KEY_LAST_FAILURE_REASON] = reason
        }
    }

    suspend fun getStatus(): BackupStatus {
        val prefs = context.backupDataStore.data.first()
        return BackupStatus(
            lastSuccessAt = prefs[KEY_LAST_SUCCESS_AT] ?: 0,
            lastFailureAt = prefs[KEY_LAST_FAILURE_AT] ?: 0,
            lastFailureReason = prefs[KEY_LAST_FAILURE_REASON] ?: "",
            lastImageWarning = prefs[KEY_LAST_IMAGE_WARNING] ?: ""
        )
    }

    suspend fun clear() {
        context.backupDataStore.edit { it.clear() }
    }
}
