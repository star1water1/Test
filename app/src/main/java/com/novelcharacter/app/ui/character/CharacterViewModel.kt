package com.novelcharacter.app.ui.character

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.RecentActivity
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.util.EventEditDialogHelper.ShiftDirection
import com.novelcharacter.app.util.SemanticFieldSyncHelper
import com.novelcharacter.app.util.StandardYearSyncHelper
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.launch

class CharacterViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val characterRepository = app.characterRepository
    private val novelRepository = app.novelRepository
    private val timelineRepository = app.timelineRepository
    private val universeRepository = app.universeRepository
    private val recentActivityDao = app.recentActivityDao
    private val semanticSyncHelper = SemanticFieldSyncHelper(characterRepository, universeRepository, novelRepository)

    val allCharacters: LiveData<List<Character>> = characterRepository.allCharacters
    val allNovels: LiveData<List<Novel>> = novelRepository.allNovels

    private val _currentNovelId = MutableLiveData<Long?>()

    val filteredCharacters: LiveData<List<Character>> = _currentNovelId.switchMap { novelId ->
        if (novelId == null || novelId == -1L) {
            characterRepository.allCharacters
        } else {
            characterRepository.getCharactersByNovel(novelId)
        }
    }

    private val _searchQuery = MutableLiveData("")
    private val _searchTrigger = MediatorLiveData<Unit>().apply {
        addSource(_searchQuery) { value = Unit }
        addSource(_currentNovelId) { value = Unit }
    }
    val searchResults: LiveData<List<Character>> = _searchTrigger.switchMap {
        val query = _searchQuery.value
        if (query.isNullOrBlank()) {
            filteredCharacters
        } else {
            val novelId = _currentNovelId.value
            if (novelId == null || novelId == -1L) {
                characterRepository.searchCharacters(query)
            } else {
                characterRepository.searchCharacters(query).map { characters ->
                    characters.filter { it.novelId == novelId }
                }
            }
        }
    }

    fun setNovelFilter(novelId: Long?) {
        _currentNovelId.value = novelId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** 현재 검색어 반환 (배치 작업 후 갱신 트리거용) */
    fun getSearchQuery(): String = _searchQuery.value ?: ""

    /** LiveData 강제 재평가 트리거 (배치 작업 후 리스트 갱신용) */
    fun refreshList() {
        _searchQuery.value = _searchQuery.value
    }

    fun getCharacterById(id: Long): LiveData<Character?> = characterRepository.getCharacterByIdLive(id)

    fun getEventsForCharacter(characterId: Long): LiveData<List<TimelineEvent>> =
        timelineRepository.getEventsForCharacter(characterId)

    suspend fun getEventsForCharacterSuspend(characterId: Long): List<TimelineEvent> =
        timelineRepository.getEventsForCharacterList(characterId)

    suspend fun getCharacterByIdSuspend(id: Long): Character? = characterRepository.getCharacterById(id)

    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        timelineRepository.getCharactersForEvent(eventId)

    suspend fun getNovelById(id: Long): Novel? = novelRepository.getNovelById(id)

    suspend fun getAllNovelsList(): List<Novel> = novelRepository.getAllNovelsList()

    fun insertCharacter(character: Character) = viewModelScope.launch {
        try {
            characterRepository.insertCharacter(character)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert character", e)
        }
    }

    fun updateCharacter(character: Character) = viewModelScope.launch {
        try {
            characterRepository.updateCharacter(character)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update character", e)
        }
    }

    fun deleteCharacter(character: Character) = viewModelScope.launch {
        try {
            characterRepository.deleteCharacter(character)
            // 삭제 후 switchMap 재평가를 강제하여 캐시된 LiveData 갱신
            _searchQuery.value = _searchQuery.value
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete character", e)
        }
    }

    // ===== FieldDefinition =====
    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        universeRepository.getFieldsByUniverseList(universeId)

    // ===== CharacterFieldValue =====
    suspend fun getValuesByCharacterList(characterId: Long): List<CharacterFieldValue> =
        characterRepository.getValuesByCharacterList(characterId)

    suspend fun getFieldValuesForNovel(novelId: Long, fieldDefId: Long): List<String> =
        characterRepository.getFieldValuesForNovel(novelId, fieldDefId)

    suspend fun getFieldValuesForUniverse(universeId: Long, fieldDefId: Long): List<String> =
        characterRepository.getFieldValuesForUniverse(universeId, fieldDefId)

    suspend fun saveAllFieldValues(characterId: Long, values: List<CharacterFieldValue>) {
        characterRepository.saveAllFieldValues(characterId, values)
        val universeId = getUniverseIdForCharacter(characterId)
        if (universeId != null) {
            semanticSyncHelper.syncFieldToStateChange(characterId, universeId, values)
        }
    }

    suspend fun updateCharacterWithFields(character: Character, values: List<CharacterFieldValue>) {
        characterRepository.updateCharacterWithFields(character, values)
        val universeId = getUniverseIdForCharacter(character.id)
        if (universeId != null) {
            semanticSyncHelper.syncFieldToStateChange(character.id, universeId, values)
        }
    }

    private suspend fun getUniverseIdForCharacter(characterId: Long): Long? {
        val character = characterRepository.getCharacterById(characterId) ?: return null
        val novelId = character.novelId ?: return null
        val novel = novelRepository.getNovelById(novelId) ?: return null
        return novel.universeId
    }

    // ===== 나이-출생연도 불일치 감지 =====

    data class AgeLinkageConflict(
        val inputAge: Int,
        val inputBirthYear: Int,
        val currentStdYear: Int,
        val expectedAge: Int,
        val suggestedBirthYear: Int,
        val suggestedStdYear: Int,
        val affectedCharacterCount: Int,
        val ageFieldId: Long,
        val birthYearFieldId: Long,
        val novelId: Long
    )

    /**
     * 저장 전 나이-출생연도 불일치를 감지한다.
     * 둘 다 값이 있고 standardYear와 맞지 않을 때만 conflict 반환.
     * @param novelId 캐릭터가 속한 작품 ID (신규 캐릭터용 — DB 조회 없이 직접 전달)
     * @param characterId 기존 캐릭터 ID (연동 확인용, 신규면 -1)
     */
    suspend fun detectAgeLinkageConflict(
        novelId: Long?,
        characterId: Long,
        fieldValues: List<CharacterFieldValue>
    ): AgeLinkageConflict? {
        if (novelId == null) return null
        val novel = novelRepository.getNovelById(novelId) ?: return null
        val stdYear = novel.standardYear ?: return null
        val universeId = novel.universeId ?: return null

        // 연동 활성 확인 (신규 캐릭터는 기본 활성)
        if (characterId > 0 && !semanticSyncHelper.isLinked(characterId)) return null

        val fields = universeRepository.getFieldsByUniverseList(universeId)
        val ageField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.AGE } ?: return null
        val birthYearField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.BIRTH_YEAR } ?: return null

        val inputAge = fieldValues.find { it.fieldDefinitionId == ageField.id }
            ?.value?.trim()?.toIntOrNull() ?: return null
        val inputBirthYear = fieldValues.find { it.fieldDefinitionId == birthYearField.id }
            ?.value?.trim()?.toIntOrNull() ?: return null

        val expectedAge = stdYear - inputBirthYear
        if (inputAge == expectedAge) return null

        // 기준연도 변경 시 영향받는 다른 캐릭터 수
        val linkedCount = characterRepository.getCharactersByNovelList(novelId)
            .filter { it.id != characterId }
            .count { semanticSyncHelper.isLinked(it.id) }

        return AgeLinkageConflict(
            inputAge = inputAge,
            inputBirthYear = inputBirthYear,
            currentStdYear = stdYear,
            expectedAge = expectedAge,
            suggestedBirthYear = stdYear - inputAge,
            suggestedStdYear = inputBirthYear + inputAge,
            affectedCharacterCount = linkedCount,
            ageFieldId = ageField.id,
            birthYearFieldId = birthYearField.id,
            novelId = novelId
        )
    }

    /**
     * 기준연도 변경 옵션 선택 시: novel.standardYear 업데이트 + 다른 캐릭터 일괄 재계산.
     */
    suspend fun applyStandardYearChange(novelId: Long, oldStdYear: Int, newStdYear: Int) {
        val novel = novelRepository.getNovelById(novelId) ?: return
        val updatedNovel = novel.copy(standardYear = newStdYear)
        novelRepository.updateNovel(updatedNovel)
        standardYearSyncHelper.onStandardYearChanged(updatedNovel, oldStdYear, newStdYear)
    }

    private val standardYearSyncHelper = StandardYearSyncHelper(characterRepository, universeRepository)

    /** 캐릭터가 속한 세계관의 관계 유형 목록을 반환 (세계관 미배정 시 기본 유형) */
    suspend fun getRelationshipTypesForCharacter(characterId: Long): List<String> {
        val universeId = getUniverseIdForCharacter(characterId) ?: return Universe.DEFAULT_RELATIONSHIP_TYPES
        val universe = universeRepository.getUniverseById(universeId) ?: return Universe.DEFAULT_RELATIONSHIP_TYPES
        return universe.getRelationshipTypes()
    }

    suspend fun insertCharacterSuspend(character: Character): Long =
        characterRepository.insertCharacter(character)

    // ===== CharacterStateChange =====
    fun getChangesByCharacter(characterId: Long): LiveData<List<CharacterStateChange>> =
        characterRepository.getChangesByCharacter(characterId)

    suspend fun getChangesUpToYear(characterId: Long, year: Int): List<CharacterStateChange> =
        characterRepository.getChangesUpToYear(characterId, year)

    suspend fun getChangesByCharacterList(characterId: Long): List<CharacterStateChange> =
        characterRepository.getChangesByCharacterList(characterId)

    fun insertStateChange(change: CharacterStateChange) = viewModelScope.launch {
        try {
            characterRepository.insertStateChange(change)
            syncStateChangeToField(change)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert state change", e)
        }
    }

    fun updateStateChange(change: CharacterStateChange) = viewModelScope.launch {
        try {
            characterRepository.updateStateChange(change)
            syncStateChangeToField(change)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update state change", e)
        }
    }

    private suspend fun syncStateChangeToField(change: CharacterStateChange) {
        if (change.fieldKey != CharacterStateChange.KEY_BIRTH && change.fieldKey != CharacterStateChange.KEY_DEATH) return
        val universeId = getUniverseIdForCharacter(change.characterId) ?: return
        semanticSyncHelper.syncStateChangeToField(change.characterId, universeId, change)
    }

    fun deleteStateChange(change: CharacterStateChange) = viewModelScope.launch {
        try {
            characterRepository.deleteStateChange(change)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete state change", e)
        }
    }

    // ===== Relationships =====
    fun getRelationshipsForCharacter(characterId: Long): LiveData<List<CharacterRelationship>> =
        characterRepository.getRelationshipsForCharacter(characterId)

    fun insertRelationship(relationship: CharacterRelationship) = viewModelScope.launch {
        try {
            characterRepository.insertRelationship(relationship)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert relationship", e)
        }
    }

    fun updateRelationship(relationship: CharacterRelationship) = viewModelScope.launch {
        try {
            characterRepository.updateRelationship(relationship)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update relationship", e)
        }
    }

    fun deleteRelationshipById(id: Long) = viewModelScope.launch {
        try {
            characterRepository.deleteRelationshipById(id)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete relationship", e)
        }
    }

    suspend fun getRelationshipsForCharacterList(characterId: Long): List<CharacterRelationship> =
        characterRepository.getRelationshipsForCharacterList(characterId)

    suspend fun getRelationshipById(id: Long): CharacterRelationship? =
        characterRepository.getRelationshipById(id)

    fun updateRelationshipOrders(relationships: List<CharacterRelationship>) = viewModelScope.launch {
        try {
            characterRepository.updateRelationshipOrders(relationships)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update relationship orders", e)
        }
    }

    suspend fun getAllCharactersList(): List<Character> =
        characterRepository.getAllCharactersList()

    suspend fun getAllCharactersByName(name: String): List<Character> =
        characterRepository.getAllCharactersByName(name)

    suspend fun getCharactersByNovelList(novelId: Long): List<Character> =
        characterRepository.getCharactersByNovelList(novelId)

    suspend fun getCharactersByUniverseList(universeId: Long): List<Character> =
        characterRepository.getCharactersByUniverseList(universeId)

    // ===== RelationshipChanges =====
    fun getRelationshipChanges(relationshipId: Long): LiveData<List<CharacterRelationshipChange>> =
        characterRepository.getRelationshipChanges(relationshipId)

    suspend fun getRelationshipChangesList(relationshipId: Long): List<CharacterRelationshipChange> =
        characterRepository.getRelationshipChangesList(relationshipId)

    fun insertRelationshipChange(change: CharacterRelationshipChange) = viewModelScope.launch {
        try {
            characterRepository.insertRelationshipChange(change)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert relationship change", e)
        }
    }

    fun updateRelationshipChange(change: CharacterRelationshipChange) = viewModelScope.launch {
        try {
            characterRepository.updateRelationshipChange(change)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update relationship change", e)
        }
    }

    fun deleteRelationshipChange(change: CharacterRelationshipChange) = viewModelScope.launch {
        try {
            characterRepository.deleteRelationshipChange(change)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete relationship change", e)
        }
    }

    suspend fun getEventsForNovelList(novelId: Long): List<TimelineEvent> =
        timelineRepository.getEventsByNovelList(novelId)

    // ===== Tags =====
    fun getTagsByCharacter(characterId: Long): LiveData<List<CharacterTag>> =
        characterRepository.getTagsByCharacter(characterId)

    suspend fun getTagsByCharacterList(characterId: Long): List<CharacterTag> =
        characterRepository.getTagsByCharacterList(characterId)

    suspend fun getAllDistinctTags(): List<String> =
        characterRepository.getAllDistinctTags()

    fun deleteAllTagsByCharacter(characterId: Long) = viewModelScope.launch {
        try {
            characterRepository.deleteAllTagsByCharacter(characterId)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete tags", e)
        }
    }

    fun insertTags(tags: List<CharacterTag>) = viewModelScope.launch {
        try {
            characterRepository.insertTags(tags)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert tags", e)
        }
    }

    suspend fun deleteAllTagsByCharacterSuspend(characterId: Long) =
        characterRepository.deleteAllTagsByCharacter(characterId)

    suspend fun insertTagsSuspend(tags: List<CharacterTag>) =
        characterRepository.insertTags(tags)

    suspend fun replaceAllTagsSuspend(characterId: Long, tags: List<CharacterTag>) =
        characterRepository.replaceAllTagsForCharacter(characterId, tags)

    fun updateCharacterDisplayOrders(characters: List<Character>) = viewModelScope.launch {
        try {
            characterRepository.updateCharacterDisplayOrders(characters)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update display orders", e)
        }
    }

    fun togglePin(character: Character) = viewModelScope.launch {
        try {
            characterRepository.setPinned(character.id, !character.isPinned)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to toggle pin", e)
        }
    }

    fun recordRecentActivity(characterId: Long, name: String) = viewModelScope.launch {
        try {
            recentActivityDao.upsert(
                RecentActivity(entityType = RecentActivity.TYPE_CHARACTER, entityId = characterId, title = name)
            )
            recentActivityDao.trimToMax(RecentActivity.MAX_ENTRIES)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to record recent activity", e)
        }
    }

    // ===== Event CRUD (캐릭터 화면에서 사건 생성용) =====
    private val db = app.database

    fun insertEvent(event: TimelineEvent, characterIds: List<Long>) = viewModelScope.launch {
        try {
            db.withTransaction {
                val eventId = timelineRepository.insertEvent(event)
                timelineRepository.updateEventCharacters(eventId, characterIds)
            }
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert event", e)
        }
    }

    fun updateEvent(event: TimelineEvent, characterIds: List<Long>) = viewModelScope.launch {
        try {
            db.withTransaction {
                timelineRepository.updateEvent(event)
                timelineRepository.updateEventCharacters(event.id, characterIds)
            }
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update event", e)
        }
    }

    fun updateEventAndShiftOthers(
        event: TimelineEvent,
        characterIds: List<Long>,
        shiftDirection: ShiftDirection,
        delta: Int,
        originalNovelId: Long?,
        originalUniverseId: Long?
    ) = viewModelScope.launch {
        try {
            val oldYear = event.year - delta
            db.withTransaction {
                timelineRepository.updateEvent(event)
                timelineRepository.updateEventCharacters(event.id, characterIds)

                val scopeEvents = when {
                    originalNovelId != null -> timelineRepository.getEventsByNovelList(originalNovelId)
                    originalUniverseId != null -> timelineRepository.getEventsByUniverseList(originalUniverseId)
                    else -> timelineRepository.getAllEventsList()
                }.filter { it.id != event.id }

                val eventsToShift = scopeEvents.filter { e ->
                    when (shiftDirection) {
                        ShiftDirection.AFTER -> e.year >= oldYear
                        ShiftDirection.BEFORE -> e.year <= oldYear
                    }
                }

                if (eventsToShift.isNotEmpty()) {
                    val shifted = eventsToShift.mapNotNull { e ->
                        val newYear = e.year.toLong() + delta.toLong()
                        if (newYear in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                            e.copy(year = newYear.toInt())
                        } else null
                    }
                    timelineRepository.updateAllEvents(shifted)

                    for (s in shifted) {
                        if (s.eventType == TimelineEvent.TYPE_BIRTH || s.eventType == TimelineEvent.TYPE_DEATH) {
                            val charIds = timelineRepository.getCharacterIdsForEvent(s.id)
                            syncEventTypeToStateChanges(s, charIds)
                        }
                    }
                }
            }
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to shift events", e)
        }
    }

    suspend fun getEventsByNovelList(novelId: Long) = timelineRepository.getEventsByNovelList(novelId)
    suspend fun getEventsByUniverseList(universeId: Long) = timelineRepository.getEventsByUniverseList(universeId)
    suspend fun getAllEventsList() = timelineRepository.getAllEventsList()

    private suspend fun syncEventTypeToStateChanges(event: TimelineEvent, characterIds: List<Long>) {
        val fieldKey = when (event.eventType) {
            TimelineEvent.TYPE_BIRTH -> CharacterStateChange.KEY_BIRTH
            TimelineEvent.TYPE_DEATH -> CharacterStateChange.KEY_DEATH
            else -> return
        }
        for (charId in characterIds) {
            try {
                val existing = characterRepository.getChangesByCharacterList(charId)
                    .find { it.fieldKey == fieldKey }
                val change = if (existing != null) {
                    existing.copy(year = event.year, month = event.month, day = event.day)
                        .also { characterRepository.updateStateChange(it) }
                } else {
                    CharacterStateChange(
                        characterId = charId, year = event.year,
                        month = event.month, day = event.day,
                        fieldKey = fieldKey, newValue = event.year.toString()
                    ).also { characterRepository.insertStateChange(it) }
                }
                val character = characterRepository.getCharacterById(charId) ?: continue
                val novel = character.novelId?.let { novelRepository.getNovelById(it) } ?: continue
                val uId = novel.universeId ?: continue
                semanticSyncHelper.syncStateChangeToField(charId, uId, change)
            } catch (e: Exception) {
                Log.w("CharacterViewModel", "Failed to sync event type for character $charId", e)
            }
        }
    }

    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        timelineRepository.getCharacterIdsForEvent(eventId)
}
