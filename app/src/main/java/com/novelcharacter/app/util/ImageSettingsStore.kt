package com.novelcharacter.app.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.imageSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "image_settings")

/**
 * 이미지 불러오기 자동 압축 설정 저장소.
 *
 * 목적은 "앱 용량 ↔ 이미지 화질의 적정선 조절"이다. 두 레버를 제공한다:
 * 최대 해상도 상한(용량을 좌우하는 주 레버 — 대부분의 사진은 표시에 필요한 것보다 훨씬 큼)과
 * JPEG 품질 %(미세조정). 기본값은 좋은 sweet spot이되 [enabled]가 false라, 켜기 전에는 어떤 압축도
 * 하지 않고 원본을 그대로 보관한다(요구사항: 설정 안 하면 압축 안 함). 값을 명시 설정하면 그 값이 유지된다.
 */
class ImageSettingsStore(private val context: Context) {

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("compress_enabled")
        private val KEY_QUALITY = intPreferencesKey("quality_percent")
        private val KEY_CAP_DIMENSION = booleanPreferencesKey("cap_dimension")
        private val KEY_MAX_LONG_EDGE = intPreferencesKey("max_long_edge_px")
        private val KEY_SKIP_BELOW_ENABLED = booleanPreferencesKey("skip_below_enabled")
        private val KEY_SKIP_BELOW_BYTES = longPreferencesKey("skip_below_bytes")

        const val DEFAULT_ENABLED = false
        const val DEFAULT_QUALITY = 85
        const val DEFAULT_CAP_DIMENSION = true
        const val DEFAULT_MAX_LONG_EDGE = 2048
        const val DEFAULT_SKIP_BELOW_ENABLED = false
        const val DEFAULT_SKIP_BELOW_BYTES = 512L * 1024L

        const val MIN_QUALITY = 10
        const val MAX_QUALITY = 100
        const val MIN_LONG_EDGE = 512
        const val MAX_LONG_EDGE = 8192

        /** 최대 해상도(긴변) 선택지 */
        val MAX_LONG_EDGE_CHOICES = listOf(1024, 2048, 4096, 8192)

        /** "일정 이하 압축 안 함" 임계값 선택지(바이트) */
        val SKIP_BELOW_CHOICES = listOf(256L * 1024, 512L * 1024, 1024L * 1024, 2048L * 1024)
    }

    data class ImageSettings(
        val enabled: Boolean = DEFAULT_ENABLED,
        val qualityPercent: Int = DEFAULT_QUALITY,
        val capDimension: Boolean = DEFAULT_CAP_DIMENSION,
        val maxLongEdgePx: Int = DEFAULT_MAX_LONG_EDGE,
        val skipBelowEnabled: Boolean = DEFAULT_SKIP_BELOW_ENABLED,
        val skipBelowBytes: Long = DEFAULT_SKIP_BELOW_BYTES
    )

    suspend fun getSettings(): ImageSettings {
        val prefs = context.imageSettingsDataStore.data.first()
        return ImageSettings(
            enabled = prefs[KEY_ENABLED] ?: DEFAULT_ENABLED,
            qualityPercent = (prefs[KEY_QUALITY] ?: DEFAULT_QUALITY).coerceIn(MIN_QUALITY, MAX_QUALITY),
            capDimension = prefs[KEY_CAP_DIMENSION] ?: DEFAULT_CAP_DIMENSION,
            maxLongEdgePx = (prefs[KEY_MAX_LONG_EDGE] ?: DEFAULT_MAX_LONG_EDGE).coerceIn(MIN_LONG_EDGE, MAX_LONG_EDGE),
            skipBelowEnabled = prefs[KEY_SKIP_BELOW_ENABLED] ?: DEFAULT_SKIP_BELOW_ENABLED,
            skipBelowBytes = (prefs[KEY_SKIP_BELOW_BYTES] ?: DEFAULT_SKIP_BELOW_BYTES).coerceAtLeast(0L)
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.imageSettingsDataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setQualityPercent(quality: Int) {
        context.imageSettingsDataStore.edit { it[KEY_QUALITY] = quality.coerceIn(MIN_QUALITY, MAX_QUALITY) }
    }

    suspend fun setCapDimension(cap: Boolean) {
        context.imageSettingsDataStore.edit { it[KEY_CAP_DIMENSION] = cap }
    }

    suspend fun setMaxLongEdgePx(px: Int) {
        context.imageSettingsDataStore.edit { it[KEY_MAX_LONG_EDGE] = px.coerceIn(MIN_LONG_EDGE, MAX_LONG_EDGE) }
    }

    suspend fun setSkipBelowEnabled(enabled: Boolean) {
        context.imageSettingsDataStore.edit { it[KEY_SKIP_BELOW_ENABLED] = enabled }
    }

    suspend fun setSkipBelowBytes(bytes: Long) {
        context.imageSettingsDataStore.edit { it[KEY_SKIP_BELOW_BYTES] = bytes.coerceAtLeast(0L) }
    }
}
