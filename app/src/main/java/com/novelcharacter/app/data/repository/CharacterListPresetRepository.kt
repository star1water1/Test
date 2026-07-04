package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.CharacterListPresetDao
import com.novelcharacter.app.data.model.CharacterListPreset

/**
 * 캐릭터 탭 필터/정렬 프리셋 저장소. `SearchPresetRepository` 패턴을 따르며 상한(20)을 강제한다.
 */
class CharacterListPresetRepository(private val dao: CharacterListPresetDao) {

    val allPresets: LiveData<List<CharacterListPreset>> = dao.getAllPresets()

    suspend fun getAllPresetsList(): List<CharacterListPreset> = dao.getAllPresetsList()

    suspend fun getPresetById(id: Long): CharacterListPreset? = dao.getPresetById(id)

    suspend fun getPresetCount(): Int = dao.getPresetCount()

    /** @throws IllegalStateException 상한 초과 시 (호출부가 사용자에게 통보) */
    suspend fun insertPreset(preset: CharacterListPreset): Long {
        val count = dao.getPresetCount()
        if (!preset.isDefault && count >= CharacterListPreset.MAX_PRESETS) {
            throw IllegalStateException("Maximum preset limit (${CharacterListPreset.MAX_PRESETS}) reached")
        }
        return dao.insert(preset)
    }

    suspend fun updatePreset(preset: CharacterListPreset) {
        dao.update(preset.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePreset(id: Long) {
        dao.deleteById(id)
    }
}
