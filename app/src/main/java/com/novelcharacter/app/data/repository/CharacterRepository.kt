package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.*
import com.novelcharacter.app.data.model.*

class CharacterRepository(
    private val db: AppDatabase,
    private val characterDao: CharacterDao,
    private val characterFieldValueDao: CharacterFieldValueDao,
    private val characterStateChangeDao: CharacterStateChangeDao,
    private val characterTagDao: CharacterTagDao,
    private val characterRelationshipDao: CharacterRelationshipDao,
    private val nameBankDao: NameBankDao
) {
    private val recentActivityDao get() = db.recentActivityDao()
    // ===== Character =====
    val allCharacters: LiveData<List<Character>> = characterDao.getAllCharacters()

    suspend fun getAllCharactersList(): List<Character> = characterDao.getAllCharactersList()
    fun getCharactersByNovel(novelId: Long): LiveData<List<Character>> =
        characterDao.getCharactersByNovel(novelId)
    suspend fun getCharactersByNovelList(novelId: Long): List<Character> =
        characterDao.getCharactersByNovelList(novelId)
    suspend fun getCharactersByUniverseList(universeId: Long): List<Character> =
        characterDao.getCharactersByUniverseList(universeId)
    suspend fun getCharacterById(id: Long): Character? = characterDao.getCharacterById(id)
    fun getCharacterByIdLive(id: Long): LiveData<Character?> = characterDao.getCharacterByIdLive(id)
    fun searchCharacters(query: String): LiveData<List<Character>> =
        characterDao.searchCharacters(query)
    suspend fun insertCharacter(character: Character): Long {
        return db.withTransaction {
            val next = if (character.novelId != null) {
                characterDao.getNextDisplayOrderInNovel(character.novelId)
            } else {
                characterDao.getNextDisplayOrderNoNovel()
            }
            characterDao.insert(character.copy(displayOrder = next))
        }
    }
    suspend fun updateCharacter(character: Character) = characterDao.update(character)

    suspend fun updateCharacterWithFields(character: Character, values: List<CharacterFieldValue>) {
        db.withTransaction {
            characterDao.update(character)
            characterFieldValueDao.replaceAllByCharacter(character.id, values)
        }
    }

    suspend fun deleteCharacter(character: Character) {
        db.withTransaction {
            nameBankDao.resetUsageByCharacter(character.id)
            recentActivityDao.deleteByEntity(RecentActivity.TYPE_CHARACTER, character.id)
            // 이 캐릭터를 이미지로 참조하는 작품/세계관의 댕글링 참조 정리
            db.novelDao().clearImageCharacterRef(character.id)
            db.universeDao().clearImageCharacterRef(character.id)
            characterDao.delete(character)
        }
    }
    suspend fun insertAllCharacters(characters: List<Character>) = characterDao.insertAll(characters)
    suspend fun updateCharacterDisplayOrders(characters: List<Character>) = characterDao.updateAll(characters)

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

    suspend fun getRelationshipById(id: Long): CharacterRelationship? =
        characterRelationshipDao.getById(id)

    suspend fun getAllRelationships(): List<CharacterRelationship> =
        characterRelationshipDao.getAllRelationships()

    suspend fun insertRelationship(relationship: CharacterRelationship): Long {
        require(relationship.characterId1 != relationship.characterId2) {
            "A character cannot have a relationship with itself"
        }
        return characterRelationshipDao.insert(relationship)
    }

    suspend fun deleteRelationshipById(id: Long) {
        characterRelationshipDao.deleteById(id)
    }

    suspend fun updateRelationship(relationship: CharacterRelationship) {
        characterRelationshipDao.update(relationship)
    }

    suspend fun updateRelationshipOrders(relationships: List<CharacterRelationship>) {
        characterRelationshipDao.updateAll(relationships)
    }

    // ===== CharacterRelationshipChange =====
    private val relationshipChangeDao get() = db.characterRelationshipChangeDao()

    fun getRelationshipChanges(relationshipId: Long): LiveData<List<CharacterRelationshipChange>> =
        relationshipChangeDao.getChangesForRelationship(relationshipId)

    suspend fun getRelationshipChangesList(relationshipId: Long): List<CharacterRelationshipChange> =
        relationshipChangeDao.getChangesForRelationshipList(relationshipId)

    suspend fun getRelationshipChangeAtYear(relationshipId: Long, year: Int): CharacterRelationshipChange? =
        relationshipChangeDao.getChangeAtYear(relationshipId, year)

    suspend fun getAllRelationshipChanges(): List<CharacterRelationshipChange> =
        relationshipChangeDao.getAllChanges()

    suspend fun insertRelationshipChange(change: CharacterRelationshipChange): Long =
        relationshipChangeDao.insert(change)

    suspend fun updateRelationshipChange(change: CharacterRelationshipChange) =
        relationshipChangeDao.update(change)

    suspend fun deleteRelationshipChange(change: CharacterRelationshipChange) =
        relationshipChangeDao.delete(change)

    /**
     * 특정 시점에서의 관계 타입을 resolve한다.
     * RelationshipChange가 있으면 해당 시점 이전의 가장 최근 변화를 반환.
     * 없으면 기본 관계의 relationshipType을 반환.
     */
    suspend fun resolveRelationshipTypeAtYear(relationship: CharacterRelationship, year: Int): String {
        val change = relationshipChangeDao.getChangeAtYear(relationship.id, year)
        return change?.relationshipType ?: relationship.relationshipType
    }

    /**
     * 특정 시점에서의 관계 강도를 resolve한다.
     */
    suspend fun resolveRelationshipIntensityAtYear(relationship: CharacterRelationship, year: Int): Int {
        val change = relationshipChangeDao.getChangeAtYear(relationship.id, year)
        return change?.intensity ?: relationship.intensity
    }

    suspend fun setPinned(id: Long, isPinned: Boolean) =
        characterDao.setPinned(id, isPinned)
}
