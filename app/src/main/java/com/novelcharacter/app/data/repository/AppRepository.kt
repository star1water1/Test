package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.*
import com.novelcharacter.app.data.model.*

class AppRepository(
    private val novelDao: NovelDao,
    private val characterDao: CharacterDao,
    private val timelineDao: TimelineDao,
    private val universeDao: UniverseDao,
    private val fieldDefinitionDao: FieldDefinitionDao,
    private val characterFieldValueDao: CharacterFieldValueDao,
    private val characterStateChangeDao: CharacterStateChangeDao,
    private val characterTagDao: CharacterTagDao,
    private val nameBankDao: NameBankDao,
    private val characterRelationshipDao: CharacterRelationshipDao
) {
    // ===== Novel =====
    val allNovels: LiveData<List<Novel>> = novelDao.getAllNovels()

    suspend fun getAllNovelsList(): List<Novel> = novelDao.getAllNovelsList()
    suspend fun getNovelById(id: Long): Novel? = novelDao.getNovelById(id)
    suspend fun insertNovel(novel: Novel): Long = novelDao.insert(novel)
    suspend fun updateNovel(novel: Novel) = novelDao.update(novel)
    suspend fun deleteNovel(novel: Novel) = novelDao.delete(novel)
    fun getNovelsByUniverse(universeId: Long): LiveData<List<Novel>> =
        novelDao.getNovelsByUniverse(universeId)
    suspend fun getNovelsByUniverseList(universeId: Long): List<Novel> =
        novelDao.getNovelsByUniverseList(universeId)
    fun searchNovels(query: String): LiveData<List<Novel>> =
        novelDao.searchNovels(query)

    // ===== Character =====
    val allCharacters: LiveData<List<Character>> = characterDao.getAllCharacters()

    suspend fun getAllCharactersList(): List<Character> = characterDao.getAllCharactersList()
    fun getCharactersByNovel(novelId: Long): LiveData<List<Character>> =
        characterDao.getCharactersByNovel(novelId)
    suspend fun getCharactersByNovelList(novelId: Long): List<Character> =
        characterDao.getCharactersByNovelList(novelId)
    suspend fun getCharacterById(id: Long): Character? = characterDao.getCharacterById(id)
    fun getCharacterByIdLive(id: Long): LiveData<Character?> = characterDao.getCharacterByIdLive(id)
    fun searchCharacters(query: String): LiveData<List<Character>> =
        characterDao.searchCharacters(query)
    suspend fun insertCharacter(character: Character): Long = characterDao.insert(character)
    suspend fun updateCharacter(character: Character) = characterDao.update(character)
    suspend fun deleteCharacter(character: Character) = characterDao.delete(character)
    suspend fun insertAllCharacters(characters: List<Character>) = characterDao.insertAll(characters)

    // ===== Timeline =====
    val allEvents: LiveData<List<TimelineEvent>> = timelineDao.getAllEvents()

    suspend fun getAllEventsList(): List<TimelineEvent> = timelineDao.getAllEventsList()
    fun getEventsByNovel(novelId: Long): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByNovel(novelId)
    suspend fun getEventById(id: Long): TimelineEvent? = timelineDao.getEventById(id)
    fun getEventsByYearRange(startYear: Int, endYear: Int): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByYearRange(startYear, endYear)
    fun searchEvents(query: String): LiveData<List<TimelineEvent>> =
        timelineDao.searchEvents(query)
    suspend fun insertEvent(event: TimelineEvent): Long = timelineDao.insert(event)
    suspend fun updateEvent(event: TimelineEvent) = timelineDao.update(event)
    suspend fun deleteEvent(event: TimelineEvent) = timelineDao.delete(event)
    suspend fun insertAllEvents(events: List<TimelineEvent>) = timelineDao.insertAll(events)
    fun getEventsByUniverse(universeId: Long): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByUniverse(universeId)
    fun getEventsByYearMonthDay(year: Int, month: Int?, day: Int?): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByYearMonthDay(year, month, day)
    fun getEventsForCharacterInRange(characterId: Long, startYear: Int, endYear: Int): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsForCharacterInRange(characterId, startYear, endYear)
    fun getEventsByNovelInRange(novelId: Long, startYear: Int, endYear: Int): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByNovelInRange(novelId, startYear, endYear)

    // ===== Timeline-Character 연결 =====
    suspend fun linkCharacterToEvent(eventId: Long, characterId: Long) {
        timelineDao.insertCrossRef(TimelineCharacterCrossRef(eventId, characterId))
    }

    suspend fun unlinkCharacterFromEvent(eventId: Long, characterId: Long) {
        timelineDao.deleteCrossRef(TimelineCharacterCrossRef(eventId, characterId))
    }

    suspend fun updateEventCharacters(eventId: Long, characterIds: List<Long>) {
        timelineDao.replaceEventCharacters(eventId, characterIds)
    }

    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        timelineDao.getCharacterIdsForEvent(eventId)

    fun getEventsForCharacter(characterId: Long): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsForCharacter(characterId)

    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        timelineDao.getCharactersForEvent(eventId)

    // ===== Universe =====
    val allUniverses: LiveData<List<Universe>> = universeDao.getAllUniverses()

    suspend fun getAllUniversesList(): List<Universe> = universeDao.getAllUniversesList()
    suspend fun getUniverseById(id: Long): Universe? = universeDao.getUniverseById(id)
    suspend fun insertUniverse(universe: Universe): Long = universeDao.insert(universe)
    suspend fun updateUniverse(universe: Universe) = universeDao.update(universe)
    suspend fun deleteUniverse(universe: Universe) = universeDao.delete(universe)

    // ===== FieldDefinition =====
    fun getFieldsByUniverse(universeId: Long): LiveData<List<FieldDefinition>> =
        fieldDefinitionDao.getFieldsByUniverse(universeId)

    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        fieldDefinitionDao.getFieldsByUniverseList(universeId)

    suspend fun getFieldById(id: Long): FieldDefinition? =
        fieldDefinitionDao.getFieldById(id)

    suspend fun getFieldByKey(universeId: Long, key: String): FieldDefinition? =
        fieldDefinitionDao.getFieldByKey(universeId, key)

    suspend fun getFieldsByType(universeId: Long, type: String): List<FieldDefinition> =
        fieldDefinitionDao.getFieldsByType(universeId, type)

    suspend fun getGroupNames(universeId: Long): List<String> =
        fieldDefinitionDao.getGroupNames(universeId)

    suspend fun insertField(field: FieldDefinition): Long =
        fieldDefinitionDao.insert(field)

    suspend fun insertAllFields(fields: List<FieldDefinition>) =
        fieldDefinitionDao.insertAll(fields)

    suspend fun updateField(field: FieldDefinition) =
        fieldDefinitionDao.update(field)

    suspend fun deleteField(field: FieldDefinition) =
        fieldDefinitionDao.delete(field)

    suspend fun deleteAllFieldsByUniverse(universeId: Long) =
        fieldDefinitionDao.deleteAllByUniverse(universeId)

    // ===== CharacterFieldValue =====
    fun getValuesByCharacter(characterId: Long): LiveData<List<CharacterFieldValue>> =
        characterFieldValueDao.getValuesByCharacter(characterId)

    suspend fun getValuesByCharacterList(characterId: Long): List<CharacterFieldValue> =
        characterFieldValueDao.getValuesByCharacterList(characterId)

    suspend fun getFieldValue(characterId: Long, fieldId: Long): CharacterFieldValue? =
        characterFieldValueDao.getValue(characterId, fieldId)

    suspend fun insertFieldValue(value: CharacterFieldValue): Long =
        characterFieldValueDao.insert(value)

    suspend fun insertAllFieldValues(values: List<CharacterFieldValue>) =
        characterFieldValueDao.insertAll(values)

    suspend fun updateFieldValue(value: CharacterFieldValue) =
        characterFieldValueDao.update(value)

    suspend fun deleteAllFieldValuesByCharacter(characterId: Long) =
        characterFieldValueDao.deleteAllByCharacter(characterId)

    suspend fun deleteFieldValue(characterId: Long, fieldId: Long) =
        characterFieldValueDao.deleteValue(characterId, fieldId)

    suspend fun getFieldValueByKey(characterId: Long, fieldKey: String): CharacterFieldValue? =
        characterFieldValueDao.getValueByFieldKey(characterId, fieldKey)

    /**
     * Save all field values for a character at once (replace all).
     * Deletes existing values and inserts the new ones.
     */
    suspend fun saveAllFieldValues(characterId: Long, values: List<CharacterFieldValue>) {
        characterFieldValueDao.replaceAllByCharacter(characterId, values)
    }

    // ===== CharacterStateChange =====
    fun getChangesByCharacter(characterId: Long): LiveData<List<CharacterStateChange>> =
        characterStateChangeDao.getChangesByCharacter(characterId)

    suspend fun getChangesByCharacterList(characterId: Long): List<CharacterStateChange> =
        characterStateChangeDao.getChangesByCharacterList(characterId)

    suspend fun getChangesUpToYear(characterId: Long, year: Int): List<CharacterStateChange> =
        characterStateChangeDao.getChangesUpToYear(characterId, year)

    suspend fun getChangesByField(characterId: Long, fieldKey: String): List<CharacterStateChange> =
        characterStateChangeDao.getChangesByField(characterId, fieldKey)

    suspend fun getChangeById(id: Long): CharacterStateChange? =
        characterStateChangeDao.getChangeById(id)

    suspend fun insertStateChange(change: CharacterStateChange): Long =
        characterStateChangeDao.insert(change)

    suspend fun insertAllStateChanges(changes: List<CharacterStateChange>) =
        characterStateChangeDao.insertAll(changes)

    suspend fun updateStateChange(change: CharacterStateChange) =
        characterStateChangeDao.update(change)

    suspend fun deleteStateChange(change: CharacterStateChange) =
        characterStateChangeDao.delete(change)

    suspend fun deleteAllStateChangesByCharacter(characterId: Long) =
        characterStateChangeDao.deleteAllByCharacter(characterId)

    // ===== Batch count queries =====
    suspend fun getNovelCountsByUniverses(universeIds: List<Long>): Map<Long, Int> =
        novelDao.getNovelCountsByUniverses(universeIds).associate { it.universeId to it.cnt }

    suspend fun getFieldCountsByUniverses(universeIds: List<Long>): Map<Long, Int> =
        fieldDefinitionDao.getFieldCountsByUniverses(universeIds).associate { it.universeId to it.cnt }

    // ===== CharacterTag =====
    fun getTagsByCharacter(characterId: Long): LiveData<List<CharacterTag>> =
        characterTagDao.getTagsByCharacter(characterId)

    suspend fun getTagsByCharacterList(characterId: Long): List<CharacterTag> =
        characterTagDao.getTagsByCharacterList(characterId)

    suspend fun getAllDistinctTags(): List<String> =
        characterTagDao.getAllDistinctTags()

    suspend fun deleteAllTagsByCharacter(characterId: Long) =
        characterTagDao.deleteAllByCharacter(characterId)

    suspend fun insertTags(tags: List<CharacterTag>) =
        characterTagDao.insertAll(tags)

    // ===== NameBank =====
    val allNameBankEntries: LiveData<List<NameBankEntry>> = nameBankDao.getAllNames()
    val availableNameBankEntries: LiveData<List<NameBankEntry>> = nameBankDao.getAvailableNames()

    suspend fun getAvailableNameBankList(): List<NameBankEntry> =
        nameBankDao.getAvailableNamesList()

    suspend fun getAllNameBankList(): List<NameBankEntry> =
        nameBankDao.getAllNamesList()

    suspend fun insertNameBankEntry(entry: NameBankEntry): Long =
        nameBankDao.insert(entry)

    suspend fun updateNameBankEntry(entry: NameBankEntry) =
        nameBankDao.update(entry)

    suspend fun deleteNameBankEntry(entry: NameBankEntry) =
        nameBankDao.delete(entry)

    suspend fun markNameBankAsUsed(id: Long, characterId: Long) =
        nameBankDao.markAsUsed(id, characterId)

    suspend fun markNameBankAsAvailable(id: Long) =
        nameBankDao.markAsAvailable(id)

    // ===== CharacterRelationship =====
    fun getRelationshipsForCharacter(characterId: Long): LiveData<List<CharacterRelationship>> =
        characterRelationshipDao.getRelationshipsForCharacter(characterId)

    suspend fun getRelationshipsForCharacterList(characterId: Long): List<CharacterRelationship> =
        characterRelationshipDao.getRelationshipsForCharacterList(characterId)

    suspend fun getAllRelationships(): List<CharacterRelationship> =
        characterRelationshipDao.getAllRelationships()

    suspend fun insertRelationship(relationship: CharacterRelationship): Long =
        characterRelationshipDao.insert(relationship)

    suspend fun deleteRelationshipById(id: Long) {
        characterRelationshipDao.deleteById(id)
    }
}
