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
    }

    data class BackupStatus(
        val lastSuccessAt: Long = 0,
        val lastFailureAt: Long = 0,
        val lastFailureReason: String = ""
    )

    suspend fun recordSuccess() {
        context.backupDataStore.edit { prefs ->
            prefs[KEY_LAST_SUCCESS_AT] = System.currentTimeMillis()
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
            lastFailureReason = prefs[KEY_LAST_FAILURE_REASON] ?: ""
        )
    }
}
