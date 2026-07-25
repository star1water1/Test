package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.CharacterSnapshot
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FieldDefNaturalKey
import com.novelcharacter.app.data.model.FieldDefRef
import com.novelcharacter.app.data.model.SnapshotRefs
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.model.generateEntityCode
import com.novelcharacter.app.util.GsonTypes
import java.io.File

/**
 * 휴지통 (B-7). 삭제 시점 스냅샷 보관 → 목록/복원/영구 삭제.
 *
 * 소프트 삭제(deletedAt 컬럼) 대신 스냅샷 방식을 채택 — 기존 조회 쿼리 20여 곳에
 * 필터를 심을 필요가 없고, FK CASCADE로 함께 사라지는 연관 데이터까지 복원 가능하다.
 * 이미지 파일은 스냅샷이 살아 있는 동안 유지되고 영구 삭제 시 함께 지워진다.
 *
 * ## 참조 정체성 (N1)
 * payload는 연관 데이터의 참조를 DB id로 담되, **안정 식별자(코드/자연키)를 함께**
 * 담는다([SnapshotRefs]). 복원은 [SnapshotRefResolver]의 사다리로 대상을 다시 찾으므로
 * 덮어쓰기 임포트·세계관 재생성으로 id가 재발급돼도 같은 대상에 다시 붙는다.
 * 구버전 payload(refs 없음)는 종전대로 id 단독 해석으로 폴백한다.
 */
class TrashRepository(private val db: AppDatabase) {

    private val trashDao = db.trashSnapshotDao()
    private val gson = Gson()

    /**
     * 이 인스턴스가 만든 스냅샷 id — 보관 한도 정리에서 **보호**한다.
     *
     * 세계관/작품 삭제나 일괄 삭제는 한 번에 수십~수백 건을 스냅샷하는데, 종전 정리는
     * `count() - MAX_ITEMS`만큼을 오래된 순으로 지워 **방금 만든 백업과 그 이미지 파일까지**
     * 태웠다(그러면서 UI는 "휴지통에서 복구할 수 있습니다"라고 약속했다).
     * 4-6 규약대로, 보관 한도가 방금 만든 백업을 파괴해서는 안 된다.
     */
    private val createdSnapshotIds = mutableSetOf<Long>()

    // 스냅샷 생성 시 반복 조회되는 코드 — 인스턴스 수명 동안 캐시한다(일괄 삭제 N+1 완화).
    private val universeCodeCache = HashMap<Long, String?>()
    private val fieldDefRefCache = HashMap<Long, FieldDefRef?>()

    val allSnapshots: LiveData<List<TrashSnapshot>> = trashDao.getAll()

    /** 복원 결과 — 어떤 연관 데이터가 참조 소실로 생략됐는지 사용자에게 알리기 위한 집계 */
    data class RestoreResult(
        val restoredName: String,
        val skippedFieldValues: Int = 0,
        val skippedRelationships: Int = 0,
        /** 같은 관계가 이미 있어 중복 생성하지 않은 건수 — 유실이 아니므로 별도 칸이다 */
        val duplicateRelationships: Int = 0,
        val skippedRelationshipChanges: Int = 0,
        val skippedMemberships: Int = 0,
        val skippedEvents: Int = 0,
        /** 관계는 살아났지만 '세력' 지정만 소실된 건수 (종전에는 집계 없이 조용히 null이 됐다) */
        val clearedRelationshipFactions: Int = 0,
        /** 관계 변화는 살아났지만 연결 사건만 소실된 건수 (동상) */
        val clearedChangeEvents: Int = 0,
        val novelCleared: Boolean = false,
        /** id가 재발급됐지만 코드/자연키로 다시 찾아 되살린 참조 건수 (N1 수정의 실효) */
        val relinkedByCode: Int = 0
    ) {
        val hasSkipped: Boolean
            get() = skippedFieldValues > 0 || skippedRelationships > 0 ||
                skippedRelationshipChanges > 0 || skippedMemberships > 0 ||
                skippedEvents > 0 || clearedRelationshipFactions > 0 ||
                clearedChangeEvents > 0 || novelCleared
    }

    /**
     * 복원 **전** 미리보기 — 무엇이 되살아나고 무엇이 안 되는지 먼저 알린다.
     *
     * 복원은 성공 시 스냅샷을 소각하므로, 되살릴 수 없는 부분이 있는데 그대로 진행하면
     * payload에만 남아 있던 원본이 그 순간 영구 소멸한다. 그래서 "검증 → 알림 → 교정 경로"의
     * 마지막 단계를 여기서 만든다 — 사용자가 취소하면 스냅샷은 손대지 않으므로,
     * 세계관/필드 정의를 먼저 복구한 뒤 다시 복원할 수 있다.
     */
    data class RestorePreview(
        val characterName: String,
        val skippedFieldValues: Int = 0,
        val skippedRelationships: Int = 0,
        val skippedRelationshipChanges: Int = 0,
        val skippedMemberships: Int = 0,
        val skippedEvents: Int = 0,
        val clearedRelationshipFactions: Int = 0,
        val clearedChangeEvents: Int = 0,
        val novelCleared: Boolean = false,
        val relinkedByCode: Int = 0,
        /**
         * 같은 코드의 캐릭터가 아직 살아 있다 — 이 스냅샷은 '삭제 백업'이 아니라
         * '파괴적 편집 직전 백업'(세계관 이동 등)이며, 복원하면 되돌리기가 아니라
         * **같은 이름의 캐릭터가 하나 더 생긴다**. 사실대로 먼저 알린다.
         */
        val duplicatesLivingCharacter: Boolean = false,
        /** 구버전 payload라 안정 식별자가 없어 id 단독으로 판단한 참조가 있다 */
        val legacyPayload: Boolean = false
    ) {
        val hasSkipped: Boolean
            get() = skippedFieldValues > 0 || skippedRelationships > 0 ||
                skippedRelationshipChanges > 0 || skippedMemberships > 0 ||
                skippedEvents > 0 || clearedRelationshipFactions > 0 ||
                clearedChangeEvents > 0 || novelCleared

        val needsConfirmation: Boolean
            get() = hasSkipped || duplicatesLivingCharacter
    }

    // ──────────────────────────────────────────────────────────────────────
    // 스냅샷 생성
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 캐릭터+연관 데이터를 스냅샷으로 보관한다.
     * 반드시 삭제 트랜잭션 내에서, 실제 삭제 전에 호출할 것.
     */
    suspend fun snapshotCharacter(character: Character, imagePaths: List<String>) {
        val relationships = db.characterRelationshipDao().getRelationshipsForCharacterList(character.id)
        val relationshipChanges = relationships.flatMap { rel ->
            db.characterRelationshipChangeDao().getChangesForRelationshipList(rel.id)
        }
        val fieldValues = db.characterFieldValueDao().getValuesByCharacterList(character.id)
        val factionMemberships = db.factionMembershipDao().getMembershipsByCharacterList(character.id)
        val eventIds = db.timelineDao().getEventIdsForCharacter(character.id)

        val snapshot = CharacterSnapshot(
            character = character,
            fieldValues = fieldValues,
            stateChanges = db.characterStateChangeDao().getChangesByCharacterList(character.id),
            tags = db.characterTagDao().getTagsByCharacterList(character.id),
            relationships = relationships,
            relationshipChanges = relationshipChanges,
            factionMemberships = factionMemberships,
            eventIds = eventIds,
            refs = buildRefs(character, fieldValues, relationships, relationshipChanges, factionMemberships, eventIds)
        )
        val newId = trashDao.insert(
            TrashSnapshot(
                entityType = TrashSnapshot.TYPE_CHARACTER,
                entityName = character.name,
                payload = gson.toJson(snapshot),
                imagePaths = gson.toJson(imagePaths)
            )
        )
        if (newId > 0) createdSnapshotIds.add(newId)
    }

    /** payload가 담은 모든 DB id에 대한 안정 식별자를 수집한다 (N1). */
    private suspend fun buildRefs(
        character: Character,
        fieldValues: List<CharacterFieldValue>,
        relationships: List<CharacterRelationship>,
        relationshipChanges: List<CharacterRelationshipChange>,
        factionMemberships: List<FactionMembership>,
        eventIds: List<Long>
    ): SnapshotRefs {
        val novel = character.novelId?.let { db.novelDao().getNovelById(it) }

        val fieldDefs = HashMap<String, FieldDefRef>()
        for (id in fieldValues.map { it.fieldDefinitionId }.distinct()) {
            fieldDefRef(id)?.let { fieldDefs[id.toString()] = it }
        }

        val characterCodes = HashMap<String, String>()
        val otherIds = relationships
            .flatMap { listOf(it.characterId1, it.characterId2) }
            .filter { it != character.id }
            .distinct()
        for (id in otherIds) {
            db.characterDao().getCharacterById(id)?.code
                ?.takeIf { it.isNotBlank() }
                ?.let { characterCodes[id.toString()] = it }
        }

        val factionCodes = HashMap<String, String>()
        val factionIds = (relationships.mapNotNull { it.factionId } + factionMemberships.map { it.factionId })
            .distinct()
        for (id in factionIds) {
            db.factionDao().getById(id)?.code
                ?.takeIf { it.isNotBlank() }
                ?.let { factionCodes[id.toString()] = it }
        }

        val eventCodes = HashMap<String, String>()
        val allEventIds = (eventIds + relationshipChanges.mapNotNull { it.eventId }).distinct()
        for (id in allEventIds) {
            db.timelineDao().getEventById(id)?.code
                ?.takeIf { it.isNotBlank() }
                ?.let { eventCodes[id.toString()] = it }
        }

        return SnapshotRefs(
            version = SnapshotRefs.VERSION,
            novelCode = novel?.code?.takeIf { it.isNotBlank() },
            universeCode = novel?.universeId?.let { universeCode(it) },
            fieldDefs = fieldDefs,
            characters = characterCodes,
            factions = factionCodes,
            events = eventCodes
        )
    }

    private suspend fun universeCode(universeId: Long): String? =
        universeCodeCache.getOrPut(universeId) {
            db.universeDao().getUniverseById(universeId)?.code?.takeIf { it.isNotBlank() }
        }

    private suspend fun fieldDefRef(fieldDefinitionId: Long): FieldDefRef? {
        if (fieldDefRefCache.containsKey(fieldDefinitionId)) return fieldDefRefCache[fieldDefinitionId]
        val fd = db.fieldDefinitionDao().getFieldById(fieldDefinitionId)
        val ref = fd?.let {
            val uCode = universeCode(it.universeId) ?: return@let null
            FieldDefRef(universeCode = uCode, entityType = it.entityType, key = it.key)
        }
        fieldDefRefCache[fieldDefinitionId] = ref
        return ref
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원
    // ──────────────────────────────────────────────────────────────────────

    /** 복원 계획 — 미리보기와 실제 복원이 **같은 해석 결과**를 쓰도록 한 곳에서 만든다. */
    private data class RestorePlan(
        val data: CharacterSnapshot,
        val novelId: Long?,
        val novelCleared: Boolean,
        /** 해석된 fieldDefinitionId로 치환된 필드값 */
        val fieldValues: List<CharacterFieldValue>,
        val skippedFieldValues: Int,
        val relationships: List<PlannedRelationship>,
        val skippedRelationships: Int,
        /** 관계가 통째로 생략되면서 함께 버려진 관계 변화 이력 */
        val skippedRelationshipChanges: Int,
        val clearedRelationshipFactions: Int,
        val clearedChangeEvents: Int,
        val memberships: List<FactionMembership>,
        val skippedMemberships: Int,
        val eventIds: List<Long>,
        val skippedEvents: Int,
        val relinkedByCode: Int,
        val legacyPayload: Boolean,
        val duplicatesLivingCharacter: Boolean
    )

    /**
     * 상대 캐릭터까지 해석이 끝난 관계.
     *
     * 자기 자신이 어느 슬롯인지를 **id 비교가 아니라 플래그로** 들고 다닌다 —
     * 옛 id가 다른 엔티티에 재사용된 상황에서 `characterId == data.character.id` 비교는
     * 상대 캐릭터를 복원 대상으로 오인할 수 있다(이 클래스가 막으려는 바로 그 오배정).
     */
    private data class PlannedRelationship(
        val relationship: CharacterRelationship,
        val selfIsFirst: Boolean,
        val selfIsSecond: Boolean,
        val changes: List<CharacterRelationshipChange>
    )

    /**
     * 복원 전 미리보기. 스냅샷을 전혀 건드리지 않는다.
     * 되살릴 수 없는 부분이 있으면 호출측이 사용자에게 알리고 진행 여부를 묻는다.
     */
    suspend fun previewRestore(snapshotId: Long): RestorePreview? {
        val plan = buildPlan(snapshotId) ?: return null
        return RestorePreview(
            characterName = plan.data.character.name,
            skippedFieldValues = plan.skippedFieldValues,
            skippedRelationships = plan.skippedRelationships,
            skippedRelationshipChanges = plan.skippedRelationshipChanges,
            skippedMemberships = plan.skippedMemberships,
            skippedEvents = plan.skippedEvents,
            clearedRelationshipFactions = plan.clearedRelationshipFactions,
            clearedChangeEvents = plan.clearedChangeEvents,
            novelCleared = plan.novelCleared,
            relinkedByCode = plan.relinkedByCode,
            duplicatesLivingCharacter = plan.duplicatesLivingCharacter,
            legacyPayload = plan.legacyPayload
        )
    }

    /**
     * 스냅샷에서 캐릭터를 복원한다.
     * 원본 id 자리가 비어 있으면 같은 id로, 차 있으면 새 id로 복원하며
     * 그 사이 삭제된 참조(필드 정의/상대 캐릭터/세력/사건)는 건너뛰고 집계해 알린다.
     */
    suspend fun restoreCharacter(snapshotId: Long): RestoreResult? {
        var result: RestoreResult? = null
        var restoredCharacterId = -1L

        db.withTransaction {
            // 트랜잭션 안에서 다시 해석한다 — 미리보기 이후 DB가 바뀌었을 수 있다.
            val plan = buildPlan(snapshotId) ?: return@withTransaction
            val data = plan.data

            var character = data.character.copy(novelId = plan.novelId)
            // 코드 충돌 시 재발급 (code unique)
            if (db.characterDao().getCharacterByCode(character.code) != null) {
                character = character.copy(code = generateEntityCode())
            }

            // 원본 id가 비어 있으면 유지, 차 있으면 새 id
            val targetId: Long = if (db.characterDao().getCharacterById(character.id) == null) {
                db.characterDao().insert(character)
                character.id
            } else {
                db.characterDao().insert(character.copy(id = 0))
            }
            restoredCharacterId = targetId

            db.characterFieldValueDao().insertAll(
                plan.fieldValues.map { it.copy(id = 0, characterId = targetId) }
            )

            // 상태변화·태그 — 캐릭터 외 참조 없음
            // code 충돌 시 재발급 (캐릭터 code와 동일 규칙): 복원 전 엑셀 임포트로 같은 코드가
            // 재생성된 경우 유니크 위반으로 복원 전체가 실패하는 것을 방지. 레거시(null) 코드는 신규 발급.
            db.characterStateChangeDao().insertAll(
                data.stateChanges.map { change ->
                    val safeCode = change.code
                        ?.takeIf { c -> db.characterStateChangeDao().getChangeByCode(c) == null }
                        ?: generateEntityCode()
                    change.copy(id = 0, characterId = targetId, code = safeCode)
                }
            )
            db.characterTagDao().insertAll(
                data.tags.map { it.copy(id = 0, characterId = targetId) }
            )

            var duplicateRelationships = 0
            var duplicateChanges = 0
            for (planned in plan.relationships) {
                val rel = planned.relationship
                val remapped = rel.copy(
                    id = 0,
                    characterId1 = if (planned.selfIsFirst) targetId else rel.characterId1,
                    characterId2 = if (planned.selfIsSecond) targetId else rel.characterId2
                )
                val newRelId = db.characterRelationshipDao().insert(remapped)
                if (newRelId == -1L) {
                    // 동일 (상대, 유형) 관계가 이미 존재 — 중복 생성하지 않는다.
                    // 상대 캐릭터를 먼저 복원했을 때 정상적으로 일어나는 일이므로 '참조 소실'과
                    // 구분해 집계한다(같은 칸에 넣으면 사실과 다른 경고가 된다).
                    duplicateRelationships++
                    duplicateChanges += planned.changes.size
                    continue
                }
                val changes = planned.changes.map { change ->
                    // code 충돌 시 재발급 — 상태변화 복원과 동일 규칙
                    val safeCode = change.code
                        ?.takeIf { c -> db.characterRelationshipChangeDao().getChangeByCode(c) == null }
                        ?: generateEntityCode()
                    change.copy(id = 0, relationshipId = newRelId, code = safeCode)
                }
                if (changes.isNotEmpty()) {
                    db.characterRelationshipChangeDao().insertAll(changes)
                }
            }

            for (membership in plan.memberships) {
                db.factionMembershipDao().insert(membership.copy(id = 0, characterId = targetId))
            }

            for (eventId in plan.eventIds) {
                db.timelineDao().insertCrossRef(TimelineCharacterCrossRef(eventId, targetId))
            }

            result = RestoreResult(
                restoredName = data.character.name,
                skippedFieldValues = plan.skippedFieldValues,
                skippedRelationships = plan.skippedRelationships,
                duplicateRelationships = duplicateRelationships,
                skippedRelationshipChanges = plan.skippedRelationshipChanges + duplicateChanges,
                skippedMemberships = plan.skippedMemberships,
                skippedEvents = plan.skippedEvents,
                clearedRelationshipFactions = plan.clearedRelationshipFactions,
                clearedChangeEvents = plan.clearedChangeEvents,
                novelCleared = plan.novelCleared,
                relinkedByCode = plan.relinkedByCode
            )
        }

        val restored = result ?: return null

        // 복원 완료 — 스냅샷 제거 (이미지 파일은 복원된 캐릭터가 소유)
        trashDao.deleteById(snapshotId)
        createdSnapshotIds.remove(snapshotId)

        // 복원도 필드값 쓰기 경로 — 라이브러리에서 정리된 뒤 복원된 값이 다시 보이게 수확 (검토 A6)
        if (restoredCharacterId != -1L) {
            FieldValueLibraryRepository(db).harvestForCharacter(restoredCharacterId)
        }

        return restored
    }

    /**
     * payload의 참조를 현행 DB에서 다시 찾아 복원 계획을 만든다.
     * 해석은 [SnapshotRefResolver]가, 조회는 여기가 담당한다 — 사다리 자체는 순수 로직이라
     * Android 없이 단위 테스트로 고정된다.
     */
    private suspend fun buildPlan(snapshotId: Long): RestorePlan? {
        val snap = trashDao.getById(snapshotId) ?: return null
        if (snap.entityType != TrashSnapshot.TYPE_CHARACTER) return null
        val data = try {
            gson.fromJson(snap.payload, CharacterSnapshot::class.java)
        } catch (_: Exception) {
            null
        } ?: return null
        @Suppress("SENSELESS_COMPARISON")
        if (data.character == null) return null   // Gson은 손상된 payload에 null을 주입할 수 있다

        val refs = data.refs
        val legacy = refs == null
        var relinked = 0
        var legacyGuess = legacy

        fun note(res: SnapshotRefResolver.Resolution) {
            if (res.origin == SnapshotRefResolver.Origin.CODE) relinked++
            if (res.isLegacyGuess) legacyGuess = true
        }

        // ── 작품 ──
        val novelIndex = buildIndex(
            oldIds = listOfNotNull(data.character.novelId),
            codes = listOfNotNull(refs?.novelCode),
            fetchById = { db.novelDao().getNovelById(it) },
            fetchByCode = { db.novelDao().getNovelByCode(it) },
            idOf = { it.id },
            codeOf = { it.code }
        )
        var novelCleared = false
        val novelId = data.character.novelId?.let { old ->
            val res = SnapshotRefResolver.resolveByCode(
                old, refs?.novelCode, novelIndex.codeById, novelIndex.idByCode, novelIndex.liveIds
            )
            note(res)
            if (!res.found) novelCleared = true
            res.id
        }

        // ── 필드 정의 (code가 없어 자연키로 해석) ──
        val fieldRefs = refs?.fieldDefs.orEmpty()
        val fieldIndex = buildFieldDefIndex(
            oldIds = data.fieldValues.map { it.fieldDefinitionId }.distinct(),
            refs = fieldRefs.values
        )
        val resolvedFieldValues = ArrayList<CharacterFieldValue>(data.fieldValues.size)
        val usedFieldDefIds = HashSet<Long>()
        var skippedFieldValues = 0
        for (v in data.fieldValues) {
            val res = SnapshotRefResolver.resolveFieldDef(
                v.fieldDefinitionId,
                fieldRefs[v.fieldDefinitionId.toString()],
                fieldIndex.naturalById,
                fieldIndex.idByNatural
            )
            note(res)
            val newId = res.id
            // (characterId, fieldDefinitionId) 유니크 — 서로 다른 옛 id가 같은 정의로 수렴하면
            // insertAll(ABORT)이 복원 전체를 실패시키므로 중복은 여기서 걸러 집계한다.
            if (newId == null || !usedFieldDefIds.add(newId)) {
                skippedFieldValues++
                continue
            }
            resolvedFieldValues.add(v.copy(fieldDefinitionId = newId))
        }

        // ── 관계 상대 캐릭터 ──
        val otherIds = data.relationships
            .flatMap { listOf(it.characterId1, it.characterId2) }
            .filter { it != data.character.id }
            .distinct()
        val charIndex = buildIndex(
            oldIds = otherIds,
            codes = refs?.characters?.values.orEmpty(),
            fetchById = { db.characterDao().getCharacterById(it) },
            fetchByCode = { db.characterDao().getCharacterByCode(it) },
            idOf = { it.id },
            codeOf = { it.code }
        )

        // ── 세력 (관계의 세력 열 + 세력 소속) ──
        val factionIds = (data.relationships.mapNotNull { it.factionId } +
            data.factionMemberships.map { it.factionId }).distinct()
        val factionIndex = buildIndex(
            oldIds = factionIds,
            codes = refs?.factions?.values.orEmpty(),
            fetchById = { db.factionDao().getById(it) },
            fetchByCode = { db.factionDao().getByCode(it) },
            idOf = { it.id },
            codeOf = { it.code }
        )

        // ── 사건 (참가 사건 + 관계 변화 연결) ──
        val eventIdsAll = (data.eventIds + data.relationshipChanges.mapNotNull { it.eventId }).distinct()
        val eventIndex = buildIndex(
            oldIds = eventIdsAll,
            codes = refs?.events?.values.orEmpty(),
            fetchById = { db.timelineDao().getEventById(it) },
            fetchByCode = { db.timelineDao().getEventByCode(it) },
            idOf = { it.id },
            codeOf = { it.code }
        )

        fun resolveCharacter(oldId: Long) = SnapshotRefResolver.resolveByCode(
            oldId, refs?.characters?.get(oldId.toString()),
            charIndex.codeById, charIndex.idByCode, charIndex.liveIds
        )

        fun resolveFaction(oldId: Long) = SnapshotRefResolver.resolveByCode(
            oldId, refs?.factions?.get(oldId.toString()),
            factionIndex.codeById, factionIndex.idByCode, factionIndex.liveIds
        )

        fun resolveEvent(oldId: Long) = SnapshotRefResolver.resolveByCode(
            oldId, refs?.events?.get(oldId.toString()),
            eventIndex.codeById, eventIndex.idByCode, eventIndex.liveIds
        )

        val plannedRelationships = ArrayList<PlannedRelationship>(data.relationships.size)
        var skippedRelationships = 0
        var skippedRelationshipChanges = 0
        var clearedRelationshipFactions = 0
        var clearedChangeEvents = 0
        for (rel in data.relationships) {
            val changes = data.relationshipChanges.filter { it.relationshipId == rel.id }
            val selfIsFirst = rel.characterId1 == data.character.id
            val selfIsSecond = rel.characterId2 == data.character.id
            if (!selfIsFirst && !selfIsSecond) {
                // 어느 슬롯도 이 캐릭터가 아니다 — payload가 손상됐거나 남의 관계가 섞였다.
                // 복원하면 무관한 두 캐릭터를 잇게 되므로 생략하고 집계한다.
                skippedRelationships++
                skippedRelationshipChanges += changes.size
                continue
            }
            // 자기 자신과의 관계(양쪽 모두 자기)면 상대 해석이 필요 없다.
            var otherNew: Long? = null
            if (!(selfIsFirst && selfIsSecond)) {
                val otherOld = if (selfIsFirst) rel.characterId2 else rel.characterId1
                val res = resolveCharacter(otherOld)
                note(res)
                if (res.id == null) {
                    skippedRelationships++
                    // 관계가 사라지면 그 이력도 함께 사라진다 — 종전에는 집계조차 없었다.
                    skippedRelationshipChanges += changes.size
                    continue
                }
                otherNew = res.id
            }
            val newFactionId = rel.factionId?.let { old ->
                val res = resolveFaction(old)
                note(res)
                if (!res.found) clearedRelationshipFactions++
                res.id
            }
            val remapped = rel.copy(
                characterId1 = if (selfIsFirst) rel.characterId1 else otherNew!!,
                characterId2 = if (selfIsSecond) rel.characterId2 else otherNew!!,
                factionId = newFactionId
            )
            val remappedChanges = changes.map { change ->
                val newEventId = change.eventId?.let { old ->
                    val res = resolveEvent(old)
                    note(res)
                    if (!res.found) clearedChangeEvents++
                    res.id
                }
                change.copy(eventId = newEventId)
            }
            plannedRelationships.add(
                PlannedRelationship(remapped, selfIsFirst, selfIsSecond, remappedChanges)
            )
        }

        val plannedMemberships = ArrayList<FactionMembership>(data.factionMemberships.size)
        var skippedMemberships = 0
        for (m in data.factionMemberships) {
            val res = resolveFaction(m.factionId)
            note(res)
            val newId = res.id
            if (newId == null) {
                skippedMemberships++
                continue
            }
            plannedMemberships.add(m.copy(factionId = newId))
        }

        val plannedEvents = LinkedHashSet<Long>()
        var skippedEvents = 0
        for (old in data.eventIds) {
            val res = resolveEvent(old)
            note(res)
            val newId = res.id
            // 서로 다른 옛 id가 같은 사건으로 수렴하는 것은 유실이 아니라 병합이다 — 집계하지 않는다.
            if (newId == null) skippedEvents++ else plannedEvents.add(newId)
        }

        // 같은 코드의 캐릭터가 아직 살아 있으면 이 스냅샷은 '편집 직전 백업'이며,
        // 복원은 되돌리기가 아니라 복제가 된다 — 사실대로 미리 알린다.
        val livingSame = data.character.code.takeIf { it.isNotBlank() }
            ?.let { db.characterDao().getCharacterByCode(it) != null }
            ?: (db.characterDao().getCharacterById(data.character.id) != null)

        return RestorePlan(
            data = data,
            novelId = novelId,
            novelCleared = novelCleared,
            fieldValues = resolvedFieldValues,
            skippedFieldValues = skippedFieldValues,
            relationships = plannedRelationships,
            skippedRelationships = skippedRelationships,
            skippedRelationshipChanges = skippedRelationshipChanges,
            clearedRelationshipFactions = clearedRelationshipFactions,
            clearedChangeEvents = clearedChangeEvents,
            memberships = plannedMemberships,
            skippedMemberships = skippedMemberships,
            eventIds = plannedEvents.toList(),
            skippedEvents = skippedEvents,
            relinkedByCode = relinked,
            legacyPayload = legacyGuess,
            duplicatesLivingCharacter = livingSame
        )
    }

    /** 해석에 필요한 최소 인덱스 — 스냅샷이 실제로 참조하는 id/코드만 조회한다. */
    private class RefIndex(
        val codeById: Map<Long, String>,
        val idByCode: Map<String, Long>,
        val liveIds: Set<Long>
    )

    private suspend fun <T : Any> buildIndex(
        oldIds: Collection<Long>,
        codes: Collection<String>,
        fetchById: suspend (Long) -> T?,
        fetchByCode: suspend (String) -> T?,
        idOf: (T) -> Long,
        codeOf: (T) -> String?
    ): RefIndex {
        val codeById = HashMap<Long, String>()
        val idByCode = HashMap<String, Long>()
        val liveIds = HashSet<Long>()
        for (id in oldIds.distinct()) {
            val row = fetchById(id) ?: continue
            liveIds.add(id)
            codeOf(row)?.takeIf { it.isNotBlank() }?.let { codeById[id] = it }
        }
        for (code in codes.distinct()) {
            if (code.isBlank()) continue
            val row = fetchByCode(code) ?: continue
            idByCode[code] = idOf(row)
        }
        return RefIndex(codeById, idByCode, liveIds)
    }

    private class FieldDefIndex(
        val naturalById: Map<Long, FieldDefNaturalKey>,
        val idByNatural: Map<FieldDefNaturalKey, Long>
    )

    private suspend fun buildFieldDefIndex(
        oldIds: Collection<Long>,
        refs: Collection<FieldDefRef>
    ): FieldDefIndex {
        val naturalById = HashMap<Long, FieldDefNaturalKey>()
        val idByNatural = HashMap<FieldDefNaturalKey, Long>()
        for (id in oldIds.distinct()) {
            val fd = db.fieldDefinitionDao().getFieldById(id) ?: continue
            val uCode = universeCode(fd.universeId)
            naturalById[id] = FieldDefNaturalKey(uCode, fd.entityType, fd.key)
        }
        for (ref in refs) {
            val uCode = ref.universeCode?.takeIf { it.isNotBlank() } ?: continue
            val type = ref.entityType?.takeIf { it.isNotBlank() } ?: continue
            val key = ref.key?.takeIf { it.isNotBlank() } ?: continue
            val universe = db.universeDao().getUniverseByCode(uCode) ?: continue
            val fd = db.fieldDefinitionDao().getFieldByKey(universe.id, key, type) ?: continue
            idByNatural[FieldDefNaturalKey(uCode, type, key)] = fd.id
        }
        return FieldDefIndex(naturalById, idByNatural)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 영구 삭제 / 정리
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 영구 삭제 — 보류 중이던 이미지 파일도 함께 지운다.
     * 단, 라이브러리(image_meta)·다른 스냅샷·살아있는 엔티티가 여전히 쓰는 파일은 남긴다
     * (경로 공유 하 무음 파괴 방지). pruneIfNeeded의 자동 purge에도 동일하게 적용된다.
     */
    suspend fun purgeSnapshot(snapshot: TrashSnapshot) {
        try {
            com.novelcharacter.app.util.ImageOwnershipGuard.deleteIfUnprotected(
                db, null, parseImagePaths(snapshot.imagePaths),
                excludeTrashSnapshotId = snapshot.id
            )
        } catch (_: Exception) {
            // 가드 실패 시 파일은 남긴다(삭제보다 보존이 안전) — 스냅샷 행 삭제는 계속 진행.
        }
        trashDao.deleteById(snapshot.id)
        createdSnapshotIds.remove(snapshot.id)
    }

    /** 휴지통 비우기 — 영구 삭제한 스냅샷 개수를 반환한다(결과 통보용) */
    suspend fun emptyTrash(): Int {
        val all = trashDao.getAllList()
        all.forEach { purgeSnapshot(it) }
        return all.size
    }

    /**
     * 보관 기한/개수 초과분 자동 정리 — 스냅샷 추가 후 호출.
     *
     * **이 인스턴스가 방금 만든 스냅샷은 정리 대상에서 제외한다.** 세계관 삭제처럼 한 번에
     * 수백 건을 스냅샷하는 경로에서 종전 구현은 그 백업을 스스로 태우고(이미지 파일까지)
     * "휴지통에서 복구할 수 있습니다"라는 안내만 남겼다 — 4-6 규약 위반이다.
     * 한도를 넘긴 잔여분은 다음 삭제 작업의 정리에서 자연히 소진된다.
     */
    suspend fun pruneIfNeeded() {
        val expired = trashDao.getExpired(System.currentTimeMillis() - TrashSnapshot.RETENTION_MS)
        expired.filter { it.id !in createdSnapshotIds }.forEach { purgeSnapshot(it) }
        val overflow = trashDao.count() - TrashSnapshot.MAX_ITEMS
        if (overflow > 0) {
            trashDao.getOldest(overflow + createdSnapshotIds.size)
                .filter { it.id !in createdSnapshotIds }
                .take(overflow)
                .forEach { purgeSnapshot(it) }
        }
    }

    private fun parseImagePaths(json: String): List<String> {
        return try {
            gson.fromJson(json, GsonTypes.STRING_LIST) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
