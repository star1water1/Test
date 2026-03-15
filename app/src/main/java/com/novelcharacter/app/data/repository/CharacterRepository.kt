package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.*
import com.novelcharacter.app.data.model.*

class CharacterRepository(
    private val characterDao: CharacterDao,
    private val characterFieldValueDao: CharacterFieldValueDao,
    private val characterStateChangeDao: CharacterStateChangeDao,
    private val characterTagDao: CharacterTagDao,
    private val characterRelationshipDao: CharacterRelationshipDao
) {
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

    suspend fun replaceAllTagsForCharacter(characterId: Long, tags: List<CharacterTag>) =
        characterTagDao.replaceAllForCharacter(characterId, tags)

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
