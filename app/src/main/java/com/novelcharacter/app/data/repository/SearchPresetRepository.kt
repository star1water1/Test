package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.SearchPresetDao
import com.novelcharacter.app.data.model.SearchPreset

class SearchPresetRepository(private val dao: SearchPresetDao) {

    val allPresets: LiveData<List<SearchPreset>> = dao.getAllPresets()

    suspend fun getAllPresetsList(): List<SearchPreset> = dao.getAllPresetsList()

    suspend fun getPresetById(id: Long): SearchPreset? = dao.getPresetById(id)

    suspend fun getPresetCount(): Int = dao.getPresetCount()

    suspend fun insertPreset(preset: SearchPreset): Long {
        val count = dao.getPresetCount()
        if (!preset.isDefault && count >= SearchPreset.MAX_PRESETS) {
            throw IllegalStateException("Maximum preset limit (${SearchPreset.MAX_PRESETS}) reached")
        }
        return dao.insert(preset)
    }

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
