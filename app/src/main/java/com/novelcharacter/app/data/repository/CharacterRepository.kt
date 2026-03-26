package com.novelcharacter.app.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.*
import com.novelcharacter.app.data.model.*
import com.novelcharacter.app.util.GsonTypes
import java.io.File

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
    suspend fun getCharactersByIds(ids: List<Long>): List<Character> = characterDao.getCharactersByIds(ids)
    suspend fun getAllCharactersByName(name: String): List<Character> = characterDao.getAllCharactersByName(name)
    fun getCharacterByIdLive(id: Long): LiveData<Character?> = characterDao.getCharacterByIdLive(id)
    fun searchCharacters(query: String): LiveData<List<Character>> =
        characterDao.searchCharacters(sanitizeLikeQuery(query))
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
        // 이미지 파일 경로를 트랜잭션 전에 파싱 (DB 삭제 후에는 접근 불가)
        val imageFiles = parseImagePaths(character.imagePaths)

        db.withTransaction {
            nameBankDao.resetUsageByCharacter(character.id)
            recentActivityDao.deleteByEntity(RecentActivity.TYPE_CHARACTER, character.id)
            // 이 캐릭터를 이미지로 참조하는 작품/세계관의 댕글링 참조 정리
            db.novelDao().clearImageCharacterRef(character.id)
            db.universeDao().clearImageCharacterRef(character.id)
            characterDao.delete(character)
        }

        // DB 트랜잭션 성공 후 디스크에서 이미지 파일 삭제
        for (file in imageFiles) {
            try {
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.w("CharacterRepository", "Failed to delete image: ${file.absolutePath}", e)
            }
        }
    }

    private fun parseImagePaths(imagePathsJson: String): List<File> {
        return try {
            val raw: List<String?>? = Gson().fromJson(imagePathsJson, GsonTypes.STRING_LIST)
            raw?.filterNotNull()?.map { File(it) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
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

    /** 같은 작품 내 모든 캐릭터의 특정 필드 값 (백분위 계산용) */
    suspend fun getFieldValuesForNovel(novelId: Long, fieldDefId: Long): List<String> =
        characterFieldValueDao.getFieldValuesForNovel(novelId, fieldDefId)

    /** 같은 세계관 내 모든 캐릭터의 특정 필드 값 (백분위 계산용) */
    suspend fun getFieldValuesForUniverse(universeId: Long, fieldDefId: Long): List<String> =
        characterFieldValueDao.getFieldValuesForUniverse(universeId, fieldDefId)

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

    // ===== 일괄 편집용 배치 메서드 =====

    /** IN 절 999 제한을 회피하기 위한 청크 분할 유틸리티 */
    private val CHUNK_SIZE = 900

    suspend fun batchSetPinned(ids: List<Long>, isPinned: Boolean) {
        db.withTransaction {
            for (chunk in ids.chunked(CHUNK_SIZE)) {
                characterDao.setPinnedForIds(chunk, isPinned)
            }
        }
    }

    suspend fun batchChangeNovel(ids: List<Long>, newNovelId: Long?) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            for (chunk in ids.chunked(CHUNK_SIZE)) {
                characterDao.updateNovelIdForIds(chunk, newNovelId, now)
            }
            // 다른 세계관으로 이동 시 고아 필드값 정리
            if (newNovelId != null) {
                val novel = db.novelDao().getNovelById(newNovelId)
                val newUniverseId = novel?.universeId
                if (newUniverseId != null) {
                    for (charId in ids) {
                        db.characterFieldValueDao().deleteValuesNotInUniverse(charId, newUniverseId)
                        db.factionMembershipDao().deleteMembershipsNotInUniverse(charId, newUniverseId)
                    }
                }
            }
        }
    }

    suspend fun batchAddTags(ids: List<Long>, tags: List<String>) {
        if (tags.isEmpty()) return
        db.withTransaction {
            for (chunk in ids.chunked(CHUNK_SIZE)) {
                val tagEntities = chunk.flatMap { charId ->
                    tags.map { tag -> CharacterTag(characterId = charId, tag = tag) }
                }
                characterTagDao.insertAll(tagEntities) // IGNORE 전략으로 중복 무시
            }
        }
    }

    suspend fun batchRemoveTags(ids: List<Long>, tags: List<String>) {
        if (tags.isEmpty()) return
        // deleteTagsFromCharacters는 이중 IN 절(characterIds + tags) 사용
        // SQLite 변수 제한(API 31 미만: 999)을 초과하지 않도록 chunk 크기 조정
        val adjustedChunk = (CHUNK_SIZE - tags.size).coerceAtLeast(1)
        db.withTransaction {
            for (chunk in ids.chunked(adjustedChunk)) {
                characterTagDao.deleteTagsFromCharacters(chunk, tags)
            }
        }
    }

    suspend fun batchSetFieldValue(ids: List<Long>, fieldDefId: Long, value: String) {
        db.withTransaction {
            for (charId in ids) {
                db.characterFieldValueDao().upsert(
                    CharacterFieldValue(characterId = charId, fieldDefinitionId = fieldDefId, value = value)
                )
            }
        }
    }

    suspend fun batchClearFieldValue(ids: List<Long>, fieldDefId: Long) {
        db.withTransaction {
            for (chunk in ids.chunked(CHUNK_SIZE)) {
                db.characterFieldValueDao().deleteFieldValueForCharacters(chunk, fieldDefId)
            }
        }
    }

    suspend fun batchAppendMemo(ids: List<Long>, text: String, prepend: Boolean) {
        db.withTransaction {
            for (chunk in ids.chunked(CHUNK_SIZE)) {
                val characters = characterDao.getCharactersByIds(chunk)
                val updated = characters.map {
                    val newMemo = when {
                        it.memo.isBlank() -> text
                        prepend -> "$text\n${it.memo}"
                        else -> "${it.memo}\n$text"
                    }
                    it.copy(memo = newMemo, updatedAt = System.currentTimeMillis())
                }
                characterDao.updateAll(updated)
            }
        }
    }

    /**
     * 일괄 삭제. 단일 삭제(deleteCharacter)의 6단계 정리를 배치로 수행:
     * 1. 이미지 경로 수집 (트랜잭션 전)
     * 2. nameBank 사용 해제
     * 3. recentActivity 삭제
     * 4. novel/universe 이미지 참조 정리
     * 5. character 삭제 (FK CASCADE로 태그/필드값/관계/세력소속/타임라인 정리)
     * 6. 이미지 파일 디스크 삭제 (트랜잭션 후)
     */
    suspend fun batchDelete(ids: List<Long>) {
        // 1. 트랜잭션 전: 이미지 경로 수집
        val allImageFiles = mutableListOf<File>()
        for (chunk in ids.chunked(CHUNK_SIZE)) {
            val characters = characterDao.getCharactersByIds(chunk)
            characters.forEach { allImageFiles.addAll(parseImagePaths(it.imagePaths)) }
        }

        // 2-5. 단일 트랜잭션으로 DB 정리
        db.withTransaction {
            for (chunk in ids.chunked(CHUNK_SIZE)) {
                nameBankDao.resetUsageByCharacterIds(chunk)
                recentActivityDao.deleteByEntityIds(RecentActivity.TYPE_CHARACTER, chunk)
                db.novelDao().clearImageCharacterRefs(chunk)
                db.universeDao().clearImageCharacterRefs(chunk)
                characterDao.deleteByIds(chunk) // FK CASCADE가 나머지 정리
            }
        }

        // 6. 트랜잭션 후: 디스크에서 이미지 삭제
        for (file in allImageFiles) {
            try {
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.w("CharacterRepository", "Failed to delete image: ${file.absolutePath}", e)
            }
        }
    }

    /** 선택 캐릭터의 고유 태그 목록 (일괄 삭제 UI용) */
    suspend fun getDistinctTagsForCharacters(ids: List<Long>): List<String> {
        val allTags = mutableSetOf<String>()
        for (chunk in ids.chunked(CHUNK_SIZE)) {
            allTags.addAll(characterTagDao.getDistinctTagsForCharacters(chunk))
        }
        return allTags.sorted()
    }
}
