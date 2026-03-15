package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.model.*

/**
 * Facade that delegates to domain-specific repositories.
 * Kept for backward compatibility with existing code that references AppRepository.
 */
class AppRepository(
    private val novelRepository: NovelRepository,
    private val characterRepository: CharacterRepository,
    private val timelineRepository: TimelineRepository,
    private val universeRepository: UniverseRepository,
    private val nameBankRepository: NameBankRepository
) {
    // ===== Novel =====
    val allNovels: LiveData<List<Novel>> get() = novelRepository.allNovels

    suspend fun getAllNovelsList(): List<Novel> = novelRepository.getAllNovelsList()
    suspend fun getNovelById(id: Long): Novel? = novelRepository.getNovelById(id)
    suspend fun insertNovel(novel: Novel): Long = novelRepository.insertNovel(novel)
    suspend fun updateNovel(novel: Novel) = novelRepository.updateNovel(novel)
    suspend fun deleteNovel(novel: Novel) = novelRepository.deleteNovel(novel)
    fun getNovelsByUniverse(universeId: Long): LiveData<List<Novel>> =
        novelRepository.getNovelsByUniverse(universeId)
    suspend fun getNovelsByUniverseList(universeId: Long): List<Novel> =
        novelRepository.getNovelsByUniverseList(universeId)
    fun searchNovels(query: String): LiveData<List<Novel>> =
        novelRepository.searchNovels(query)

    // ===== Character =====
    val allCharacters: LiveData<List<Character>> get() = characterRepository.allCharacters

    suspend fun getAllCharactersList(): List<Character> = characterRepository.getAllCharactersList()
    fun getCharactersByNovel(novelId: Long): LiveData<List<Character>> =
        characterRepository.getCharactersByNovel(novelId)
    suspend fun getCharactersByNovelList(novelId: Long): List<Character> =
        characterRepository.getCharactersByNovelList(novelId)
    suspend fun getCharacterById(id: Long): Character? = characterRepository.getCharacterById(id)
    fun getCharacterByIdLive(id: Long): LiveData<Character?> = characterRepository.getCharacterByIdLive(id)
    fun searchCharacters(query: String): LiveData<List<Character>> =
        characterRepository.searchCharacters(query)
    suspend fun insertCharacter(character: Character): Long = characterRepository.insertCharacter(character)
    suspend fun updateCharacter(character: Character) = characterRepository.updateCharacter(character)
    suspend fun deleteCharacter(character: Character) = characterRepository.deleteCharacter(character)
    suspend fun insertAllCharacters(characters: List<Character>) = characterRepository.insertAllCharacters(characters)

    // ===== Timeline =====
    val allEvents: LiveData<List<TimelineEvent>> get() = timelineRepository.allEvents

    suspend fun getAllEventsList(): List<TimelineEvent> = timelineRepository.getAllEventsList()
    fun getEventsByNovel(novelId: Long): LiveData<List<TimelineEvent>> =
        timelineRepository.getEventsByNovel(novelId)
    suspend fun getEventById(id: Long): TimelineEvent? = timelineRepository.getEventById(id)
    fun getEventsByYearRange(startYear: Int, endYear: Int): LiveData<List<TimelineEvent>> =
        timelineRepository.getEventsByYearRange(startYear, endYear)
    fun searchEvents(query: String): LiveData<List<TimelineEvent>> =
        timelineRepository.searchEvents(query)
    suspend fun insertEvent(event: TimelineEvent): Long = timelineRepository.insertEvent(event)
    suspend fun updateEvent(event: TimelineEvent) = timelineRepository.updateEvent(event)
    suspend fun deleteEvent(event: TimelineEvent) = timelineRepository.deleteEvent(event)
    suspend fun insertAllEvents(events: List<TimelineEvent>) = timelineRepository.insertAllEvents(events)
    fun getEventsByUniverse(universeId: Long): LiveData<List<TimelineEvent>> =
        timelineRepository.getEventsByUniverse(universeId)
    fun getEventsByYearMonthDay(year: Int, month: Int?, day: Int?): LiveData<List<TimelineEvent>> =
        timelineRepository.getEventsByYearMonthDay(year, month, day)
    fun getEventsForCharacterInRange(characterId: Long, startYear: Int, endYear: Int): LiveData<List<TimelineEvent>> =
        timelineRepository.getEventsForCharacterInRange(characterId, startYear, endYear)
    fun getEventsByNovelInRange(novelId: Long, startYear: Int, endYear: Int): LiveData<List<TimelineEvent>> =
        timelineRepository.getEventsByNovelInRange(novelId, startYear, endYear)

    // ===== Timeline-Character linkage =====
    suspend fun linkCharacterToEvent(eventId: Long, characterId: Long) =
        timelineRepository.linkCharacterToEvent(eventId, characterId)

    suspend fun unlinkCharacterFromEvent(eventId: Long, characterId: Long) =
        timelineRepository.unlinkCharacterFromEvent(eventId, characterId)

    suspend fun updateEventCharacters(eventId: Long, characterIds: List<Long>) =
        timelineRepository.updateEventCharacters(eventId, characterIds)

    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        timelineRepository.getCharacterIdsForEvent(eventId)

    fun getEventsForCharacter(characterId: Long): LiveData<List<TimelineEvent>> =
        timelineRepository.getEventsForCharacter(characterId)

    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        timelineRepository.getCharactersForEvent(eventId)

    // ===== Universe =====
    val allUniverses: LiveData<List<Universe>> get() = universeRepository.allUniverses

    suspend fun getAllUniversesList(): List<Universe> = universeRepository.getAllUniversesList()
    suspend fun getUniverseById(id: Long): Universe? = universeRepository.getUniverseById(id)
    suspend fun insertUniverse(universe: Universe): Long = universeRepository.insertUniverse(universe)
    suspend fun updateUniverse(universe: Universe) = universeRepository.updateUniverse(universe)
    suspend fun deleteUniverse(universe: Universe) = universeRepository.deleteUniverse(universe)

    // ===== FieldDefinition =====
    fun getFieldsByUniverse(universeId: Long): LiveData<List<FieldDefinition>> =
        universeRepository.getFieldsByUniverse(universeId)

    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        universeRepository.getFieldsByUniverseList(universeId)

    suspend fun getFieldById(id: Long): FieldDefinition? =
        universeRepository.getFieldById(id)

    suspend fun getFieldByKey(universeId: Long, key: String): FieldDefinition? =
        universeRepository.getFieldByKey(universeId, key)

    suspend fun getFieldsByType(universeId: Long, type: String): List<FieldDefinition> =
        universeRepository.getFieldsByType(universeId, type)

    suspend fun getGroupNames(universeId: Long): List<String> =
        universeRepository.getGroupNames(universeId)

    suspend fun insertField(field: FieldDefinition): Long =
        universeRepository.insertField(field)

    suspend fun insertAllFields(fields: List<FieldDefinition>) =
        universeRepository.insertAllFields(fields)

    suspend fun updateField(field: FieldDefinition) =
        universeRepository.updateField(field)

    suspend fun deleteField(field: FieldDefinition) =
        universeRepository.deleteField(field)

    suspend fun deleteAllFieldsByUniverse(universeId: Long) =
        universeRepository.deleteAllFieldsByUniverse(universeId)

    // ===== CharacterFieldValue =====
    fun getValuesByCharacter(characterId: Long): LiveData<List<CharacterFieldValue>> =
        characterRepository.getValuesByCharacter(characterId)

    suspend fun getValuesByCharacterList(characterId: Long): List<CharacterFieldValue> =
        characterRepository.getValuesByCharacterList(characterId)

    suspend fun getFieldValue(characterId: Long, fieldId: Long): CharacterFieldValue? =
        characterRepository.getFieldValue(characterId, fieldId)

    suspend fun insertFieldValue(value: CharacterFieldValue): Long =
        characterRepository.insertFieldValue(value)

    suspend fun insertAllFieldValues(values: List<CharacterFieldValue>) =
        characterRepository.insertAllFieldValues(values)

    suspend fun updateFieldValue(value: CharacterFieldValue) =
        characterRepository.updateFieldValue(value)

    suspend fun deleteAllFieldValuesByCharacter(characterId: Long) =
        characterRepository.deleteAllFieldValuesByCharacter(characterId)

    suspend fun deleteFieldValue(characterId: Long, fieldId: Long) =
        characterRepository.deleteFieldValue(characterId, fieldId)

    suspend fun getFieldValueByKey(characterId: Long, fieldKey: String): CharacterFieldValue? =
        characterRepository.getFieldValueByKey(characterId, fieldKey)

    suspend fun saveAllFieldValues(characterId: Long, values: List<CharacterFieldValue>) =
        characterRepository.saveAllFieldValues(characterId, values)

    // ===== CharacterStateChange =====
    fun getChangesByCharacter(characterId: Long): LiveData<List<CharacterStateChange>> =
        characterRepository.getChangesByCharacter(characterId)

    suspend fun getChangesByCharacterList(characterId: Long): List<CharacterStateChange> =
        characterRepository.getChangesByCharacterList(characterId)

    suspend fun getChangesUpToYear(characterId: Long, year: Int): List<CharacterStateChange> =
        characterRepository.getChangesUpToYear(characterId, year)

    suspend fun getChangesByField(characterId: Long, fieldKey: String): List<CharacterStateChange> =
        characterRepository.getChangesByField(characterId, fieldKey)

    suspend fun getChangeById(id: Long): CharacterStateChange? =
        characterRepository.getChangeById(id)

    suspend fun insertStateChange(change: CharacterStateChange): Long =
        characterRepository.insertStateChange(change)

    suspend fun insertAllStateChanges(changes: List<CharacterStateChange>) =
        characterRepository.insertAllStateChanges(changes)

    suspend fun updateStateChange(change: CharacterStateChange) =
        characterRepository.updateStateChange(change)

    suspend fun deleteStateChange(change: CharacterStateChange) =
        characterRepository.deleteStateChange(change)

    suspend fun deleteAllStateChangesByCharacter(characterId: Long) =
        characterRepository.deleteAllStateChangesByCharacter(characterId)

    // ===== Batch count queries =====
    suspend fun getNovelCountsByUniverses(universeIds: List<Long>): Map<Long, Int> =
        universeRepository.getNovelCountsByUniverses(universeIds)

    suspend fun getFieldCountsByUniverses(universeIds: List<Long>): Map<Long, Int> =
        universeRepository.getFieldCountsByUniverses(universeIds)

    // ===== CharacterTag =====
    fun getTagsByCharacter(characterId: Long): LiveData<List<CharacterTag>> =
        characterRepository.getTagsByCharacter(characterId)

    suspend fun getTagsByCharacterList(characterId: Long): List<CharacterTag> =
        characterRepository.getTagsByCharacterList(characterId)

    suspend fun getAllDistinctTags(): List<String> =
        characterRepository.getAllDistinctTags()

    suspend fun deleteAllTagsByCharacter(characterId: Long) =
        characterRepository.deleteAllTagsByCharacter(characterId)

    suspend fun insertTags(tags: List<CharacterTag>) =
        characterRepository.insertTags(tags)

    // ===== NameBank =====
    val allNameBankEntries: LiveData<List<NameBankEntry>> get() = nameBankRepository.allNameBankEntries
    val availableNameBankEntries: LiveData<List<NameBankEntry>> get() = nameBankRepository.availableNameBankEntries

    suspend fun getAvailableNameBankList(): List<NameBankEntry> =
        nameBankRepository.getAvailableNameBankList()

    suspend fun getAllNameBankList(): List<NameBankEntry> =
        nameBankRepository.getAllNameBankList()

    suspend fun insertNameBankEntry(entry: NameBankEntry): Long =
        nameBankRepository.insertNameBankEntry(entry)

    suspend fun updateNameBankEntry(entry: NameBankEntry) =
        nameBankRepository.updateNameBankEntry(entry)

    suspend fun deleteNameBankEntry(entry: NameBankEntry) =
        nameBankRepository.deleteNameBankEntry(entry)

    suspend fun markNameBankAsUsed(id: Long, characterId: Long) =
        nameBankRepository.markNameBankAsUsed(id, characterId)

    suspend fun markNameBankAsAvailable(id: Long) =
        nameBankRepository.markNameBankAsAvailable(id)

    // ===== CharacterRelationship =====
    fun getRelationshipsForCharacter(characterId: Long): LiveData<List<CharacterRelationship>> =
        characterRepository.getRelationshipsForCharacter(characterId)

    suspend fun getRelationshipsForCharacterList(characterId: Long): List<CharacterRelationship> =
        characterRepository.getRelationshipsForCharacterList(characterId)

    suspend fun getAllRelationships(): List<CharacterRelationship> =
        characterRepository.getAllRelationships()

    suspend fun insertRelationship(relationship: CharacterRelationship): Long =
        characterRepository.insertRelationship(relationship)

    suspend fun deleteRelationshipById(id: Long) =
        characterRepository.deleteRelationshipById(id)
}
