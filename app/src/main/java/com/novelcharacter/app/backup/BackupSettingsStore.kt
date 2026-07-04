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
 *
 * 이미지 포함 기본값은 false(데이터 전용)이다. 자동 백업 .enc는 이미지가 든 filesDir 안에
 * 저장되므로 이미지를 포함해도 filesDir 손실에 대한 추가 보호가 없고(순수 용량 낭비),
 * 기기 이전은 '이미지 포함 내보내기'가 담당한다. 사용자는 이미지 포함/보관 개수를 자율 조정할 수 있으며,
 * 기존에 값을 명시 설정한 사용자는 그 값이 그대로 유지된다(기본값은 미설정일 때만 적용).
 */
class BackupSettingsStore(private val context: Context) {

    companion object {
        private val KEY_INCLUDE_IMAGES = booleanPreferencesKey("include_images")
        private val KEY_MAX_BACKUPS = intPreferencesKey("max_backups")

        // 데이터 전용이 sane default — 이미지는 filesDir에 영구 보관되므로 매일 재복사할 이유가 없다.
        const val DEFAULT_INCLUDE_IMAGES = false
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
