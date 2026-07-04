package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterSnapshot
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
 */
class TrashRepository(private val db: AppDatabase) {

    private val trashDao = db.trashSnapshotDao()
    private val gson = Gson()

    val allSnapshots: LiveData<List<TrashSnapshot>> = trashDao.getAll()

    /** 복원 결과 — 어떤 연관 데이터가 참조 소실로 생략됐는지 사용자에게 알리기 위한 집계 */
    data class RestoreResult(
        val restoredName: String,
        val skippedFieldValues: Int = 0,
        val skippedRelationships: Int = 0,
        val skippedMemberships: Int = 0,
        val skippedEvents: Int = 0,
        val novelCleared: Boolean = false
    ) {
        val hasSkipped: Boolean
            get() = skippedFieldValues > 0 || skippedRelationships > 0 ||
                skippedMemberships > 0 || skippedEvents > 0 || novelCleared
    }

    /**
     * 캐릭터+연관 데이터를 스냅샷으로 보관한다.
     * 반드시 삭제 트랜잭션 내에서, 실제 삭제 전에 호출할 것.
     */
    suspend fun snapshotCharacter(character: Character, imagePaths: List<String>) {
        val relationships = db.characterRelationshipDao().getRelationshipsForCharacterList(character.id)
        val relationshipChanges = relationships.flatMap { rel ->
            db.characterRelationshipChangeDao().getChangesForRelationshipList(rel.id)
        }
        val snapshot = CharacterSnapshot(
            character = character,
            fieldValues = db.characterFieldValueDao().getValuesByCharacterList(character.id),
            stateChanges = db.characterStateChangeDao().getChangesByCharacterList(character.id),
            tags = db.characterTagDao().getTagsByCharacterList(character.id),
            relationships = relationships,
            relationshipChanges = relationshipChanges,
            factionMemberships = db.factionMembershipDao().getMembershipsByCharacterList(character.id),
            eventIds = db.timelineDao().getEventIdsForCharacter(character.id)
        )
        trashDao.insert(
            TrashSnapshot(
                entityType = TrashSnapshot.TYPE_CHARACTER,
                entityName = character.name,
                payload = gson.toJson(snapshot),
                imagePaths = gson.toJson(imagePaths)
            )
        )
    }

    /**
     * 스냅샷에서 캐릭터를 복원한다.
     * 원본 id 자리가 비어 있으면 같은 id로, 차 있으면 새 id로 복원하며
     * 그 사이 삭제된 참조(필드 정의/상대 캐릭터/세력/사건)는 건너뛰고 집계해 알린다.
     */
    suspend fun restoreCharacter(snapshotId: Long): RestoreResult? {
        val snap = trashDao.getById(snapshotId) ?: return null
        val data = try {
            gson.fromJson(snap.payload, CharacterSnapshot::class.java)
        } catch (_: Exception) {
            null
        } ?: return null

        var skippedFieldValues = 0
        var skippedRelationships = 0
        var skippedMemberships = 0
        var skippedEvents = 0
        var novelCleared = false

        db.withTransaction {
            // 작품이 그 사이 삭제됐으면 미배정으로 복원
            var character = data.character
            if (character.novelId != null && db.novelDao().getNovelById(character.novelId!!) == null) {
                character = character.copy(novelId = null)
                novelCleared = true
            }
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

            // 필드값 — 필드 정의가 남아 있는 것만
            val validFieldValues = data.fieldValues.filter {
                db.fieldDefinitionDao().getFieldById(it.fieldDefinitionId) != null
            }
            skippedFieldValues = data.fieldValues.size - validFieldValues.size
            db.characterFieldValueDao().insertAll(
                validFieldValues.map { it.copy(id = 0, characterId = targetId) }
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

            // 관계 — 상대 캐릭터가 남아 있는 것만, 관계변화 이력은 새 관계 id로 재연결
            for (rel in data.relationships) {
                val otherId = if (rel.characterId1 == data.character.id) rel.characterId2 else rel.characterId1
                val otherExists = otherId == data.character.id ||
                    db.characterDao().getCharacterById(otherId) != null
                if (!otherExists) {
                    skippedRelationships++
                    continue
                }
                val factionId = rel.factionId?.takeIf { db.factionDao().getById(it) != null }
                val remapped = rel.copy(
                    id = 0,
                    characterId1 = if (rel.characterId1 == data.character.id) targetId else rel.characterId1,
                    characterId2 = if (rel.characterId2 == data.character.id) targetId else rel.characterId2,
                    factionId = factionId
                )
                val newRelId = db.characterRelationshipDao().insert(remapped)
                if (newRelId == -1L) {
                    // 동일 (상대, 유형) 관계가 이미 존재 — 중복 생성하지 않음
                    skippedRelationships++
                    continue
                }
                val changes = data.relationshipChanges
                    .filter { it.relationshipId == rel.id }
                    .map { change ->
                        val eventId = change.eventId?.takeIf { db.timelineDao().getEventById(it) != null }
                        // code 충돌 시 재발급 — 상태변화 복원과 동일 규칙
                        val safeCode = change.code
                            ?.takeIf { c -> db.characterRelationshipChangeDao().getChangeByCode(c) == null }
                            ?: generateEntityCode()
                        change.copy(id = 0, relationshipId = newRelId, eventId = eventId, code = safeCode)
                    }
                if (changes.isNotEmpty()) {
                    db.characterRelationshipChangeDao().insertAll(changes)
                }
            }

            // 세력 소속 — 세력이 남아 있는 것만
            val validMemberships = data.factionMemberships.filter {
                db.factionDao().getById(it.factionId) != null
            }
            skippedMemberships = data.factionMemberships.size - validMemberships.size
            for (membership in validMemberships) {
                db.factionMembershipDao().insert(membership.copy(id = 0, characterId = targetId))
            }

            // 사건 연결 — 사건이 남아 있는 것만
            for (eventId in data.eventIds) {
                if (db.timelineDao().getEventById(eventId) != null) {
                    db.timelineDao().insertCrossRef(TimelineCharacterCrossRef(eventId, targetId))
                } else {
                    skippedEvents++
                }
            }
        }

        // 복원 완료 — 스냅샷 제거 (이미지 파일은 복원된 캐릭터가 소유)
        trashDao.deleteById(snapshotId)

        return RestoreResult(
            restoredName = data.character.name,
            skippedFieldValues = skippedFieldValues,
            skippedRelationships = skippedRelationships,
            skippedMemberships = skippedMemberships,
            skippedEvents = skippedEvents,
            novelCleared = novelCleared
        )
    }

    /** 영구 삭제 — 보류 중이던 이미지 파일도 함께 지운다 */
    suspend fun purgeSnapshot(snapshot: TrashSnapshot) {
        parseImagePaths(snapshot.imagePaths).forEach { path ->
            try {
                File(path).delete()
            } catch (_: Exception) {
            }
        }
        trashDao.deleteById(snapshot.id)
    }

    /** 휴지통 비우기 */
    suspend fun emptyTrash() {
        trashDao.getAllList().forEach { purgeSnapshot(it) }
    }

    /** 보관 기한/개수 초과분 자동 정리 — 스냅샷 추가 후 호출 */
    suspend fun pruneIfNeeded() {
        val expired = trashDao.getExpired(System.currentTimeMillis() - TrashSnapshot.RETENTION_MS)
        expired.forEach { purgeSnapshot(it) }
        val overflow = trashDao.count() - TrashSnapshot.MAX_ITEMS
        if (overflow > 0) {
            trashDao.getOldest(overflow).forEach { purgeSnapshot(it) }
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
