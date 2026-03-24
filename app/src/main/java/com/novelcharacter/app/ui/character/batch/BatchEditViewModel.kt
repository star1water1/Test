package com.novelcharacter.app.ui.character.batch

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.util.SemanticFieldSyncHelper
import com.novelcharacter.app.util.StandardYearSyncHelper
import kotlinx.coroutines.launch

sealed class BatchOperationResult {
    data class Success(val operation: String, val affectedCount: Int) : BatchOperationResult()
    data class Error(val message: String) : BatchOperationResult()
}

class BatchEditViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val characterRepository = app.characterRepository
    private val novelRepository = app.novelRepository
    private val universeRepository = app.universeRepository
    private val semanticSyncHelper = SemanticFieldSyncHelper(characterRepository, universeRepository, novelRepository)
    private val stdYearSyncHelper = StandardYearSyncHelper(characterRepository, universeRepository)

    // ===== 선택 상태 =====

    private val _selectedIds = MutableLiveData<Set<Long>>(emptySet())
    val selectedIds: LiveData<Set<Long>> = _selectedIds

    val selectedCount: LiveData<Int> = _selectedIds.map { it.size }

    private val _operationResult = MutableLiveData<BatchOperationResult?>()
    val operationResult: LiveData<BatchOperationResult?> = _operationResult

    private val _isProcessing = MutableLiveData(false)
    val isProcessing: LiveData<Boolean> = _isProcessing

    // ===== 선택 관리 =====

    fun toggleSelection(id: Long) {
        val current = _selectedIds.value ?: emptySet()
        _selectedIds.value = if (id in current) current - id else current + id
    }

    fun selectAll(visibleIds: List<Long>) {
        val current = _selectedIds.value ?: emptySet()
        _selectedIds.value = current + visibleIds
    }

    fun deselectAll() {
        _selectedIds.value = emptySet()
    }

    fun addToSelection(ids: Set<Long>) {
        val current = _selectedIds.value ?: emptySet()
        _selectedIds.value = current + ids
    }

    fun replaceSelection(ids: Set<Long>) {
        _selectedIds.value = ids
    }

    fun clearResult() {
        _operationResult.value = null
    }

    // ===== 데이터 조회 (BottomSheet에서 사용) =====

    suspend fun getAllNovelsList(): List<Novel> = novelRepository.getAllNovelsList()

    suspend fun getDistinctTagsForCharacters(): List<String> {
        val ids = _selectedIds.value?.toList() ?: return emptyList()
        return characterRepository.getDistinctTagsForCharacters(ids)
    }

    suspend fun getAllDistinctTags(): List<String> =
        app.database.characterTagDao().getAllDistinctTags()

    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        universeRepository.getFieldsByUniverseList(universeId)

    /** 선택 캐릭터들이 속한 세계관 ID 목록 (중복 제거) */
    suspend fun getUniverseIdsForSelection(): List<Long> {
        val ids = _selectedIds.value?.toList() ?: return emptyList()
        val universeIds = mutableSetOf<Long>()
        for (chunk in ids.chunked(900)) {
            val characters = characterRepository.getAllCharactersList()
                .filter { it.id in chunk.toSet() }
            for (char in characters) {
                val novelId = char.novelId ?: continue
                val novel = novelRepository.getNovelById(novelId) ?: continue
                novel.universeId?.let { universeIds.add(it) }
            }
        }
        return universeIds.toList()
    }

    // ===== 배치 작업 =====

    fun setPinned(pinned: Boolean) = launchBatchOp("setPinned") { ids ->
        characterRepository.batchSetPinned(ids, pinned)
        ids.size
    }

    fun changeNovel(newNovelId: Long?) = launchBatchOp("changeNovel") { ids ->
        characterRepository.batchChangeNovel(ids, newNovelId)

        // 시맨틱 필드 재동기화 (standardYear가 달라질 수 있으므로)
        if (newNovelId != null) {
            val newNovel = novelRepository.getNovelById(newNovelId)
            val newUniverseId = newNovel?.universeId
            if (newUniverseId != null && newNovel.standardYear != null) {
                for (charId in ids) {
                    try {
                        if (stdYearSyncHelper.isLinked(charId)) {
                            val values = characterRepository.getValuesByCharacterList(charId)
                            semanticSyncHelper.syncFieldToStateChange(charId, newUniverseId, values)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to sync semantic fields for character $charId after novel change", e)
                    }
                }
            }
        }
        ids.size
    }

    fun addTags(tags: List<String>) = launchBatchOp("addTags") { ids ->
        characterRepository.batchAddTags(ids, tags)
        ids.size
    }

    fun removeTags(tags: List<String>) = launchBatchOp("removeTags") { ids ->
        characterRepository.batchRemoveTags(ids, tags)
        ids.size
    }

    fun setFieldValue(fieldDefId: Long, value: String) = launchBatchOp("setFieldValue") { ids ->
        characterRepository.batchSetFieldValue(ids, fieldDefId, value)

        // 시맨틱 필드 동기화 (나이/출생연도/사망연도/생존여부 변경 시)
        val fieldDef = app.database.fieldDefinitionDao().getFieldById(fieldDefId)
        if (fieldDef != null && SemanticRole.fromConfig(fieldDef.config) != null) {
            for (charId in ids) {
                try {
                    val universeId = getUniverseIdForCharacter(charId) ?: continue
                    val allValues = characterRepository.getValuesByCharacterList(charId)
                    semanticSyncHelper.syncFieldToStateChange(charId, universeId, allValues)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync semantic field for character $charId", e)
                }
            }
        }
        ids.size
    }

    fun clearFieldValue(fieldDefId: Long) = launchBatchOp("clearFieldValue") { ids ->
        characterRepository.batchClearFieldValue(ids, fieldDefId)
        ids.size
    }

    fun appendMemo(text: String, prepend: Boolean) = launchBatchOp("appendMemo") { ids ->
        characterRepository.batchAppendMemo(ids, text, prepend)
        ids.size
    }

    fun deleteSelected() = launchBatchOp("delete") { ids ->
        characterRepository.batchDelete(ids)
        val count = ids.size
        _selectedIds.postValue(emptySet()) // 삭제 후 선택 해제
        count
    }

    // ===== 내부 유틸리티 =====

    private fun launchBatchOp(opName: String, block: suspend (List<Long>) -> Int) {
        val ids = _selectedIds.value?.toList()
        if (ids.isNullOrEmpty()) return

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val count = block(ids)
                _operationResult.value = BatchOperationResult.Success(opName, count)
            } catch (e: Exception) {
                Log.e(TAG, "Batch operation '$opName' failed", e)
                _operationResult.value = BatchOperationResult.Error(
                    e.message ?: "알 수 없는 오류가 발생했습니다"
                )
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private suspend fun getUniverseIdForCharacter(characterId: Long): Long? {
        val character = characterRepository.getCharacterById(characterId) ?: return null
        val novelId = character.novelId ?: return null
        val novel = novelRepository.getNovelById(novelId) ?: return null
        return novel.universeId
    }

    companion object {
        private const val TAG = "BatchEditViewModel"
    }
}
