package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.*
import com.novelcharacter.app.data.model.*
import com.novelcharacter.app.util.GlobalScopeFieldMove
import com.novelcharacter.app.util.GsonTypes
import com.novelcharacter.app.util.SqlInChunks
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
    val snapshotted: Int = 0,
    /**
     * 전역 구역의 값 중 **안전하지 않아 보관 값으로 남긴** 수 (B-128).
     *
     * [removedValues]와 갈라 두는 것이 요점이다 — 이쪽은 **유실이 아니다.** 값은 그대로 살아
     * 원래 정의를 가리키고, 캐릭터를 되돌리면 다시 보인다. 합치면 확인 다이얼로그가
     * *"제거됩니다"*라고 말하는데 실제로는 제거되지 않는 거짓 고지가 된다.
     */
    val keptGlobalValues: Int = 0
) {
    operator fun plus(o: UniverseMoveCounts) = UniverseMoveCounts(
        remappedValues + o.remappedValues,
        removedValues + o.removedValues,
        removedMemberships + o.removedMemberships,
        snapshotted + o.snapshotted,
        keptGlobalValues + o.keptGlobalValues
    )

    /** 실제 제거(유실)가 발생했는가 — 고지 필요 여부. **보관은 유실이 아니라 여기 들지 않는다.** */
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
    suspend fun getCharactersByIds(ids: List<Long>): List<Character> =
        SqlInChunks.flat(ids) { characterDao.getCharactersByIds(it) }
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

    // 값 라이브러리 수확 — insert-only·runCatching 내장이라 저장 실패로 이어지지 않는다.
    // 각 쓰기 메서드의 withTransaction 블록 '뒤'에서 호출해 커밋 후 수확한다.
    private val fieldLibrary by lazy { FieldValueLibraryRepository(db) }

    /**
     * 편집 폼의 저장 (N2).
     *
     * @param coveredFieldDefinitionIds 폼이 실제로 렌더한 필드 정의 id 집합.
     *   **폼의 권한은 딱 이 집합까지다.** 커버 밖의 기존 값은 폼이 판단할 근거가 없으므로
     *   그대로 둔다 — 종전에는 폼이 넘긴 목록으로 통째로 대체해서, 작품을 '없음'으로 바꾸면
     *   (폼이 필드를 0개 렌더) 필드값이 전량 무음 삭제됐다. 같은 조작의 일괄 편집 경로
     *   (`batchChangeNovel`의 `newUniverseId == null` 가드)는 반대로 전량 보존한다.
     *   null이면 폼이 값 목록 전체를 책임진다는 뜻(레거시 호출부 보호).
     * @return 폼 밖이라 보존된 값의 개수 — 호출부가 "화면에 없지만 남아 있다"를 알린다.
     */
    suspend fun updateCharacterWithFields(
        character: Character,
        values: List<CharacterFieldValue>,
        coveredFieldDefinitionIds: Set<Long>? = null
    ): Int {
        var preserved = 0
        db.withTransaction {
            characterDao.update(character)
            val finalValues = if (coveredFieldDefinitionIds == null) {
                values
            } else {
                val existing = characterFieldValueDao.getValuesByCharacterList(character.id)
                preserved = CharacterFieldValueMerge.preservedCount(values, coveredFieldDefinitionIds, existing)
                CharacterFieldValueMerge.merge(values, coveredFieldDefinitionIds, existing)
            }
            characterFieldValueDao.replaceAllByCharacter(character.id, finalValues)
        }
        fieldLibrary.harvestForCharacter(character.id)
        return preserved
    }

    /**
     * **세계관을 떠나 무소속이 되는 저장** (B-128의 반대 방향).
     *
     * 이 방향은 '이동'이 아니라 '이탈'이라 세력 소속·스냅샷을 건드리지 않는다 —
     * [updateCharacterAcrossUniverse]로 태우면 *"새 세계관에 없는 소속"*이 전부 고아가 되어
     * **세력 소속이 말없이 지워진다.** 확정이 정한 것은 값의 처분뿐이므로 거기까지만 한다.
     *
     * 하는 일은 [updateCharacterWithFields]와 같되(폼 커버 밖 값은 그대로 보존 — N2),
     * 그 보존분 중 **전역 구역에 짝이 있는 것을 확정 규칙대로 이어 준다**: 타입이 호환되고
     * 대상이 비어 있을 때만 옮기고, 아니면 보관 값으로 남긴다. 어느 쪽이든 값은 살아 있다.
     *
     * @return 이어 준 수와 보관한 수, 그리고 폼 밖이라 보존된 수([UniverseMoveCounts.snapshotted]는
     *   쓰지 않는다 — 이 경로는 파괴가 없어 백업할 것이 없다).
     */
    suspend fun updateCharacterLeavingUniverse(
        character: Character,
        values: List<CharacterFieldValue>,
        coveredFieldDefinitionIds: Set<Long>?
    ): LeaveUniverseResult {
        var result = LeaveUniverseResult()
        db.withTransaction {
            characterDao.update(character)
            val existing = characterFieldValueDao.getValuesByCharacterList(character.id)
            val merged = if (coveredFieldDefinitionIds == null) values
            else CharacterFieldValueMerge.merge(values, coveredFieldDefinitionIds, existing)
            val preserved = if (coveredFieldDefinitionIds == null) 0
            else CharacterFieldValueMerge.preservedCount(values, coveredFieldDefinitionIds, existing)

            val allDefsById = db.fieldDefinitionDao().getAllFieldsAllTypes().associateBy { it.id }
            val globalFields = db.fieldDefinitionDao().getGlobalFieldsList()
            // 옮길 후보는 **세계관에 속한 캐릭터 필드값**이고, 도착지는 전역 구역이다.
            // '대상이 비었는가'에는 병합 결과에서 이미 값이 있는 전역 필드를 넣는다.
            val occupied = merged.filter { it.value.isNotBlank() }.mapTo(HashSet()) { it.fieldDefinitionId }
            val moving = merged.mapNotNull { v ->
                val def = allDefsById[v.fieldDefinitionId] ?: return@mapNotNull null
                if (def.universeId == null) return@mapNotNull null
                if (def.entityType != FieldDefinition.ENTITY_CHARACTER) return@mapNotNull null
                GlobalScopeFieldMove.Candidate(def, v.value)
            }
            val plan = GlobalScopeFieldMove.plan(moving, globalFields, occupied)

            val finalValues = if (plan.transfers.isEmpty()) merged else {
                // **옮긴 값이 먼저 자리를 잡는다.** 대상 전역 필드에 빈 값 행이 남아 있을 수 있는데
                // (빈 값은 '점유'가 아니라 이관을 막지 않는다), 목록 순서대로 걸러 내면 그 빈 행이
                // 앞서서 이기고 **옮긴 값이 조용히 버려진다.**
                val moved = merged.mapNotNull { v ->
                    val to = plan.transfers[v.fieldDefinitionId] ?: return@mapNotNull null
                    v.copy(id = 0, fieldDefinitionId = to)
                }
                val rest = merged.filter { it.fieldDefinitionId !in plan.transfers }
                val taken = HashSet<Long>()
                (moved + rest).filter { taken.add(it.fieldDefinitionId) }
            }
            characterFieldValueDao.replaceAllByCharacter(character.id, finalValues)
            // **보존 수에서 옮긴 것을 뺀다.** `preserved`는 이관 전에 센 것이라 옮겨 간 값까지
            // 들어 있는데, 그것을 그대로 고지하면 *"화면에 없는 필드값 N개를 보관했습니다 —
            // 작품을 다시 배정하면 그대로 보입니다"*라고 말하게 된다. 옮긴 값은 **지금 화면에
            // 보이고** 되돌릴 것도 없으니 두 문장이 다 거짓이다.
            // 옮길 후보는 폼이 렌더하지 않는 세계관 필드뿐이라 이관분은 언제나 보존분의 부분집합이다.
            result = LeaveUniverseResult(
                transferred = plan.transfers.size,
                kept = plan.kept.size,
                preserved = (preserved - plan.transfers.size).coerceAtLeast(0)
            )
        }
        fieldLibrary.harvestForCharacter(character.id)
        return result
    }

    /** [updateCharacterLeavingUniverse]의 결과 — 호출부가 고지에 쓴다. 어느 값도 유실되지 않는다. */
    data class LeaveUniverseResult(
        /** 전역 구역의 짝으로 이어 준 값 수. */
        val transferred: Int = 0,
        /** 안전하지 않아 보관 값으로 남긴 값 수(타입 불일치·대상 점유·짝 없음). */
        val kept: Int = 0,
        /** 폼이 렌더하지 않아 그대로 둔 값 수 (N2) — **이관분은 빠져 있다**(그쪽은 화면에 보인다). */
        val preserved: Int = 0
    )

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
        // resolveAgeLinkage처럼 상위 트랜잭션 안에서 불릴 수 있다 — 수확은 캐릭터 1명 분량의
        // 소규모 작업이고 내부 runCatching이라 상위 저장을 실패시키지 않는다.
        fieldLibrary.harvestForCharacter(characterId)
    }

    /** 같은 작품 내 모든 캐릭터의 특정 필드 값 (백분위 계산용) */
    suspend fun getFieldValuesForNovel(novelId: Long, fieldDefId: Long): List<String> =
        characterFieldValueDao.getFieldValuesForNovel(novelId, fieldDefId)

    /** 같은 세계관 내 모든 캐릭터의 특정 필드 값 (백분위 계산용) */
    suspend fun getFieldValuesForUniverse(universeId: Long, fieldDefId: Long): List<String> =
        characterFieldValueDao.getFieldValuesForUniverse(universeId, fieldDefId)

    /** 여러 캐릭터의 전체 필드값 일괄 조회 (IN 절 변수 상한은 [SqlInChunks]가 지킨다) */
    suspend fun getValuesForCharacters(characterIds: List<Long>): List<CharacterFieldValue> =
        SqlInChunks.flat(characterIds) { characterFieldValueDao.getValuesForCharacters(it) }

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

    // 상태변화 쓰기는 값 라이브러리를 건드리지 않는다 (B-60 · 확정 20번 ㄱ1) —
    // `usageCount`가 '지금 쓰이는 횟수'로 정해졌고, 이력 쓰기는 **현재 값을 바꾸지 않으므로**
    // 셀 것도 새로 등재할 것도 없다(`__birth`·`__death`의 필드 동기화는 그 경로가 따로 지고,
    // 그쪽은 현재 값 쓰기라 원래 수확 대상이다). 모집단 규약은
    // [FieldValueLibraryRepository.harvestUniversesOrThrow]가 단일 소스다.

    suspend fun insertStateChange(change: CharacterStateChange): Long =
        characterStateChangeDao.insert(change)

    suspend fun insertAllStateChanges(changes: List<CharacterStateChange>) =
        characterStateChangeDao.insertAll(changes)

    suspend fun updateStateChange(change: CharacterStateChange) =
        characterStateChangeDao.update(change)

    /**
     * 상태변화 이력 삭제 — 삭제 전 휴지통 스냅샷을 남긴다.
     *
     * 종전에는 이 경로만 휴지통을 거치지 않아, 캐릭터·사건 삭제는 되돌릴 수 있는데
     * 이력 한 줄을 잘못 지우면 영영 사라졌다("지운 것은 되돌릴 수 있다"는 약속의 구멍).
     * 스냅샷과 삭제를 한 트랜잭션으로 묶는다 — 삭제만 커밋되면 그 자체가 무통보 유실이다.
     *
     * @param snapshot false면 스냅샷 없이 지운다. **파생 정리 경로 전용**이다
     *   ([com.novelcharacter.app.util.SemanticFieldSyncHelper]) — 출생·사망 사건 삭제가
     *   함께 지우는 이력은 사건 스냅샷이 이미 담으므로, 여기서 또 담으면 같은 이력이 두 벌
     *   남아 복원이 중복되고 거짓 경고가 뜬다(스냅샷은 겹치지 않고 이어붙는다).
     */
    suspend fun deleteStateChange(change: CharacterStateChange, snapshot: Boolean = true) {
        if (!snapshot) {
            characterStateChangeDao.delete(change)
            return
        }
        val trash = TrashRepository(db)
        db.withTransaction {
            trash.snapshotStateChange(change)
            characterStateChangeDao.delete(change)
        }
        trash.pruneIfNeeded()
    }

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

    // ===== CharacterQuote (명대사 — 사용자 요청 2026.08.20) =====
    //
    // **DAO를 생성자가 아니라 db에서 집는다** — `recentActivityDao`와 같은 자리다. 이 표를
    // 쓰는 곳이 상세 화면 하나여서 생성자를 늘리면 부르는 세 자리가 전부 인자 하나씩 는다.

    private val characterQuoteDao get() = db.characterQuoteDao()

    fun getQuotesByCharacter(characterId: Long): LiveData<List<CharacterQuote>> =
        characterQuoteDao.getQuotesByCharacter(characterId)

    suspend fun getQuotesByCharacterList(characterId: Long): List<CharacterQuote> =
        characterQuoteDao.getQuotesByCharacterList(characterId)

    /** 여러 캐릭터의 대사 — 생일 모달·대결 카드가 쓴다. 통로는 R-54(청크)를 지난다. */
    suspend fun getQuotesForCharacters(characterIds: List<Long>): List<CharacterQuote> =
        SqlInChunks.flat(characterIds) { characterQuoteDao.getQuotesForCharacters(it) }

    /** 새 대사는 **맨 끝에** 붙는다 — 적은 차례가 곧 목록의 차례다(사용자가 뒤에 끌어 옮긴다). */
    suspend fun insertQuote(quote: CharacterQuote): Long {
        val ordered =
            if (quote.sortOrder > 0) quote
            else quote.copy(sortOrder = characterQuoteDao.nextSortOrder(quote.characterId))
        return characterQuoteDao.insert(ordered)
    }

    suspend fun updateQuote(quote: CharacterQuote) = characterQuoteDao.update(quote)

    /**
     * 드래그로 바뀐 차례를 **한 트랜잭션에** 쓴다.
     * 한 칸 옮길 때마다 쓰면 열 칸에 열 번이 돌고, 중간에 끊기면 차례가 반쯤 뒤섞인다.
     */
    suspend fun updateQuoteOrders(quotes: List<CharacterQuote>) = db.withTransaction {
        characterQuoteDao.updateAll(quotes.mapIndexed { index, q -> q.copy(sortOrder = index) })
    }

    /**
     * **휴지통에 담지 않는다** — 관계·관계 변화와 같은 처분이다(상태 변화만 담는다).
     * 대신 지우기 전에 창이 묻고, 그 창이 *"휴지통에는 남지 않는다"*를 적는다(개발 의도 2번:
     * 유실이 조용하지 않으면 된다).
     */
    suspend fun deleteQuote(quote: CharacterQuote) = characterQuoteDao.delete(quote)

    suspend fun deleteAllQuotesByCharacter(characterId: Long) =
        characterQuoteDao.deleteAllByCharacter(characterId)

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

    suspend fun batchSetPinned(ids: List<Long>, isPinned: Boolean) {
        db.withTransaction {
            SqlInChunks.each(ids) { characterDao.setPinnedForIds(it, isPinned) }
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
        // 정리는 커밋 이후에 한 번 — 종전에는 스냅샷만 남기고 정리를 부르지 않아 다음 삭제 작업까지
        // 한도를 넘긴 채 쌓였다 (B-15). 인스턴스를 공유해야 이 작업의 백업이 보호된다 (R-3).
        // **편집 직전 백업**이다 — 캐릭터는 지워지지 않으므로 복원은 되돌리기가 아니라 복제다(B-2).
        val trash = TrashRepository(db, TrashSnapshot.KIND_EDIT_BACKUP)
        db.withTransaction {
            val newUniverseId = newNovelId?.let { db.novelDao().getNovelById(it)?.universeId }
            if (newUniverseId != null) {
                val allDefsById: Map<Long, FieldDefinition> = db.fieldDefinitionDao().getAllFieldsAllTypes().associateBy { it.id }
                val newFields = db.fieldDefinitionDao().getFieldsByUniverseList(newUniverseId)
                val newDefByKey = newFields.associateBy { it.key }
                SqlInChunks.each(ids) { chunk ->
                    for (character in characterDao.getCharactersByIds(chunk)) {
                        val curUniverse = character.novelId?.let { db.novelDao().getNovelById(it)?.universeId }
                        if (curUniverse == newUniverseId) continue // 같은 세계관 내 이동은 정리 불필요
                        agg += migrateCharacterFieldsToUniverse(character, newUniverseId, allDefsById, newDefByKey, newFields, trash)
                    }
                }
            }
            // novelId 갱신은 이관/스냅샷 '후' — 스냅샷이 옛 소속을 담도록
            SqlInChunks.each(ids) { characterDao.updateNovelIdForIds(it, newNovelId, now) }
        }
        trash.pruneIfNeeded()
        // 세계관이 바뀐 이동이면 대상 세계관 라이브러리에 재매핑 값 수확 (검토 A6)
        newNovelId?.let { db.novelDao().getNovelById(it)?.universeId }
            ?.let { fieldLibrary.harvestUniverses(setOf(it)) }
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
        /**
         * 새 세계관의 필드 **전부** — 전역 값의 이관 대상을 찾는 데 쓴다 (B-128).
         * [newDefByKey]로 대신할 수 없다: 그 맵은 `associateBy { it.key }`라 **종류를 가리지 않고
         * 키로 뭉개므로**, 같은 세계관에 키가 같은 사건 필드가 있으면 캐릭터 필드가 밀려 사라진다.
         * 그러면 짝이 있는데도 못 찾아 값이 조용히 보관 값으로 굳는다.
         */
        newFields: List<FieldDefinition>,
        trash: TrashRepository
    ): UniverseMoveCounts {
        val oldValues = characterFieldValueDao.getValuesByCharacterList(character.id)
        val finalValues = ArrayList<CharacterFieldValue>(oldValues.size)
        val usedDefIds = HashSet<Long>()
        var remapped = 0
        var removedValues = 0
        var keptGlobal = 0
        // 전역 구역의 값은 여기서도 확정 규칙을 따른다 (B-128) — 이 경로는 일괄 편집과
        // **엑셀 가져오기**가 쓴다. 아래 일반 규칙에 맡기면 짝이 없을 때 *제거*되는데,
        // 편집 폼 경로는 같은 값을 *보관*한다. **한 저장소 안에서 처분의 방향이 갈리면**
        // 사용자는 같은 조작을 어디서 했는지에 따라 값을 잃거나 잃지 않는다.
        val globalPlan = planGlobalScopeMove(
            oldValues, allDefsById, newFields, occupiedTargetFieldIds = emptySet()
        )
        for (v in oldValues) {
            val def = allDefsById[v.fieldDefinitionId]
            // 캐릭터 필드가 아닌 정의(사건 필드 등)를 가리키는 값은 세계관 이동의 대상이 아니다 —
            // 재매핑도 제거도 하지 않고 그대로 둔다. 캐릭터 필드 목록으로는 대응을 찾을 수 없어
            // 종전에는 '대응 필드 없음'으로 분류되어 제거됐다.
            if (def != null && def.entityType != FieldDefinition.ENTITY_CHARACTER) {
                if (usedDefIds.add(v.fieldDefinitionId)) finalValues.add(v)
                continue
            }
            if (def != null && def.universeId == null) {
                val target = globalPlan.transfers[def.id]
                if (target == null) {
                    // 보관 — 값은 그대로 살아 원래 정의를 가리킨다(유실이 아니다).
                    if (usedDefIds.add(v.fieldDefinitionId)) finalValues.add(v)
                    if (def.id in globalPlan.kept) keptGlobal++
                } else if (usedDefIds.add(target)) {
                    finalValues.add(v.copy(fieldDefinitionId = target)); remapped++
                }
                continue
            }
            val key = def?.key
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
            // 원본은 살아 있다 — **편집 직전 백업**이다. 종류를 인스턴스에 맡기면 엑셀 임포트가
            // 넘겨준 삭제용 인스턴스 때문에 'delete'로 각인되어, 복원이 되돌리기가 아니라
            // 복제가 된다(B-2). 파괴 범위도 함께 적어 되돌리기가 그만큼만 교체하게 한다.
            trash.snapshotCharacter(
                character,
                parseImagePaths(character.imagePaths).map { it.absolutePath },
                kind = TrashSnapshot.KIND_EDIT_BACKUP,
                revertScope = RestoreModes.SCOPE_UNIVERSE_MOVE
            )
        }
        characterFieldValueDao.replaceAllByCharacter(character.id, finalValues)
        if (orphanMemberships > 0) db.factionMembershipDao().deleteMembershipsNotInUniverse(character.id, newUniverseId)
        return UniverseMoveCounts(remapped, removedValues, orphanMemberships, if (willLose) 1 else 0, keptGlobal)
    }

    /**
     * 엑셀 가져오기 등 외부 경로에서 한 캐릭터를 [newUniverseId] 세계관으로 이관한다.
     * 편집화면의 세계관 이동과 동일한 P0 로직(같은 key 재매핑·타 세계관 세력 소속 제거·유실 시 스냅샷)을
     * 재사용해 정합을 보장한다. novelId 갱신은 호출부 책임(호출 전/후 무관 — 이 메서드는 필드값·소속만 정리).
     */
    /**
     * @param trash 스냅샷을 남길 저장소. **한 작업 안에서는 같은 인스턴스를 넘길 것** —
     *   보관 한도 정리는 "그 인스턴스가 만든 스냅샷"을 보호하므로, 캐릭터마다 새 인스턴스를
     *   쓰면 같은 작업의 정리가 방금 만든 백업을 그대로 태운다(엑셀 임포트가 정확히 그 경로였다).
     */
    suspend fun migrateCharacterToUniverse(
        character: Character,
        newUniverseId: Long,
        trash: TrashRepository? = null
    ): UniverseMoveCounts {
        // 호출부가 인스턴스를 넘겼다면 정리도 그쪽 책임이다(작업 범위를 그쪽이 안다).
        // 여기서 만든 경우에만 커밋 후 정리한다 (B-15). 편집 직전 백업이다(B-2).
        val ownedTrash = if (trash == null) TrashRepository(db, TrashSnapshot.KIND_EDIT_BACKUP) else null
        val counts = db.withTransaction {
            val trashRepo = trash ?: ownedTrash!!
            val allDefsById: Map<Long, FieldDefinition> = db.fieldDefinitionDao().getAllFieldsAllTypes().associateBy { it.id }
            val newFields = db.fieldDefinitionDao().getFieldsByUniverseList(newUniverseId)
            val newDefByKey = newFields.associateBy { it.key }
            migrateCharacterFieldsToUniverse(character, newUniverseId, allDefsById, newDefByKey, newFields, trashRepo)
        }
        ownedTrash?.pruneIfNeeded()
        // 재매핑된 값이 새 세계관 필드의 라이브러리에 등재되도록 수확
        fieldLibrary.harvestForCharacter(character.id)
        return counts
    }

    /**
     * 전역 구역의 값이 이 이동에서 어떻게 처분되는가 (B-128) — **세는 쪽과 적용하는 쪽의 단일 소스.**
     *
     * 둘이 갈리면 확인 다이얼로그가 *"N개가 제거됩니다"*라고 해 놓고 실제로는 보관하거나 그 반대가
     * 되는데, 사용자가 그 어긋남을 알아챌 길은 저장한 뒤 값을 하나씩 세어 보는 것뿐이다.
     *
     * 판정 자체는 [GlobalScopeFieldMove]가 든다(순수라 시험이 잠근다). 여기서는 *무엇을 물을지* —
     * 곧 **전역 구역의 캐릭터 필드값만** 골라 넘기는 일만 한다.
     */
    private fun planGlobalScopeMove(
        oldValues: List<CharacterFieldValue>,
        allDefsById: Map<Long, FieldDefinition>,
        targetFields: List<FieldDefinition>,
        occupiedTargetFieldIds: Set<Long>
    ): GlobalScopeFieldMove.Plan {
        val moving = oldValues.mapNotNull { v ->
            val def = allDefsById[v.fieldDefinitionId] ?: return@mapNotNull null
            if (def.universeId != null) return@mapNotNull null
            if (def.entityType != FieldDefinition.ENTITY_CHARACTER) return@mapNotNull null
            GlobalScopeFieldMove.Candidate(def, v.value)
        }
        return GlobalScopeFieldMove.plan(moving, targetFields, occupiedTargetFieldIds)
    }

    /** 세계관 이동 시 유실될 값·세력 소속 수를 미리 센다(편집화면 확인 다이얼로그·고지용, 파괴 없음). */
    suspend fun countCrossUniverseLoss(characterId: Long, newUniverseId: Long): UniverseMoveCounts {
        val old = characterFieldValueDao.getValuesByCharacterList(characterId)
        val allDefsById: Map<Long, FieldDefinition> = db.fieldDefinitionDao().getAllFieldsAllTypes().associateBy { it.id }
        val newFields = db.fieldDefinitionDao().getFieldsByUniverseList(newUniverseId)
        val newKeys = newFields.map { it.key }.toSet()
        // 전역 구역의 값은 **제거 대상이 아니다** (B-128) — 옮기거나 보관한다. 적용 쪽과 같은
        // 판정을 써서 세지 않으면 이 수가 다이얼로그에서 그대로 거짓말이 된다.
        val globalPlan = planGlobalScopeMove(old, allDefsById, newFields, occupiedTargetFieldIds = emptySet())
        var removed = 0
        var remappable = 0
        for (v in old) {
            val def = allDefsById[v.fieldDefinitionId]
            // 캐릭터 필드가 아닌 정의를 가리키는 값은 이동 대상이 아니다 — 세지 않는다
            // (이 집계가 확인 다이얼로그의 '제거됩니다' 문구를 만든다: 사실과 달라선 안 된다)
            if (def != null && def.entityType != FieldDefinition.ENTITY_CHARACTER) continue
            if (def != null && def.universeId == null) {
                if (globalPlan.transfers.containsKey(def.id)) remappable++
                continue
            }
            val key = def?.key
            if (key == null || key !in newKeys) removed++ else remappable++
        }
        val memberships = db.factionMembershipDao().countMembershipsNotInUniverse(characterId, newUniverseId)
        return UniverseMoveCounts(remappable, removed, memberships, 0, globalPlan.kept.size)
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
        // 정리는 커밋 이후에 — 종전에는 스냅샷만 남기고 pruneIfNeeded를 부르지 않았다 (B-15).
        // 편집 직전 백업이다 — 캐릭터는 지워지지 않는다(B-2).
        val trash = TrashRepository(db, TrashSnapshot.KIND_EDIT_BACKUP)
        return db.withTransaction {
            val allDefsById: Map<Long, FieldDefinition> = db.fieldDefinitionDao().getAllFieldsAllTypes().associateBy { it.id }
            val newFields = db.fieldDefinitionDao().getFieldsByUniverseList(newUniverseId)
            val newDefByKey = newFields.associateBy { it.key }
            val old = characterFieldValueDao.getValuesByCharacterList(character.id)
            val formNonBlank = formValues.filter { it.value.isNotBlank() }
            val formDefIds = formNonBlank.map { it.fieldDefinitionId }.toHashSet()

            // 전역 구역의 값은 확정 규칙으로 따로 처분한다 (B-128) — 타입이 호환되고 대상이
            // 비어 있을 때만 옮기고, 아니면 **보관 값으로 남긴다.** 아래 일반 경로에 맡기면
            // 타입을 보지 않고 밀어 넣거나(오염), 짝이 없다고 제거해 버린다(유실).
            //
            // '대상이 비었는가'에는 폼이 방금 채운 것까지 넣는다 — 사용자가 지금 적은 값이
            // 옛 값에 덮이면 그것이 가장 나쁜 덮어쓰기다.
            val globalPlan = planGlobalScopeMove(old, allDefsById, newFields, formDefIds)

            val gapFills = LinkedHashMap<Long, CharacterFieldValue>() // 새 defId -> 이관값(폼 미입력분)
            var remapped = 0
            var removed = 0
            var keptGlobal = 0
            for (v in old) {
                if (v.value.isBlank()) continue
                val def = allDefsById[v.fieldDefinitionId]
                // 캐릭터 필드가 아닌 정의(사건 필드 등)는 세계관 이동의 대상이 아니다 —
                // 재매핑도 제거도 하지 않고 그대로 보존한다(폼도 이 값을 렌더하지 않는다).
                if (def != null && def.entityType != FieldDefinition.ENTITY_CHARACTER) {
                    if (v.fieldDefinitionId !in formDefIds) gapFills.getOrPut(v.fieldDefinitionId) { v }
                    continue
                }
                if (def != null && def.universeId == null) {
                    val target = globalPlan.transfers[def.id]
                    if (target == null) {
                        // 보관 — 값은 그대로 살아 원래 정의를 가리킨다(유실이 아니다).
                        gapFills.getOrPut(v.fieldDefinitionId) { v }
                        if (def.id in globalPlan.kept) keptGlobal++
                    } else if (!gapFills.containsKey(target)) {
                        gapFills[target] = v.copy(fieldDefinitionId = target)
                        remapped++
                    }
                    continue
                }
                val key = def?.key
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
                // 편집 직전 백업 — 이 경로는 캐릭터 행까지 덮어쓰므로 되돌리기 범위에 포함한다.
                trash.snapshotCharacter(
                    persisted,
                    parseImagePaths(persisted.imagePaths).map { it.absolutePath },
                    kind = TrashSnapshot.KIND_EDIT_BACKUP,
                    revertScope = RestoreModes.SCOPE_UNIVERSE_MOVE_WITH_ROW
                )
            }
            characterDao.update(character)
            characterFieldValueDao.replaceAllByCharacter(character.id, formNonBlank + gapFills.values)
            if (orphanMemberships > 0) db.factionMembershipDao().deleteMembershipsNotInUniverse(character.id, newUniverseId)
            UniverseMoveCounts(remapped, removed, orphanMemberships, if (willLose) 1 else 0, keptGlobal)
        }.also {
            trash.pruneIfNeeded()
            // 세계관 간 이동 저장도 폼 값 저장 경로 — 새 세계관 필드로 수확 (검토 A6)
            fieldLibrary.harvestForCharacter(character.id)
        }
    }

    suspend fun batchAddTags(ids: List<Long>, tags: List<String>) {
        if (tags.isEmpty()) return
        db.withTransaction {
            SqlInChunks.each(ids) { chunk ->
                val tagEntities = chunk.flatMap { charId ->
                    tags.map { tag -> CharacterTag(characterId = charId, tag = tag) }
                }
                characterTagDao.insertAll(tagEntities) // IGNORE 전략으로 중복 무시
            }
        }
    }

    suspend fun batchRemoveTags(ids: List<Long>, tags: List<String>) {
        if (tags.isEmpty()) return
        // deleteTagsFromCharacters는 이중 IN 절(characterIds + tags) 사용 —
        // 태그 몫을 밝혀 넘긴다(그 예산 계산은 통로가 [SqlInChunks.sizeFor]로 안에서 한다).
        db.withTransaction {
            SqlInChunks.each(ids, reservedBinds = tags.size) {
                characterTagDao.deleteTagsFromCharacters(it, tags)
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
        fieldLibrary.harvestField(fieldDefId)
    }

    suspend fun batchClearFieldValue(ids: List<Long>, fieldDefId: Long) {
        db.withTransaction {
            SqlInChunks.each(ids) { db.characterFieldValueDao().deleteFieldValueForCharacters(it, fieldDefId) }
        }
    }

    suspend fun batchAppendMemo(ids: List<Long>, text: String, prepend: Boolean) {
        db.withTransaction {
            SqlInChunks.each(ids) { chunk ->
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
            deleteCharactersCascade(db, trash, ids)
        }

        // 이미지 파일은 스냅샷과 함께 유지 — 휴지통 정리 시점에 삭제 (B-7)
        trash.pruneIfNeeded()
    }

    /**
     * 일괄 삭제 시 함께 정리(FK CASCADE)될 연관 데이터 요약.
     * 캐릭터는 휴지통 스냅샷으로 복원되지만, 사용자가 삭제 범위를 확인 후 결정할 수 있도록
     * 관계·상태변화·세력소속·사건연계 규모를 사전 고지한다(조작 마찰 최소화 + 변수 제어).
     */
    data class DeleteImpact(
        val characters: Int,
        val relationships: Int,
        val stateChanges: Int,
        val quotes: Int,
        val factionMemberships: Int,
        val eventLinks: Int
    ) {
        /** 캐릭터 외 함께 정리될 연관 데이터가 있는지 — 요약 문구 노출 여부 판단용. */
        val hasLinkedData: Boolean
            get() = relationships > 0 || stateChanges > 0 || quotes > 0 ||
                factionMemberships > 0 || eventLinks > 0
    }

    /**
     * 일괄 삭제 전 연쇄 영향 규모를 집계한다. IN 절 변수 한도는 [SqlInChunks]가 지킨다(받쳐주는 확장성).
     *
     * **계수 넷을 한 번에 도는 자리라 [SqlInChunks.sum]이 아니라 [SqlInChunks.each]다** — 질의마다
     * `sum`을 걸면 같은 목록을 네 번 나눠 돈다. 조각마다 넷을 함께 묻고 `+=`로 더하는 것이 R-54가
     * 말하는 그 합산이며, 나누기가 계수를 바꾸지 않는다는 성질은 여기서도 같다.
     */
    suspend fun getBatchDeleteImpact(ids: List<Long>): DeleteImpact {
        if (ids.isEmpty()) return DeleteImpact(0, 0, 0, 0, 0, 0)
        val relIds = mutableSetOf<Long>()  // 관계는 두 끝이 서로 다른 청크에 나뉠 수 있어 id Set으로 교차청크 중복 제거
        var stateChanges = 0
        var quotes = 0
        var memberships = 0
        var eventLinks = 0
        SqlInChunks.each(ids) { chunk ->
            relIds.addAll(characterRelationshipDao.getRelationshipIdsForCharacters(chunk))
            stateChanges += characterStateChangeDao.countByCharacterIds(chunk)
            quotes += characterQuoteDao.countByCharacterIds(chunk)
            memberships += db.factionMembershipDao().countByCharacterIds(chunk)
            eventLinks += db.timelineDao().countEventLinksForCharacters(chunk)
        }
        return DeleteImpact(ids.size, relIds.size, stateChanges, quotes, memberships, eventLinks)
    }

    /**
     * 선택 캐릭터의 고유 태그 목록 (일괄 삭제 UI용).
     *
     * `DISTINCT`는 조각 안에서만 성립하므로(R-54) **접고 정렬하는 일이 호출부에 있다** — Set으로
     * 받아 `sorted()`로 낸다. 통로를 지나도 그 책임은 그대로 여기다.
     */
    suspend fun getDistinctTagsForCharacters(ids: List<Long>): List<String> {
        val allTags = mutableSetOf<String>()
        SqlInChunks.each(ids) { allTags.addAll(characterTagDao.getDistinctTagsForCharacters(it)) }
        return allTags.sorted()
    }

    companion object {
        /**
         * 캐릭터 일괄 삭제 공통 본체 — 삭제 전 캐릭터별 휴지통 스냅샷을 남기고
         * nameBank 사용·최근활동·작품/세계관 이미지 참조를 정리한 뒤 삭제한다(FK CASCADE가 나머지 정리).
         * 작품/세계관 계단식 삭제(NovelRepository/UniverseRepository)에서도 재사용한다.
         *
         * 반드시 db.withTransaction 안에서 호출해야 하며, 커밋 후 trash.pruneIfNeeded() 호출은
         * 호출측 책임이다(트랜잭션 롤백 시 스냅샷 이미지 파일이 지워지는 것 방지).
         */
        suspend fun deleteCharactersCascade(db: AppDatabase, trash: TrashRepository, ids: List<Long>) {
            if (ids.isEmpty()) return
            // **전 청크를 먼저 스냅샷한 뒤에 삭제한다.** 청크마다 스냅샷→삭제를 반복하면,
            // 앞 청크를 지울 때 FK CASCADE가 관계 행을 없애 버려 청크를 가로지르는 관계가
            // 뒤 청크의 payload에서 사라진다 — 관계는 양쪽 스냅샷이 모두 담는다는 전제가
            // 깨지고, 복원은 "상대가 같은 작업 안에 있으니 그쪽이 담았겠지"라며 조용히
            // 건너뛴다(무통보 유실). 900명을 넘는 세계관에서만 나타나던 경로다.
            SqlInChunks.each(ids) { chunk ->
                for (character in db.characterDao().getCharactersByIds(chunk)) {
                    trash.snapshotCharacter(character, parseImagePathStrings(character.imagePaths))
                }
            }
            SqlInChunks.each(ids) { chunk ->
                db.nameBankDao().resetUsageByCharacterIds(chunk)
                db.recentActivityDao().deleteByEntityIds(RecentActivity.TYPE_CHARACTER, chunk)
                db.novelDao().clearImageCharacterRefs(chunk)
                db.universeDao().clearImageCharacterRefs(chunk)
                db.characterDao().deleteByIds(chunk) // FK CASCADE가 나머지 정리
            }
        }

        private fun parseImagePathStrings(imagePathsJson: String): List<String> = try {
            val raw: List<String?>? = Gson().fromJson(imagePathsJson, GsonTypes.STRING_LIST)
            raw?.filterNotNull() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
