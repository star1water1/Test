package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.SearchPresetDao
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.util.PresetLimit

class SearchPresetRepository(private val dao: SearchPresetDao) {

    val allPresets: LiveData<List<SearchPreset>> = dao.getAllPresets()

    suspend fun getAllPresetsList(): List<SearchPreset> = dao.getAllPresetsList()

    suspend fun getPresetCount(): Int = dao.getPresetCount()

    suspend fun getPresetById(id: Long): SearchPreset? = dao.getPresetById(id)

    /** 이 이름을 이미 쓰고 있는 프리셋 — 저장 전 겹침 판정(B-191). 없으면 null. */
    suspend fun getPresetByName(name: String): SearchPreset? = dao.getPresetByName(name)

    /** '다른 이름으로 저장'이 제안 이름을 지을 때 쓰는 이름 전수. */
    suspend fun getAllNames(): List<String> = dao.getAllNames()

    /**
     * 저장한다 — **개수로 막지 않는다**(B-75, 확정 19번 ㄱ1: 권고로 통일).
     * 권고 초과는 [exceedsRecommended]로 물어 호출부가 고지한다.
     */
    suspend fun insertPreset(preset: SearchPreset): Long = dao.insert(preset)

    /** 지금 개수가 권고 한도를 넘었는가 — 저장 뒤 한 줄 고지의 근거. */
    suspend fun exceedsRecommended(): Boolean = PresetLimit.exceeded(dao.getPresetCount())

    suspend fun updatePreset(preset: SearchPreset) {
        dao.update(preset.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePreset(id: Long) {
        dao.deleteById(id)
    }

    /**
     * 기본 프리셋 셋을 세운다 — **이름이 이미 쓰이고 있으면 그것만 건너뛴다**(B-191 콜드 검토).
     *
     * ## 왜 건너뛰는가 — REPLACE 시절에 이 자리가 무엇을 했는지가 근거다
     * 종전 `@Insert`는 REPLACE라, 사용자가 `최근검색`이라는 이름의 프리셋을 갖고 있는 채로
     * 여기 들어오면 **그 프리셋을 조용히 지우고** 기본 프리셋을 얹었다. B-191이 그 기전을
     * ABORT로 걷자 같은 자리가 **예외**가 됐는데, 이 함수는 ViewModel `init`의
     * `viewModelScope.launch` 안에서 `try` 없이 불린다 — 즉 **전역 검색 탭을 열 때마다 앱이 죽는다.**
     * 게다가 기본 프리셋이 하나도 안 서므로 위 가드가 영영 일찍 돌아오지 않아 **매번 죽는다.**
     *
     * **닿는 길이 있다:** 설정의 초기화가 프리셋을 통째로 지우고(`deleteAll`), 엑셀 가져오기가
     * `기본` 열이 비거나 없는 파일에서 `isDefault = false`로 행을 들인다 — 그 파일에 `최근검색`이
     * 있으면(앱이 내보낸 파일이라 흔하다) 다음 진입에서 정확히 그 상태가 된다.
     *
     * **건너뛰는 것이 옳은 처분이다:** 그 이름은 이미 사용자 것이고, 기본 프리셋을 못 세우는
     * 대가는 *칩 하나가 없는 것*이지만 덮어쓰는 대가는 **사용자가 짜 둔 검색 조합의 소멸**이다.
     * 셋 다 겹쳐 하나도 못 세우면 다음 진입에서 다시 시도한다 — 조회 한 번이라 값이 싸다.
     */
    suspend fun ensureDefaultPresets() {
        val existing = dao.getAllPresetsList()
        if (existing.any { it.isDefault }) return
        val taken = existing.mapTo(HashSet()) { it.name }

        val defaults = listOf(
            SearchPreset(
                name = DEFAULT_RECENT,
                query = "",
                sortMode = SearchPreset.SORT_RECENT,
                isDefault = true
            ),
            SearchPreset(
                name = DEFAULT_NAME,
                query = "",
                sortMode = SearchPreset.SORT_NAME,
                isDefault = true
            ),
            SearchPreset(
                name = DEFAULT_TAG,
                query = "",
                sortMode = SearchPreset.SORT_TAG,
                isDefault = true
            )
        )
        defaults.filter { it.name !in taken }.forEach { dao.insert(it) }
    }

    companion object {
        const val DEFAULT_RECENT = "최근검색"
        const val DEFAULT_NAME = "이름우선"
        const val DEFAULT_TAG = "태그우선"
    }
}
