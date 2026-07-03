package com.novelcharacter.app.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.backupSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "backup_settings")

/**
 * 자동 백업 옵션 저장소 — 이미지 포함 여부, 보관 개수.
 * 기본값은 기존 동작(이미지 포함, 3개 보관)과 동일하며, 사용자가 자율적으로 조정할 수 있다.
 */
class BackupSettingsStore(private val context: Context) {

    companion object {
        private val KEY_INCLUDE_IMAGES = booleanPreferencesKey("include_images")
        private val KEY_MAX_BACKUPS = intPreferencesKey("max_backups")

        const val DEFAULT_INCLUDE_IMAGES = true
        const val DEFAULT_MAX_BACKUPS = 3

        /** 보관 개수 선택지 */
        val MAX_BACKUPS_CHOICES = listOf(1, 2, 3, 5, 10)
    }

    data class BackupSettings(
        val includeImages: Boolean = DEFAULT_INCLUDE_IMAGES,
        val maxBackups: Int = DEFAULT_MAX_BACKUPS
    )

    suspend fun getSettings(): BackupSettings {
        val prefs = context.backupSettingsDataStore.data.first()
        return BackupSettings(
            includeImages = prefs[KEY_INCLUDE_IMAGES] ?: DEFAULT_INCLUDE_IMAGES,
            maxBackups = (prefs[KEY_MAX_BACKUPS] ?: DEFAULT_MAX_BACKUPS).coerceAtLeast(1)
        )
    }

    suspend fun setIncludeImages(include: Boolean) {
        context.backupSettingsDataStore.edit { it[KEY_INCLUDE_IMAGES] = include }
    }

    suspend fun setMaxBackups(count: Int) {
        context.backupSettingsDataStore.edit { it[KEY_MAX_BACKUPS] = count.coerceAtLeast(1) }
    }
}
