package com.novelcharacter.app.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

        private val KEY_EDITOR_REMOVE_POLICY = stringPreferencesKey("editor_remove_policy")
        private val KEY_AUTO_LINK_BY_CHARACTER = booleanPreferencesKey("auto_link_by_character")
        private val KEY_ORGANIZE_FOLDER_URI = stringPreferencesKey("organize_folder_uri")

        const val DEFAULT_AUTO_LINK_BY_CHARACTER = true
    }

    /**
     * 정리 폴더(SAF 트리) URI. 지정하면 그 폴더의 이미지를 이미지 탭에서 받아올 수 있고,
     * 정리용 내보내기의 대상 폴더가 된다.
     *
     * 권한이 사라져도(폴더 삭제·권한 회수) **자동으로 지우지 않는다** — 사용자가 상황을 보고
     * 재지정할지 해제할지 정한다(조용한 설정 변경 금지).
     */
    suspend fun getOrganizeFolderUri(): String? {
        val prefs = context.imageSettingsDataStore.data.first()
        return prefs[KEY_ORGANIZE_FOLDER_URI]?.takeIf { it.isNotBlank() }
    }

    suspend fun setOrganizeFolderUri(uri: String?) {
        context.imageSettingsDataStore.edit {
            if (uri.isNullOrBlank()) it.remove(KEY_ORGANIZE_FOLDER_URI) else it[KEY_ORGANIZE_FOLDER_URI] = uri
        }
    }

    /**
     * 캐릭터 자동 링크(기본 켜짐): 한 캐릭터에 함께 등록된 이미지들을 이미지 탭의 링크
     * 그룹("같은 캐릭터 묶음")으로 자동 유지하고, 캐릭터에서 빠지면 그 링크를 자동 해제한다.
     * 꺼도 이미 만들어진 링크는 그대로 남는다(동결) — 정리는 이미지 탭의 링크 해제로 한다.
     */
    suspend fun getAutoLinkByCharacter(): Boolean {
        val prefs = context.imageSettingsDataStore.data.first()
        return prefs[KEY_AUTO_LINK_BY_CHARACTER] ?: DEFAULT_AUTO_LINK_BY_CHARACTER
    }

    suspend fun setAutoLinkByCharacter(enabled: Boolean) {
        context.imageSettingsDataStore.edit { it[KEY_AUTO_LINK_BY_CHARACTER] = enabled }
    }

    /**
     * 캐릭터 편집창에서 이미지를 제거하고 저장할 때의 파일 처리 정책.
     * - [ALWAYS_ADOPT](**기본** — B-107 D8, 사용자 확정 ⓐ "뗀 이미지는 무조건 남긴다"):
     *   어떤 이미지든 제거 시 파일을 지우지 않고 라이브러리(미배정)로 입양한다.
     *   삭제는 명시적으로 고를 때만 일어난다(편집창 2지 선택 · 이미지 탭 · `_삭제승인/`).
     * - [LIBRARY_ONLY]: 라이브러리(image_meta) 이미지는 파일을 남겨 미배정으로 복귀,
     *   편집창에서 직접 골라 넣은 일반 이미지는 삭제(공유 참조는 가드가 보호).
     *
     * ### 기본을 뒤집은 이유
     *
     * `LIBRARY_ONLY`는 **같은 조작인데 넣은 경로에 따라 결과가 갈린다** — 이미지 탭에서
     * 불러온 것은 남고 편집창에서 갤러리로 고른 것은 파일째 지워지는데, 사용자는 몇 달 전에
     * 어느 경로로 넣었는지 기억하지 못한다. 그것이 확정 ⓐ가 겨눈 자리다.
     *
     * **그래도 설정을 없애지는 않는다.** 명시적으로 고른 것이라면 사용자의 자유이고
     * (자율성 — 기능의 쓸모는 사용자가 가린다), 없애는 쪽은 엑셀 '앱 설정' 시트에 실린 키를
     * 없애는 일이라 왕복 처리가 따라붙는다(B-95의 `underbustEstimation`이 P-5를 낳은 부류).
     *
     * 기본을 뒤집으면 남는 파일이 늘지만, **골라 지울 자리가 같은 슬라이스 안에 함께 들어왔다**
     * (서랍 D3 · `_삭제승인` D6 · 편집창 2지 선택 D7).
     */
    enum class EditorRemovePolicy { LIBRARY_ONLY, ALWAYS_ADOPT }

    suspend fun getEditorRemovePolicy(): EditorRemovePolicy {
        val prefs = context.imageSettingsDataStore.data.first()
        return when (prefs[KEY_EDITOR_REMOVE_POLICY]) {
            // 고른 적이 있으면 그 값이 이긴다 — 기본을 뒤집는 것이 사용자의 선택을 덮지 않는다.
            EditorRemovePolicy.LIBRARY_ONLY.name -> EditorRemovePolicy.LIBRARY_ONLY
            else -> EditorRemovePolicy.ALWAYS_ADOPT
        }
    }

    suspend fun setEditorRemovePolicy(policy: EditorRemovePolicy) {
        context.imageSettingsDataStore.edit { it[KEY_EDITOR_REMOVE_POLICY] = policy.name }
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
