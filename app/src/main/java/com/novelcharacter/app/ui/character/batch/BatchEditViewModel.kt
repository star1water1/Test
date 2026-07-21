package com.novelcharacter.app.ui.character.batch

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import androidx.room.withTransaction
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.UniverseMoveCounts
import com.novelcharacter.app.util.SemanticFieldSyncHelper
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
        return resolveCharUniverse(ids).values.filterNotNull().distinct()
    }

    // ===== 배치 작업 =====

    fun setPinned(pinned: Boolean) = launchBatchOp("setPinned") { ids ->
        characterRepository.batchSetPinned(ids, pinned)
        BatchCounts(ids.size)
    }

    fun changeNovel(newNovelId: Long?) = launchBatchOp("changeNovel") { ids ->
        val move = characterRepository.batchChangeNovel(ids, newNovelId)

        // 시맨틱 필드 재동기화: 이동 후 필드값↔상태변화(__birth/__death/__alive) 일관성 유지.
        // standardYear·연동(isLinked) 의존 연산(AGE·birthYear→age)은 syncFieldToStateChange가
        // 역할별로 내부 self-gate하므로, 여기서 standardYear·isLinked로 미리 거르면 그 두 게이트와
        // 무관한 birth/death/alive/birth_date 재동기화까지 함께 누락된다(setFieldValue 경로와 불일치).
        // 따라서 대상 세계관만 확인하고 역할별 판단은 헬퍼에 위임한다(setField/clearField 경로와 동일).
        var syncFailures = 0
        if (newNovelId != null) {
            val newNovel = novelRepository.getNovelById(newNovelId)
            val newUniverseId = newNovel?.universeId
            if (newUniverseId != null) {
                // 값 일괄 로드 + 단일 트랜잭션 — setField/addStateChange와 동일하게 N+1·개별 커밋 제거(받쳐주는 확장성).
                val valuesByChar = characterRepository.getValuesForCharacters(ids).groupBy { it.characterId }
                app.database.withTransaction {
                    for (charId in ids) {
                        try {
                            val values = valuesByChar[charId] ?: emptyList()
                            semanticSyncHelper.syncFieldToStateChange(charId, newUniverseId, values)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to sync semantic fields for character $charId after novel change", e)
                            syncFailures++
                        }
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
        // 세계관 일괄 해소 — 스코프와 시맨틱싱크에 재사용(캐릭터별 getNovelById/getUniverseIdForCharacter N+1 제거).
        val universeByChar = resolveCharUniverse(ids)
        // 필드는 특정 세계관 소유 — 그 세계관 캐릭터에만 적용한다(시트가 약속한 "해당 세계관 캐릭터에만").
        // 타 세계관 캐릭터에 찍으면 그 캐릭터 편집기/통계엔 안 잡히는 고아 필드값이 생긴다.
        val scoped = if (fieldDef != null) ids.filter { universeByChar[it] == fieldDef.universeId } else ids
        val skipped = ids.size - scoped.size  // 다른 세계관/미배정으로 제외된 수 — 조용히 넘기지 않고 집계·고지(변수 제어)
        if (scoped.isEmpty()) return@launchBatchOp BatchCounts(0, skipped = skipped)
        characterRepository.batchSetFieldValue(scoped, fieldDefId, value)

        // 시맨틱 필드 동기화 (나이/출생연도/사망연도/생존여부 변경 시)
        val syncFailures = syncSemanticFields(fieldDef, scoped, universeByChar)
        BatchCounts(scoped.size, syncFailures, skipped = skipped)
    }

    fun clearFieldValue(fieldDefId: Long) = launchBatchOp("clearFieldValue") { ids ->
        val fieldDef = app.database.fieldDefinitionDao().getFieldById(fieldDefId)
        val universeByChar = resolveCharUniverse(ids)
        val scoped = if (fieldDef != null) ids.filter { universeByChar[it] == fieldDef.universeId } else ids
        val skipped = ids.size - scoped.size  // 다른 세계관/미배정으로 제외된 수 — 집계·고지(변수 제어)
        if (scoped.isEmpty()) return@launchBatchOp BatchCounts(0, skipped = skipped)
        characterRepository.batchClearFieldValue(scoped, fieldDefId)

        // 시맨틱 필드 동기화 (출생연도/사망연도 등 초기화 시 나이 등 재계산)
        val syncFailures = syncSemanticFields(fieldDef, scoped, universeByChar)
        BatchCounts(scoped.size, syncFailures, skipped = skipped)
    }

    /**
     * 시맨틱 필드(나이/출생/사망/생존) 일괄 변경 후 상태변화 재동기화.
     * 값은 한 번에 로드(getValuesForCharacters)하고, 모든 싱크를 단일 트랜잭션으로 묶어
     * 캐릭터별 N+1 읽기 + 개별 커밋(fsync)을 제거한다(받쳐주는 확장성). @return 동기화 실패 캐릭터 수.
     */
    private suspend fun syncSemanticFields(
        fieldDef: FieldDefinition?,
        scoped: List<Long>,
        universeByChar: Map<Long, Long?>
    ): Int {
        if (fieldDef == null || SemanticRole.fromConfig(fieldDef.config) == null) return 0
        val valuesByChar = characterRepository.getValuesForCharacters(scoped).groupBy { it.characterId }
        var syncFailures = 0
        app.database.withTransaction {
            for (charId in scoped) {
                try {
                    val universeId = universeByChar[charId] ?: continue
                    val allValues = valuesByChar[charId] ?: emptyList()
                    semanticSyncHelper.syncFieldToStateChange(charId, universeId, allValues)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync semantic field for character $charId", e)
                    syncFailures++
                }
            }
        }
        return syncFailures
    }

    fun appendMemo(text: String, prepend: Boolean) = launchBatchOp("appendMemo") { ids ->
        characterRepository.batchAppendMemo(ids, text, prepend)
        BatchCounts(ids.size)
    }

    /**
     * 선택 캐릭터들에 동일한 상태변화(시점 + 필드 + 새 값)를 한 번에 기록한다.
     * - 커스텀 필드([fieldUniverseId] != null)면 그 세계관 캐릭터에만 적용(타 세계관엔 없는 필드라 무의미).
     * - 특수키(__birth/__death/__alive, [fieldUniverseId] == null)는 선택 전체에 적용.
     * - 중복 처리(변수 제어): 단일키(출생/사망/생존)는 이미 기록이 있으면 값과 무관하게 스킵(기존 값을 조용히
     *   덮어쓰지 않음). 커스텀 필드는 시점별 다중 이력을 허용하므로 정밀 자연키(연·월·일·값) 완전 일치만 스킵.
     *   스킵 건수는 집계해 통보한다.
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
        // 세계관을 한 번에 해소(캐릭터별 getNovelById N+1 제거) → 스코프·시맨틱싱크 양쪽에 재사용.
        val universeByChar = resolveCharUniverse(ids)
        val targets = if (fieldUniverseId != null) ids.filter { universeByChar[it] == fieldUniverseId } else ids
        val outOfScope = ids.size - targets.size  // 다른 세계관/미배정으로 제외된 수 — 자연키 중복 스킵과 함께 집계
        if (targets.isEmpty()) return@launchBatchOp BatchCounts(0, skipped = outOfScope)
        val dao = app.database.characterStateChangeDao()
        // 단일키(__birth/__death/__alive)는 캐릭터당 1행 불변식을 가진다. 이를 무시하고 blind-insert하면
        // 같은 캐릭터에 두 번째 __birth가 생겨 필드값(새 값)↔연표/나이(ORDER BY year ASC로 옛 값)가 어긋난다.
        val isSingular = fieldKey == CharacterStateChange.KEY_BIRTH ||
            fieldKey == CharacterStateChange.KEY_DEATH ||
            fieldKey == CharacterStateChange.KEY_ALIVE
        // __birth/__death는 개별 경로처럼 birth_year/death_year·나이·생존 필드로 역동기화해야
        // 연표·필드값·통계가 어긋나지 않는다(연결성·변수 제어). 개별 경로: insertStateChange→syncStateChangeToField.
        val isSemantic = fieldKey == CharacterStateChange.KEY_BIRTH || fieldKey == CharacterStateChange.KEY_DEATH
        var inserted = 0
        var skipped = 0
        var syncFailures = 0
        // 단일 트랜잭션 — 500명이면 개별 커밋 500회(fsync)를 1회로. 규모에서 깨지지 않게(받쳐주는 확장성).
        app.database.withTransaction {
            for (charId in targets) {
                val alreadyPresent = if (isSingular) {
                    // 단일키는 값과 무관하게 이미 기록이 있으면 건너뛴다. 덮어쓰면 사용자가 개별 설정한
                    // 기존 출생/사망/생존을 일괄 조작이 조용히 바꾸게 되어 변수 제어를 위반한다 →
                    // 대신 스킵으로 집계·고지하고, 바로잡을 경로(개별 편집)를 남긴다.
                    dao.getChangesByField(charId, fieldKey).isNotEmpty()
                } else {
                    // 커스텀 필드는 시점별 다중 이력을 허용 — 정밀 자연키(연·월·일·값) 완전 일치만 중복 스킵.
                    dao.getChangeByExactKey(charId, year, month, day, fieldKey, newValue) != null
                }
                if (alreadyPresent) {
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
                        universeByChar[charId]?.let { universeId ->
                            semanticSyncHelper.syncStateChangeToField(charId, universeId, change)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to sync semantic field after batch state change for character $charId", e)
                        syncFailures++
                    }
                }
            }
        }
        BatchCounts(affected = inserted, syncFailures = syncFailures, skipped = skipped + outOfScope)
    }

    /**
     * 현재 선택에 대한 일괄 삭제 연쇄 영향 요약(관계·상태변화·세력소속·사건연계).
     * 삭제 확인 다이얼로그가 범위를 사전 고지하는 데 쓴다(조작 마찰 최소화 + 변수 제어).
     */
    suspend fun getDeleteImpact(): CharacterRepository.DeleteImpact {
        val ids = _selectedIds.value?.toList() ?: emptyList()
        return characterRepository.getBatchDeleteImpact(ids)
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

    /**
     * [ids] 각 캐릭터 → 소속 세계관 id 맵 (없으면 null). 캐릭터·작품을 각각 청크 배치 조회해
     * 캐릭터별 getNovelById N+1을 제거한다(500명이 몇 개 작품에 몰려도 작품 조회는 distinct 1회씩).
     * 스코프 판정(필드 세계관)과 시맨틱 싱크(캐릭터별 세계관) 양쪽에서 재사용한다.
     */
    private suspend fun resolveCharUniverse(ids: List<Long>): Map<Long, Long?> {
        if (ids.isEmpty()) return emptyMap()
        val charToNovel = HashMap<Long, Long?>(ids.size)
        val novelIds = HashSet<Long>()
        for (chunk in ids.chunked(900)) {
            for (char in app.database.characterDao().getCharactersByIds(chunk)) {
                charToNovel[char.id] = char.novelId
                char.novelId?.let { novelIds.add(it) }
            }
        }
        val novelUniverse = HashMap<Long, Long?>(novelIds.size)
        for (chunk in novelIds.toList().chunked(900)) {
            for (novel in novelRepository.getNovelsByIds(chunk)) novelUniverse[novel.id] = novel.universeId
        }
        val result = HashMap<Long, Long?>(charToNovel.size)
        for ((charId, novelId) in charToNovel) result[charId] = novelId?.let { novelUniverse[it] }
        return result
    }

    companion object {
        private const val TAG = "BatchEditViewModel"
    }
}
