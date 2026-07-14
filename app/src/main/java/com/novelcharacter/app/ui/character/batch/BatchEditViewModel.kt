package com.novelcharacter.app.ui.character.batch

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.repository.UniverseMoveCounts
import com.novelcharacter.app.util.SemanticFieldSyncHelper
import com.novelcharacter.app.util.StandardYearSyncHelper
import kotlinx.coroutines.launch

sealed class BatchOperationResult {
    /**
     * @param affectedCount 실제 반영된 캐릭터 수
     * @param syncFailures 시맨틱 필드(나이/생존 등) 재동기화에 실패한 캐릭터 수 — 조용한 부분 실패를 통보하기 위해 집계
     * @param move 세계관 이동(작품 변경) 시 필드 이관/제거 집계 — 유실을 사용자에게 고지하기 위해 전달
     */
    data class Success(
        val operation: String,
        val affectedCount: Int,
        val syncFailures: Int = 0,
        val move: UniverseMoveCounts = UniverseMoveCounts(),
        // 자연키 중복 등으로 건너뛴 건수(예: 일괄 상태변화에서 이미 같은 기록이 있는 캐릭터). 조용한 스킵을 고지하기 위함.
        val skipped: Int = 0
    ) : BatchOperationResult()
    data class Error(val message: String) : BatchOperationResult()
}

/** 배치 작업 내부 집계 결과 (반영 건수 + 동기화 실패 건수 + 세계관 이동 집계 + 스킵 건수) */
private data class BatchCounts(
    val affected: Int,
    val syncFailures: Int = 0,
    val move: UniverseMoveCounts = UniverseMoveCounts(),
    val skipped: Int = 0
)

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

    // 현재 선택 수. 이전에는 `_selectedIds.map { it.size }`(파생 LiveData)였는데 아무도 observe하지
    // 않아 비활성 LiveData의 .value가 영구 null → 이를 `.value ?: 0`으로 읽던 모든 곳이 항상 0을 봤고,
    // '작업' 버튼의 `count > 0` 가드에 막혀 일괄 작업 전체가 먹통이었다. 항상 현재값을 주는 프로퍼티로 교체.
    val selectedCount: Int get() = _selectedIds.value?.size ?: 0

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
            val characters = app.database.characterDao().getCharactersByIds(chunk)
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
        BatchCounts(ids.size)
    }

    fun changeNovel(newNovelId: Long?) = launchBatchOp("changeNovel") { ids ->
        val move = characterRepository.batchChangeNovel(ids, newNovelId)

        // 시맨틱 필드 재동기화 (standardYear가 달라질 수 있으므로)
        var syncFailures = 0
        if (newNovelId != null) {
            val newNovel = novelRepository.getNovelById(newNovelId)
            val newUniverseId = newNovel?.universeId
            if (newUniverseId != null && newNovel?.standardYear != null) {
                for (charId in ids) {
                    try {
                        if (stdYearSyncHelper.isLinked(charId)) {
                            val values = characterRepository.getValuesByCharacterList(charId)
                            semanticSyncHelper.syncFieldToStateChange(charId, newUniverseId, values)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to sync semantic fields for character $charId after novel change", e)
                        syncFailures++
                    }
                }
            }
        }
        BatchCounts(ids.size, syncFailures, move)
    }

    fun addTags(tags: List<String>) = launchBatchOp("addTags") { ids ->
        characterRepository.batchAddTags(ids, tags)
        BatchCounts(ids.size)
    }

    fun removeTags(tags: List<String>) = launchBatchOp("removeTags") { ids ->
        characterRepository.batchRemoveTags(ids, tags)
        BatchCounts(ids.size)
    }

    fun setFieldValue(fieldDefId: Long, value: String) = launchBatchOp("setFieldValue") { ids ->
        val fieldDef = app.database.fieldDefinitionDao().getFieldById(fieldDefId)
        // 필드는 특정 세계관 소유 — 그 세계관 캐릭터에만 적용한다(시트가 약속한 "해당 세계관 캐릭터에만").
        // 타 세계관 캐릭터에 찍으면 그 캐릭터 편집기/통계엔 안 잡히는 고아 필드값이 생긴다.
        val scoped = if (fieldDef != null) idsInUniverse(ids, fieldDef.universeId) else ids
        if (scoped.isEmpty()) return@launchBatchOp BatchCounts(0)
        characterRepository.batchSetFieldValue(scoped, fieldDefId, value)

        // 시맨틱 필드 동기화 (나이/출생연도/사망연도/생존여부 변경 시)
        var syncFailures = 0
        if (fieldDef != null && SemanticRole.fromConfig(fieldDef.config) != null) {
            for (charId in scoped) {
                try {
                    val universeId = getUniverseIdForCharacter(charId) ?: continue
                    val allValues = characterRepository.getValuesByCharacterList(charId)
                    semanticSyncHelper.syncFieldToStateChange(charId, universeId, allValues)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync semantic field for character $charId", e)
                    syncFailures++
                }
            }
        }
        BatchCounts(scoped.size, syncFailures)
    }

    fun clearFieldValue(fieldDefId: Long) = launchBatchOp("clearFieldValue") { ids ->
        val fieldDef = app.database.fieldDefinitionDao().getFieldById(fieldDefId)
        val scoped = if (fieldDef != null) idsInUniverse(ids, fieldDef.universeId) else ids
        if (scoped.isEmpty()) return@launchBatchOp BatchCounts(0)
        characterRepository.batchClearFieldValue(scoped, fieldDefId)

        // 시맨틱 필드 동기화 (출생연도/사망연도 등 초기화 시 나이 등 재계산)
        var syncFailures = 0
        if (fieldDef != null && SemanticRole.fromConfig(fieldDef.config) != null) {
            for (charId in scoped) {
                try {
                    val universeId = getUniverseIdForCharacter(charId) ?: continue
                    val allValues = characterRepository.getValuesByCharacterList(charId)
                    semanticSyncHelper.syncFieldToStateChange(charId, universeId, allValues)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync semantic field for character $charId after clear", e)
                    syncFailures++
                }
            }
        }
        BatchCounts(scoped.size, syncFailures)
    }

    fun appendMemo(text: String, prepend: Boolean) = launchBatchOp("appendMemo") { ids ->
        characterRepository.batchAppendMemo(ids, text, prepend)
        BatchCounts(ids.size)
    }

    /**
     * 선택 캐릭터들에 동일한 상태변화(시점 + 필드 + 새 값)를 한 번에 기록한다.
     * - 커스텀 필드([fieldUniverseId] != null)면 그 세계관 캐릭터에만 적용(타 세계관엔 없는 필드라 무의미).
     * - 특수키(__birth/__death/__alive 등, [fieldUniverseId] == null)는 선택 전체에 적용.
     * - 이미 같은 자연키(캐릭터+연도+필드키+값)가 있으면 중복 생성하지 않고 스킵으로 집계해 통보한다(변수 제어).
     */
    fun addStateChange(
        year: Int,
        month: Int?,
        day: Int?,
        fieldKey: String,
        newValue: String,
        description: String,
        fieldUniverseId: Long?
    ) = launchBatchOp("addStateChange") { ids ->
        val targets = if (fieldUniverseId != null) idsInUniverse(ids, fieldUniverseId) else ids
        val dao = app.database.characterStateChangeDao()
        // __birth/__death는 개별 경로처럼 birth_year/death_year·나이·생존 필드로 역동기화해야
        // 연표·필드값·통계가 어긋나지 않는다(연결성·변수 제어). 개별 경로: insertStateChange→syncStateChangeToField.
        val isSemantic = fieldKey == CharacterStateChange.KEY_BIRTH || fieldKey == CharacterStateChange.KEY_DEATH
        var inserted = 0
        var skipped = 0
        var syncFailures = 0
        for (charId in targets) {
            // 중복 판정에 month/day 포함 — 연도만 같고 시점이 다른 별개 기록은 허용(개별 경로와의 불일치·조용한 스킵 완화).
            if (dao.getChangeByExactKey(charId, year, month, day, fieldKey, newValue) != null) {
                skipped++
                continue
            }
            val change = CharacterStateChange(
                characterId = charId, year = year, month = month, day = day,
                fieldKey = fieldKey, newValue = newValue, description = description
            )
            dao.insert(change)
            inserted++
            if (isSemantic) {
                try {
                    getUniverseIdForCharacter(charId)?.let { universeId ->
                        semanticSyncHelper.syncStateChangeToField(charId, universeId, change)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync semantic field after batch state change for character $charId", e)
                    syncFailures++
                }
            }
        }
        BatchCounts(affected = inserted, syncFailures = syncFailures, skipped = skipped)
    }

    fun deleteSelected() = launchBatchOp("delete") { ids ->
        characterRepository.batchDelete(ids)
        val count = ids.size
        _selectedIds.postValue(emptySet()) // 삭제 후 선택 해제
        BatchCounts(count)
    }

    // ===== 내부 유틸리티 =====

    private fun launchBatchOp(opName: String, block: suspend (List<Long>) -> BatchCounts) {
        if (_isProcessing.value == true) return // 중복 실행 방지
        val ids = _selectedIds.value?.toList()
        if (ids.isNullOrEmpty()) return

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val counts = block(ids)
                // counts.move를 반드시 전달 — 누락하면 작품 변경(세계관 이동) 시 제거된 필드값·세력 소속이
                // 기본값(0)으로 덮여 CharacterListFragment의 이동-제거 경고가 영원히 안 뜨고, 유실이 조용히 삼켜진다(변수 제어).
                _operationResult.value = BatchOperationResult.Success(opName, counts.affected, counts.syncFailures, counts.move, counts.skipped)
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

    /** [ids] 중 [universeId] 세계관에 속한 캐릭터 id만 (청크 조회 — getUniverseIdsForSelection과 동일 패턴). */
    private suspend fun idsInUniverse(ids: List<Long>, universeId: Long): List<Long> {
        val result = mutableListOf<Long>()
        for (chunk in ids.chunked(900)) {
            val characters = app.database.characterDao().getCharactersByIds(chunk)
            for (char in characters) {
                val novelId = char.novelId ?: continue
                val novel = novelRepository.getNovelById(novelId) ?: continue
                if (novel.universeId == universeId) result.add(char.id)
            }
        }
        return result
    }

    companion object {
        private const val TAG = "BatchEditViewModel"
    }
}
