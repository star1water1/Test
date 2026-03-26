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
import com.google.gson.Gson
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
            try {
                val ids = universes.map { it.id }
                _universeNovelCounts.value = universeRepository.getNovelCountsByUniverses(ids)
                _universeFieldCounts.value = universeRepository.getFieldCountsByUniverses(ids)
            } catch (e: Exception) {
                Log.w("UniverseViewModel", "Failed to load counts", e)
                _universeNovelCounts.value = emptyMap()
                _universeFieldCounts.value = emptyMap()
            }
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

    /** 기본 프리셋 복원: 삭제된 빌트인 프리셋을 다시 DB에 삽입 */
    fun restoreBuiltInPresets() = viewModelScope.launch {
        try {
            val existing = userPresetDao.getAllTemplatesList()
            val existingBuiltInNames = existing.filter { it.isBuiltIn }.map { it.name }.toSet()
            val builtInTemplates = PresetTemplates.getBuiltInTemplates()
            var restored = 0
            builtInTemplates.forEach { template ->
                if (template.universe.name !in existingBuiltInNames) {
                    val fieldsJson = PresetTemplates.fieldsToJson(template.fields)
                    userPresetDao.insert(UserPresetTemplate(
                        name = template.universe.name,
                        description = template.universe.description,
                        fieldsJson = fieldsJson,
                        isBuiltIn = true
                    ))
                    restored++
                }
            }
            Log.i("UniverseViewModel", "Restored $restored built-in presets")
        } catch (e: Exception) {
            Log.e("UniverseViewModel", "Failed to restore built-in presets", e)
        }
    }

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
            try {
                db.withTransaction {
                    val universeId = universeRepository.insertUniverse(template.universe)
                    val fieldsWithId = template.fields.map { it.copy(universeId = universeId) }
                    universeRepository.insertAllFields(fieldsWithId)
                }
                _presetApplied.value = Event(template.universe.name)
            } catch (e: Exception) {
                Log.e("UniverseViewModel", "Failed to apply preset", e)
            }
        }

    private val gson = Gson()

    /** 세계관에 속한 캐릭터 중 이미지가 있는 랜덤 캐릭터의 첫 이미지 경로 반환 */
    fun resolveRandomCharacterImage(universeId: Long, callback: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                // 단일 JOIN 쿼리로 세계관 내 모든 캐릭터를 한 번에 조회
                val allCharacters = characterRepository.getCharactersByUniverseList(universeId)
                val withImages = allCharacters.filter { char ->
                    char.imagePaths.isNotBlank() && char.imagePaths != "[]"
                }
                if (withImages.isEmpty()) {
                    callback(null)
                    return@launch
                }
                val target = withImages.random()
                val randomPath = try {
                    gson.fromJson(target.imagePaths, Array<String>::class.java)?.randomOrNull()
                } catch (_: Exception) { null }
                callback(randomPath)
            } catch (e: Exception) {
                Log.e("UniverseViewModel", "Failed to resolve character image", e)
                callback(null)
            }
        }
    }

    fun resolveCharacterImageById(characterId: Long, callback: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val character = characterRepository.getCharacterById(characterId)
                if (character == null) {
                    callback(null)
                    return@launch
                }
                val randomPath = try {
                    gson.fromJson(character.imagePaths, Array<String>::class.java)?.randomOrNull()
                } catch (_: Exception) { null }
                callback(randomPath)
            } catch (e: Exception) {
                Log.e("UniverseViewModel", "Failed to resolve character image by ID", e)
                callback(null)
            }
        }
    }

    /**
     * 세계관에 속한 캐릭터 중 이미지가 있는 캐릭터 목록을 반환 (select_character UI용)
     */
    fun getCharactersWithImageForUniverse(universeId: Long, callback: (List<Pair<Long, String>>) -> Unit) {
        viewModelScope.launch {
            try {
                val chars = characterRepository.getCharactersByUniverseList(universeId)
                val result = chars.mapNotNull { char ->
                    if (char.imagePaths.isNotBlank() && char.imagePaths != "[]") {
                        val firstPath = try {
                            gson.fromJson(char.imagePaths, Array<String>::class.java)?.firstOrNull()
                        } catch (_: Exception) { null }
                        if (firstPath != null) Pair(char.id, char.name) else null
                    } else null
                }
                callback(result)
            } catch (_: Exception) {
                callback(emptyList())
            }
        }
    }

    /** 세계관에 속한 작품 중 이미지를 가진 랜덤 작품의 이미지 경로 반환 (계단식 해상도) */
    fun resolveRandomNovelImage(universeId: Long, callback: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val novels = novelRepository.getNovelsByUniverseList(universeId)
                // 직접 이미지가 있거나 캐릭터 이미지 모드인 작품을 후보로 포함
                val candidates = novels.filter {
                    (it.imagePaths.isNotBlank() && it.imagePaths != "[]") ||
                    it.imageMode in listOf(
                        Novel.IMAGE_MODE_RANDOM_CHARACTER,
                        Novel.IMAGE_MODE_SELECT_CHARACTER
                    )
                }
                if (candidates.isEmpty()) {
                    callback(null)
                    return@launch
                }
                resolveNovelImageCascading(candidates.random(), callback)
            } catch (e: Exception) {
                Log.e("UniverseViewModel", "Failed to resolve novel image", e)
                callback(null)
            }
        }
    }

    /** 특정 작품의 이미지 경로를 반환 (계단식 해상도) */
    fun resolveNovelImageById(novelId: Long, callback: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val novel = novelRepository.getNovelById(novelId)
                    ?: run { callback(null); return@launch }
                resolveNovelImageCascading(novel, callback)
            } catch (e: Exception) {
                Log.e("UniverseViewModel", "Failed to resolve novel image by ID", e)
                callback(null)
            }
        }
    }

    /**
     * 작품 이미지를 계단식으로 해상도:
     * 1차: 직접 이미지(imagePaths) → 2차: 캐릭터 이미지 모드 → 해당 캐릭터 이미지
     */
    private suspend fun resolveNovelImageCascading(novel: Novel, callback: (String?) -> Unit) {
        // 1차: 직접 이미지 (JSON 배열에서 랜덤 선택)
        if (novel.imagePaths.isNotBlank() && novel.imagePaths != "[]") {
            val path = try {
                gson.fromJson(novel.imagePaths, Array<String>::class.java)?.randomOrNull()
            } catch (_: Exception) { null }
            if (path != null) {
                callback(path)
                return
            }
        }
        // 2차: 캐릭터 이미지 모드 → 하위 캐릭터 이미지 해상도
        if (novel.imageMode in listOf(
                Novel.IMAGE_MODE_RANDOM_CHARACTER,
                Novel.IMAGE_MODE_SELECT_CHARACTER
            )) {
            val characters = characterRepository.getCharactersByNovelList(novel.id)
            val withImages = characters.filter {
                it.imagePaths.isNotBlank() && it.imagePaths != "[]"
            }
            if (withImages.isEmpty()) {
                callback(null)
                return
            }
            val target = if (novel.imageMode == Novel.IMAGE_MODE_SELECT_CHARACTER && novel.imageCharacterId != null) {
                withImages.find { it.id == novel.imageCharacterId } ?: withImages.random()
            } else {
                withImages.random()
            }
            val path = try {
                gson.fromJson(target.imagePaths, Array<String>::class.java)?.randomOrNull()
            } catch (_: Exception) { null }
            callback(path)
            return
        }
        callback(null)
    }

    /** 세계관에 속한 작품 중 이미지 표시 가능한 작품 목록 반환 (select_novel UI용) */
    fun getNovelsWithImageForUniverse(universeId: Long, callback: (List<Pair<Long, String>>) -> Unit) {
        viewModelScope.launch {
            try {
                val novels = novelRepository.getNovelsByUniverseList(universeId)
                // 직접 이미지 + 캐릭터 이미지 모드 작품 모두 포함 (계단식 해상도와 일관성 유지)
                val result = novels.mapNotNull { novel ->
                    if ((novel.imagePaths.isNotBlank() && novel.imagePaths != "[]") ||
                        novel.imageMode in listOf(
                            Novel.IMAGE_MODE_RANDOM_CHARACTER,
                            Novel.IMAGE_MODE_SELECT_CHARACTER
                        )) {
                        Pair(novel.id, novel.title)
                    } else null
                }
                callback(result)
            } catch (_: Exception) {
                callback(emptyList())
            }
        }
    }

    fun recordRecentActivity(entityType: String, entityId: Long, title: String) = viewModelScope.launch {
        try {
            recentActivityDao.upsert(
                RecentActivity(entityType = entityType, entityId = entityId, title = title)
            )
            recentActivityDao.trimToMax(RecentActivity.MAX_ENTRIES)
        } catch (e: Exception) {
            Log.e("UniverseViewModel", "Failed to record recent activity", e)
        }
    }
}
