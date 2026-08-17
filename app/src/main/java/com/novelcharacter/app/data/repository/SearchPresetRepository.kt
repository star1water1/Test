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

    suspend fun ensureDefaultPresets() {
        val existing = dao.getAllPresetsList()
        if (existing.any { it.isDefault }) return

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
        defaults.forEach { dao.insert(it) }
    }

    companion object {
        const val DEFAULT_RECENT = "최근검색"
        const val DEFAULT_NAME = "이름우선"
        const val DEFAULT_TAG = "태그우선"
    }
}
