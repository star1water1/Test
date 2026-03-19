package com.novelcharacter.app.ui.universe

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.RecentActivity
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.UserPresetTemplate
import com.novelcharacter.app.util.PresetTemplates
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class UniverseViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val db = app.database
    private val universeRepository = app.universeRepository
    private val novelRepository = app.novelRepository
    private val characterRepository = app.characterRepository
    private val recentActivityDao = app.recentActivityDao
    private val userPresetDao = db.userPresetTemplateDao()
    val allUniverses: LiveData<List<Universe>> = universeRepository.allUniverses
    val recentActivities: LiveData<List<RecentActivity>> = recentActivityDao.getRecentActivities(5)
    val userPresets: LiveData<List<UserPresetTemplate>> = userPresetDao.getAllTemplates()

    // 각 세계관의 작품 수, 필드 수를 캐시
    private val _universeNovelCounts = MutableLiveData<Map<Long, Int>>()
    val universeNovelCounts: LiveData<Map<Long, Int>> = _universeNovelCounts

    private val _universeFieldCounts = MutableLiveData<Map<Long, Int>>()
    val universeFieldCounts: LiveData<Map<Long, Int>> = _universeFieldCounts

    private var loadCountsJob: Job? = null

    fun loadCounts(universes: List<Universe>) {
        loadCountsJob?.cancel()
        loadCountsJob = viewModelScope.launch {
            val ids = universes.map { it.id }
            _universeNovelCounts.value = universeRepository.getNovelCountsByUniverses(ids)
            _universeFieldCounts.value = universeRepository.getFieldCountsByUniverses(ids)
        }
    }

    fun insertUniverse(universe: Universe) = viewModelScope.launch {
        try {
            universeRepository.insertUniverse(universe)
        } catch (e: Exception) {
            Log.e("UniverseViewModel", "Failed to insert universe", e)
        }
    }

    fun updateUniverse(universe: Universe) = viewModelScope.launch {
        try {
            universeRepository.updateUniverse(universe)
        } catch (e: Exception) {
            Log.e("UniverseViewModel", "Failed to update universe", e)
        }
    }

    fun deleteUniverse(universe: Universe) = viewModelScope.launch {
        try {
            universeRepository.deleteUniverse(universe)
        } catch (e: Exception) {
            Log.e("UniverseViewModel", "Failed to delete universe", e)
        }
    }

    fun updateDisplayOrders(universes: List<Universe>) = viewModelScope.launch {
        try {
            universeRepository.updateUniverseDisplayOrders(universes)
        } catch (e: Exception) {
            Log.e("UniverseViewModel", "Failed to update display orders", e)
        }
    }

    fun getPresetTemplates(): List<PresetTemplates.PresetTemplate> =
        PresetTemplates.getBuiltInTemplates()

    /** 현재 세계관의 필드 구성을 사용자 프리셋으로 저장 */
    fun saveAsUserPreset(universeId: Long, name: String, description: String) = viewModelScope.launch {
        try {
            val fields = universeRepository.getFieldsByUniverseList(universeId)
            val json = PresetTemplates.fieldsToJson(fields)
            userPresetDao.insert(UserPresetTemplate(
                name = name,
                description = description,
                fieldsJson = json
            ))
        } catch (e: Exception) {
            Log.e("UniverseViewModel", "Failed to save user preset", e)
        }
    }

    /** 사용자 프리셋 업데이트 */
    fun updateUserPreset(preset: UserPresetTemplate) = viewModelScope.launch {
        try {
            userPresetDao.update(preset.copy(updatedAt = System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.e("UniverseViewModel", "Failed to update user preset", e)
        }
    }

    /** 사용자 프리셋 삭제 */
    fun deleteUserPreset(preset: UserPresetTemplate) = viewModelScope.launch {
        try {
            userPresetDao.delete(preset)
        } catch (e: Exception) {
            Log.e("UniverseViewModel", "Failed to delete user preset", e)
        }
    }

    // SingleLiveEvent pattern: wrap value so observer consumes it only once
    class Event<out T>(private val content: T) {
        private var hasBeenHandled = false
        fun getContentIfNotHandled(): T? = if (hasBeenHandled) null else { hasBeenHandled = true; content }
    }

    private val _presetApplied = MutableLiveData<Event<String>?>()
    val presetApplied: LiveData<Event<String>?> = _presetApplied

    fun applyPreset(template: PresetTemplates.PresetTemplate) =
        viewModelScope.launch {
            db.withTransaction {
                val universeId = universeRepository.insertUniverse(template.universe)
                val fieldsWithId = template.fields.map { it.copy(universeId = universeId) }
                universeRepository.insertAllFields(fieldsWithId)
            }
            _presetApplied.value = Event(template.universe.name)
        }

    /** 세계관에 속한 캐릭터 중 이미지가 있는 랜덤 캐릭터의 첫 이미지 경로 반환 */
    fun resolveRandomCharacterImage(universeId: Long, callback: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val novels = novelRepository.getNovelsByUniverseList(universeId)
                val allCharacters = novels.flatMap { novel ->
                    characterRepository.getCharactersByNovelList(novel.id)
                }
                val withImages = allCharacters.filter { char ->
                    char.imagePaths.isNotBlank() && char.imagePaths != "[]"
                }
                if (withImages.isEmpty()) {
                    callback(null)
                    return@launch
                }
                val target = withImages.random()
                val firstPath = try {
                    val arr = com.google.gson.Gson().fromJson(target.imagePaths, Array<String>::class.java)
                    arr?.firstOrNull()
                } catch (_: Exception) { null }
                callback(firstPath)
            } catch (e: Exception) {
                Log.e("UniverseViewModel", "Failed to resolve character image", e)
                callback(null)
            }
        }
    }

    fun recordRecentActivity(entityType: String, entityId: Long, title: String) = viewModelScope.launch {
        recentActivityDao.upsert(
            RecentActivity(entityType = entityType, entityId = entityId, title = title)
        )
        recentActivityDao.trimToMax(RecentActivity.MAX_ENTRIES)
    }
}
