package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.CharacterListPresetDao
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.util.PresetLimit

/**
 * 캐릭터 탭 필터/정렬 프리셋 저장소. `SearchPresetRepository` 패턴을 따르며,
 * 개수 한도는 **권고**다(`PresetLimit` — B-75). 넘어도 저장하고 호출부가 말한다.
 */
class CharacterListPresetRepository(private val dao: CharacterListPresetDao) {

    val allPresets: LiveData<List<CharacterListPreset>> = dao.getAllPresets()

    suspend fun getAllPresetsList(): List<CharacterListPreset> = dao.getAllPresetsList()

    suspend fun getPresetById(id: Long): CharacterListPreset? = dao.getPresetById(id)

    suspend fun getPresetCount(): Int = dao.getPresetCount()

    /** 이 이름을 이미 쓰고 있는 프리셋 — 저장 전 겹침 판정(B-191). 없으면 null. */
    suspend fun getPresetByName(name: String): CharacterListPreset? = dao.getPresetByName(name)

    /** '다른 이름으로 저장'이 제안 이름을 지을 때 쓰는 이름 전수. */
    suspend fun getAllNames(): List<String> = dao.getAllNames()

    /**
     * 저장한다 — **개수로 막지 않는다**(B-75, 확정 19번 ㄱ1: 권고로 통일).
     * 권고 초과는 [exceedsRecommended]로 물어 호출부가 고지한다.
     */
    suspend fun insertPreset(preset: CharacterListPreset): Long = dao.insert(preset)

    /** 지금 개수가 권고 한도를 넘었는가 — 저장 뒤 한 줄 고지의 근거. */
    suspend fun exceedsRecommended(): Boolean = PresetLimit.exceeded(dao.getPresetCount())

    suspend fun updatePreset(preset: CharacterListPreset) {
        dao.update(preset.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePreset(id: Long) {
        dao.deleteById(id)
    }
}
