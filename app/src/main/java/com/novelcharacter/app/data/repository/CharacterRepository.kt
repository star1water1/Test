package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.*
import com.novelcharacter.app.data.model.*
import com.novelcharacter.app.util.GsonTypes
import java.io.File

/**
 * 세계관 이동(작품 변경) 시 필드값 처리 결과 집계.
 * @param remappedValues 같은 key 필드로 이관된 값 수
 * @param removedValues 새 세계관에 대응 필드가 없어 제거된 값 수
 * @param removedMemberships 새 세계관에 없어 제거된 세력 소속 수
 * @param snapshotted 파괴 전 휴지통 스냅샷을 남긴 캐릭터 수(되돌리기 가능)
 */
data class UniverseMoveCounts(
    val remappedValues: Int = 0,
    val removedValues: Int = 0,
    val removedMemberships: Int = 0,
    val snapshotted: Int = 0
) {
    operator fun plus(o: UniverseMoveCounts) = UniverseMoveCounts(
        remappedValues + o.remappedValues,
        removedValues + o.removedValues,
        removedMemberships + o.removedMemberships,
        snapshotted + o.snapshotted
    )

    /** 실제 제거(유실)가 발생했는가 — 고지 필요 여부. */
    val hasRemoval: Boolean get() = removedValues > 0 || removedMemberships > 0
}

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
        val trash = TrashRepository(db)
        val imagePaths = parseImagePaths(character.imagePaths).map { it.absolutePath }

        db.withTransaction {
            // 휴지통 스냅샷 — CASCADE 삭제 전에 연관 데이터를 통째로 보관 (B-7)
            trash.snapshotCharacter(character, imagePaths)
            nameBankDao.resetUsageByCharacter(character.id)
            recentActivityDao.deleteByEntity(RecentActivity.TYPE_CHARACTER, character.id)
            // 이 캐릭터를 이미지로 참조하는 작품/세계관의 댕글링 참조 정리
            db.novelDao().clearImageCharacterRef(character.id)
            db.universeDao().clearImageCharacterRef(character.id)
            characterDao.delete(character)
        }

        // 이미지 파일은 즉시 지우지 않는다 — 복원을 위해 스냅샷이 살아 있는 동안 유지되고,
        // 휴지통 영구 삭제/자동 정리 시점에 함께 삭제된다 (TrashRepository.purgeSnapshot)
        trash.pruneIfNeeded()
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

    /** 여러 캐릭터의 전체 필드값 일괄 조회 (IN 절 999 제한 회피를 위한 청크 분할) */
    suspend fun getValuesForCharacters(characterIds: List<Long>): List<CharacterFieldValue> {
        val result = mutableListOf<CharacterFieldValue>()
        for (chunk in characterIds.chunked(CHUNK_SIZE)) {
            result.addAll(characterFieldValueDao.getValuesForCharacters(chunk))
        }
        return result
    }

    /** 세계관 전체 필드값 일괄 조회 (편집 화면 자동완성 배치 로드용) */
    suspend fun getAllFieldValuesForUniverse(universeId: Long): List<CharacterFieldValue> =
        characterFieldValueDao.getAllValuesForUniverse(universeId)

    // ===== CharacterStateChange =====
    fun getChangesByCharacter(characterId: Long): LiveData<List<CharacterStateChange>> =
        characterStateChangeDao.getChangesByCharacter(characterId)

    suspend fun getChangesByCharacterList(characterId: Long): List<CharacterStateChange> =
        characterStateChangeDao.getChangesByCharacterList(characterId)

    /** 전체 상태변화 일괄 조회 (관계도 시간뷰의 생사 판정용) */
    suspend fun getAllStateChangesList(): List<CharacterStateChange> =
        characterStateChangeDao.getAllChangesList()

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

    /**
     * 캐릭터를 작품에 재배정한다. 다른 세계관으로 이동하는 경우(개발 의도: 변수 제어 · 유기적 연결):
     * - 새 세계관에 **같은 key** 필드가 있으면 값을 그 필드로 **이관(재매핑)**한다(유실 없음).
     * - 대응 필드가 없는 값·새 세계관에 없는 세력 소속은 제거하되, **제거가 실제로 일어나는 경우에만**
     *   파괴 전 휴지통 스냅샷을 남겨 **되돌릴 수 있게** 한다.
     * 반환값으로 이관/제거 건수를 집계해 호출부가 사용자에게 고지할 수 있게 한다.
     */
    suspend fun batchChangeNovel(ids: List<Long>, newNovelId: Long?): UniverseMoveCounts {
        val now = System.currentTimeMillis()
        var agg = UniverseMoveCounts()
        db.withTransaction {
            val newUniverseId = newNovelId?.let { db.novelDao().getNovelById(it)?.universeId }
            if (newUniverseId != null) {
                val trash = TrashRepository(db)
                val allDefsById = db.fieldDefinitionDao().getAllFieldsList().associateBy { it.id }
                val newDefByKey = db.fieldDefinitionDao().getFieldsByUniverseList(newUniverseId).associateBy { it.key }
                for (chunk in ids.chunked(CHUNK_SIZE)) {
                    for (character in characterDao.getCharactersByIds(chunk)) {
                        val curUniverse = character.novelId?.let { db.novelDao().getNovelById(it)?.universeId }
                        if (curUniverse == newUniverseId) continue // 같은 세계관 내 이동은 정리 불필요
                        agg += migrateCharacterFieldsToUniverse(character, newUniverseId, allDefsById, newDefByKey, trash)
                    }
                }
            }
            // novelId 갱신은 이관/스냅샷 '후' — 스냅샷이 옛 소속을 담도록
            for (chunk in ids.chunked(CHUNK_SIZE)) {
                characterDao.updateNovelIdForIds(chunk, newNovelId, now)
            }
        }
        return agg
    }

    /**
     * 한 캐릭터의 필드값을 [newUniverseId] 세계관으로 이관/정리한다. 상위 트랜잭션 안에서 호출할 것.
     * 같은 key 필드로 재매핑하고, 대응 없는 값·타 세계관 세력 소속은 제거하며, 유실 시에만 스냅샷을 남긴다.
     */
    private suspend fun migrateCharacterFieldsToUniverse(
        character: Character,
        newUniverseId: Long,
        allDefsById: Map<Long, FieldDefinition>,
        newDefByKey: Map<String, FieldDefinition>,
        trash: TrashRepository
    ): UniverseMoveCounts {
        val oldValues = characterFieldValueDao.getValuesByCharacterList(character.id)
        val finalValues = ArrayList<CharacterFieldValue>(oldValues.size)
        val usedDefIds = HashSet<Long>()
        var remapped = 0
        var removedValues = 0
        for (v in oldValues) {
            val key = allDefsById[v.fieldDefinitionId]?.key
            val newDef = key?.let { newDefByKey[it] }
            when {
                newDef == null -> removedValues++                          // 새 세계관에 대응 필드 없음
                !usedDefIds.add(newDef.id) -> removedValues++             // 같은 def로 이미 이관됨(중복 방지)
                newDef.id == v.fieldDefinitionId -> finalValues.add(v)    // 이미 새 세계관 소속
                else -> { finalValues.add(v.copy(fieldDefinitionId = newDef.id)); remapped++ }
            }
        }
        val orphanMemberships = db.factionMembershipDao().countMembershipsNotInUniverse(character.id, newUniverseId)
        val willLose = removedValues > 0 || orphanMemberships > 0
        if (willLose) {
            trash.snapshotCharacter(character, parseImagePaths(character.imagePaths).map { it.absolutePath })
        }
        characterFieldValueDao.replaceAllByCharacter(character.id, finalValues)
        if (orphanMemberships > 0) db.factionMembershipDao().deleteMembershipsNotInUniverse(character.id, newUniverseId)
        return UniverseMoveCounts(remapped, removedValues, orphanMemberships, if (willLose) 1 else 0)
    }

    /** 세계관 이동 시 유실될 값·세력 소속 수를 미리 센다(편집화면 확인 다이얼로그·고지용, 파괴 없음). */
    suspend fun countCrossUniverseLoss(characterId: Long, newUniverseId: Long): UniverseMoveCounts {
        val old = characterFieldValueDao.getValuesByCharacterList(characterId)
        val allDefsById = db.fieldDefinitionDao().getAllFieldsList().associateBy { it.id }
        val newKeys = db.fieldDefinitionDao().getFieldsByUniverseList(newUniverseId).map { it.key }.toSet()
        var removed = 0
        var remappable = 0
        for (v in old) {
            val key = allDefsById[v.fieldDefinitionId]?.key
            if (key == null || key !in newKeys) removed++ else remappable++
        }
        val memberships = db.factionMembershipDao().countMembershipsNotInUniverse(characterId, newUniverseId)
        return UniverseMoveCounts(remappable, removed, memberships, 0)
    }

    /**
     * 편집화면에서 캐릭터를 다른 세계관으로 옮겨 저장할 때: 입력한 폼 값을 우선하되,
     * 폼이 채우지 않은 같은 key 필드는 기존 값으로 이관(유실 방지)하고, 대응 없는 값·타 세계관 세력은
     * 제거하되 파괴 전 스냅샷을 남긴다. [formValues]는 characterId가 채워진 상태여야 한다.
     */
    suspend fun updateCharacterAcrossUniverse(
        character: Character,
        formValues: List<CharacterFieldValue>,
        newUniverseId: Long
    ): UniverseMoveCounts {
        return db.withTransaction {
            val allDefsById = db.fieldDefinitionDao().getAllFieldsList().associateBy { it.id }
            val newDefByKey = db.fieldDefinitionDao().getFieldsByUniverseList(newUniverseId).associateBy { it.key }
            val old = characterFieldValueDao.getValuesByCharacterList(character.id)
            val formNonBlank = formValues.filter { it.value.isNotBlank() }
            val formDefIds = formNonBlank.map { it.fieldDefinitionId }.toHashSet()

            val gapFills = LinkedHashMap<Long, CharacterFieldValue>() // 새 defId -> 이관값(폼 미입력분)
            var remapped = 0
            var removed = 0
            for (v in old) {
                if (v.value.isBlank()) continue
                val key = allDefsById[v.fieldDefinitionId]?.key
                val newDef = key?.let { newDefByKey[it] }
                if (newDef == null) { removed++; continue }        // 대응 필드 없음 → 제거
                if (newDef.id in formDefIds) continue              // 사용자가 새 값 입력 → 폼 우선
                if (gapFills.containsKey(newDef.id)) continue       // 이미 이관됨(중복 방지)
                gapFills[newDef.id] = v.copy(fieldDefinitionId = newDef.id)
                remapped++
            }
            val orphanMemberships = db.factionMembershipDao().countMembershipsNotInUniverse(character.id, newUniverseId)
            val willLose = removed > 0 || orphanMemberships > 0
            if (willLose) {
                val persisted = characterDao.getCharacterById(character.id) ?: character
                TrashRepository(db).snapshotCharacter(persisted, parseImagePaths(persisted.imagePaths).map { it.absolutePath })
            }
            characterDao.update(character)
            characterFieldValueDao.replaceAllByCharacter(character.id, formNonBlank + gapFills.values)
            if (orphanMemberships > 0) db.factionMembershipDao().deleteMembershipsNotInUniverse(character.id, newUniverseId)
            UniverseMoveCounts(remapped, removed, orphanMemberships, if (willLose) 1 else 0)
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
     * ※ 삭제 전 캐릭터별 휴지통 스냅샷 보관, 이미지 파일은 휴지통 정리 시점에 삭제 (B-7)
     */
    suspend fun batchDelete(ids: List<Long>) {
        val trash = TrashRepository(db)

        // 2-5. 단일 트랜잭션으로 DB 정리 (삭제 전 캐릭터별 휴지통 스냅샷 보관)
        db.withTransaction {
            for (chunk in ids.chunked(CHUNK_SIZE)) {
                val characters = characterDao.getCharactersByIds(chunk)
                for (character in characters) {
                    trash.snapshotCharacter(
                        character,
                        parseImagePaths(character.imagePaths).map { it.absolutePath }
                    )
                }
                nameBankDao.resetUsageByCharacterIds(chunk)
                recentActivityDao.deleteByEntityIds(RecentActivity.TYPE_CHARACTER, chunk)
                db.novelDao().clearImageCharacterRefs(chunk)
                db.universeDao().clearImageCharacterRefs(chunk)
                characterDao.deleteByIds(chunk) // FK CASCADE가 나머지 정리
            }
        }

        // 이미지 파일은 스냅샷과 함께 유지 — 휴지통 정리 시점에 삭제 (B-7)
        trash.pruneIfNeeded()
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
