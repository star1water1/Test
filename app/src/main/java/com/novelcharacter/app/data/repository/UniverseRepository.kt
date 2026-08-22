package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.FieldDefinitionDao
import com.novelcharacter.app.data.dao.NovelDao
import com.novelcharacter.app.data.dao.UniverseDao
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.RecentActivity
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.util.SqlInChunks

class UniverseRepository(
    private val db: AppDatabase,
    private val universeDao: UniverseDao,
    private val fieldDefinitionDao: FieldDefinitionDao,
    private val novelDao: NovelDao
) {
    // ===== Universe =====
    val allUniverses: LiveData<List<Universe>> = universeDao.getAllUniverses()

    suspend fun getAllUniversesList(): List<Universe> = universeDao.getAllUniversesList()
    suspend fun getUniverseById(id: Long): Universe? = universeDao.getUniverseById(id)
    /**
     * 세계관을 만든다 — **전역 기본 필드를 함께 심는다** (B-119).
     *
     * 심기를 부르는 쪽이 아니라 여기 두는 이유: 세계관을 만드는 길이 하나가 아니다(빈 세계관 ·
     * 프리셋). 부르는 쪽마다 적으면 **다음에 생기는 길이 그것을 빠뜨리고**, 그 고장은
     * *"이 세계관에만 기본 필드가 없다"*로 조용히 난다 — 설계 1-2가 참조형을 기각한 그 증상과
     * 같은 부류라 처방도 같다(한 자리로 모은다).
     *
     * **월드패키지·엑셀·휴지통 복원은 이 길로 오지 않는다**(자기 필드를 곧바로 넣으므로 미리
     * 심으면 그 삽입이 유니크 제약에 걸린다). 그쪽은 기본 필드 관리 화면의 **'다시 심기'**가
     * 따라잡는다 — 그것이 그 단추가 있는 이유다.
     *
     * 심기가 같은 트랜잭션이라 **세계관만 서고 기본 필드는 없는 중간 상태가 남지 않는다.**
     */
    suspend fun insertUniverse(universe: Universe): Long {
        return db.withTransaction {
            val next = universeDao.getNextDisplayOrder()
            val id = universeDao.insert(universe.copy(displayOrder = next))
            DefaultFieldTemplateRepository(db).plantIntoUniverse(id, universe.name)
            id
        }
    }
    suspend fun updateUniverse(universe: Universe) = universeDao.update(universe)

    /**
     * 세계관 삭제 — 하위 작품·캐릭터·사건·세력까지 계단식으로 함께 삭제한다.
     *
     * **삭제되는 모든 엔티티가 휴지통 스냅샷을 남긴다 (B-1).** 종전에는 캐릭터만 복원
     * 가능했고 세계관·작품·사건·세력·필드 정의는 즉시 영구 소멸했다.
     * 스냅샷들은 하나의 삭제 작업(`operationId`)으로 묶여 통째로 보관·복원되며
     * (B-14 — 반쪽만 남는 백업은 복원이 아니라 유실이다), 복원은
     * 세계관 → 작품 → 세력 → 사건 → 캐릭터 순서라 참조가 코드로 다시 이어진다(R-1).
     *
     * 스냅샷 수집은 **어떤 삭제보다 먼저** 끝내야 한다 — 1단계가 캐릭터를 지우고 나면
     * 세력 소속·사건 연결을 더 이상 읽을 수 없다.
     */
    suspend fun deleteUniverse(universe: Universe) {
        val trash = TrashRepository(db)
        // 이미지 파일 경로를 트랜잭션 전에 파싱 (DB 삭제 후에는 접근 불가)
        // 하위 작품의 이미지도 함께 정리 대상 — 트랜잭션 전에 수집한다.
        // (스냅샷이 같은 경로를 보유하므로 ImageOwnershipGuard가 실제로는 보존한다 —
        //  가드 호출은 스냅샷 생성이 실패한 경우의 안전망으로 남긴다)
        val novels = novelDao.getNovelsByUniverseList(universe.id)
        val imageFiles = parseImagePaths(universe.imagePaths) +
            novels.flatMap { parseImagePaths(it.imagePaths) }

        db.withTransaction {
            // 0. 스냅샷 — 삭제 전에 전부 수집한다.
            //    같은 작업이 함께 지우는 대상 집합을 먼저 확정해야 링크를 어느 쪽이 담을지
            //    결정할 수 있다(양쪽이 담으면 복원이 중복되고 거짓 경고가 뜬다).
            val characters = db.characterDao().getCharactersByUniverseList(universe.id)
            val characterIds = characters.map { it.id }
            val doomedCharacterIds = characterIds.toSet()
            val events = db.timelineDao().getEventsByUniverseList(universe.id)
            val doomedEventIds = events.map { it.id }.toSet()
            val factions = db.factionDao().getFactionsByUniverseList(universe.id)

            trash.snapshotUniverse(universe)
            for (novel in novels) trash.snapshotNovel(novel, doomedEventIds)
            for (faction in factions) {
                // 세력은 세계관 FK CASCADE로 죽는다 — 자동 관계는 삭제되지 않고
                // factionId만 null이 되므로(SET_NULL) '유지' 쪽과 같은 형태다.
                trash.snapshotFaction(
                    faction, deleteRelationships = false, doomedCharacterIds = doomedCharacterIds
                )
            }
            for (event in events) trash.snapshotEvent(event, doomedCharacterIds)

            // 1. 하위 캐릭터 계단식 삭제 (휴지통 스냅샷 후 삭제 — 태그/필드값/관계 등은 FK CASCADE)
            CharacterRepository.deleteCharactersCascade(db, trash, characterIds)

            // 2. 하위 작품 삭제 (다른 세계관의 imageNovelId 참조는 FK SET_NULL이 정리)
            if (novels.isNotEmpty()) {
                SqlInChunks.each(novels.map { it.id }) { chunk ->
                    db.recentActivityDao().deleteByEntityIds(RecentActivity.TYPE_NOVEL, chunk)
                }
                novels.forEach { novelDao.delete(it) }
            }

            // 3. 이 세계관의 사건 삭제 (사건 필드값·캐릭터/작품 연결은 FK CASCADE)
            db.timelineDao().deleteAllByUniverse(universe.id)

            // 4. FieldDefinition은 FK CASCADE로 삭제되지만,
            // CharacterStateChange는 string fieldKey 참조라 CASCADE 대상이 아니므로 명시적 삭제
            // (field_definitions 테이블이 아직 존재하는 시점에 실행해야 서브쿼리 동작)
            db.characterStateChangeDao().deleteAllChangesByUniverse(universe.id)
            db.recentActivityDao().deleteByEntity(RecentActivity.TYPE_UNIVERSE, universe.id)
            universeDao.delete(universe)
        }

        // 캐릭터 이미지 파일은 휴지통 스냅샷이 살아 있는 동안 유지 — 정리 시점에 함께 삭제
        trash.pruneIfNeeded()

        // DB 트랜잭션 성공 후 디스크에서 이미지 파일 삭제 — 단, 다른 엔티티가 공유 중이거나
        // 라이브러리(image_meta)·휴지통이 보유한 파일은 지우지 않는다(경로 공유 배정 하 무음 파괴 방지).
        // Context 없는 저장소 계층이라 드래프트 보호는 생략된다(가드 문서 참조).
        try {
            com.novelcharacter.app.util.ImageOwnershipGuard.deleteIfUnprotected(
                db, null, imageFiles.map { it.absolutePath }
            )
        } catch (e: Exception) {
            android.util.Log.w("UniverseRepository", "Guarded image cleanup failed", e)
        }
    }

    private fun parseImagePaths(imagePathsJson: String): List<java.io.File> {
        return try {
            val raw: List<String?>? = com.google.gson.Gson().fromJson(imagePathsJson, com.novelcharacter.app.util.GsonTypes.STRING_LIST)
            raw?.filterNotNull()?.map { java.io.File(it) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
    /**
     * 세계관 순서 저장 — **차례만 받아 `displayOrder`만 쓴다.**
     *
     * 화면이 든 엔티티 사본이 아니라 id 차례를 받는다. 순서 편집이 열려 있는 동안 같은 행이
     * 다른 경로에서 바뀌어도(이름·설명·이미지·경계선) 그 변경이 순서 저장에 되감기지 않는다.
     * 한 트랜잭션으로 묶어 중간 상태가 목록에 방출되지 않게 한다.
     */
    suspend fun updateUniverseDisplayOrders(orderedIds: List<Long>) = db.withTransaction {
        orderedIds.forEachIndexed { index, id -> universeDao.setDisplayOrder(id, index.toLong()) }
    }

    // ===== FieldDefinition =====
    fun getFieldsByUniverse(universeId: Long): LiveData<List<FieldDefinition>> =
        fieldDefinitionDao.getFieldsByUniverse(universeId)

    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        fieldDefinitionDao.getFieldsByUniverseList(universeId)

    /**
     * 세계관·entityType을 가리지 않는 id 조회 — 보관 중인(세계관 밖) 필드값 표시용 (N2).
     * IN 절 변수 한도를 넘지 않도록 [SqlInChunks]가 나눈다 — 저장소 공통 통로.
     */
    suspend fun getFieldsByIds(ids: List<Long>): List<FieldDefinition> =
        SqlInChunks.flat(ids) { fieldDefinitionDao.getFieldsByIds(it) }

    // 사건 필드 (B-10) — entityType = "event"
    fun getEventFieldsByUniverse(universeId: Long): LiveData<List<FieldDefinition>> =
        fieldDefinitionDao.getFieldsByUniverse(universeId, FieldDefinition.ENTITY_EVENT)

    suspend fun getEventFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        fieldDefinitionDao.getFieldsByUniverseList(universeId, FieldDefinition.ENTITY_EVENT)

    // 작품 필드 (확-3) — entityType = "novel"
    fun getNovelFieldsByUniverse(universeId: Long): LiveData<List<FieldDefinition>> =
        fieldDefinitionDao.getFieldsByUniverse(universeId, FieldDefinition.ENTITY_NOVEL)

    suspend fun getNovelFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        fieldDefinitionDao.getFieldsByUniverseList(universeId, FieldDefinition.ENTITY_NOVEL)

    suspend fun getFieldById(id: Long): FieldDefinition? =
        fieldDefinitionDao.getFieldById(id)

    suspend fun getFieldByKey(universeId: Long, key: String): FieldDefinition? =
        fieldDefinitionDao.getFieldByKey(universeId, key)

    suspend fun getFieldsByType(universeId: Long, type: String): List<FieldDefinition> =
        fieldDefinitionDao.getFieldsByType(universeId, type)

    suspend fun getGroupNames(universeId: Long): List<String> =
        fieldDefinitionDao.getGroupNames(universeId)

    /**
     * ⚠️ **전역 구역(`universeId = null`)을 넘길 때는 부르는 쪽이 key 유일성을 책임진다** —
     * 유니크 색인 `(universeId, entityType, key)`는 NULL끼리를 서로 다른 값으로 보므로
     * 그 구역에서는 아무것도 막지 않는다(B-230 ⓑ). 세계관 필드라면 색인이 잡아
     * `SQLiteConstraintException`이 뜨지만, 전역 구역에서는 **조용히 중복이 들어간다.**
     */
    // 전역키 보증(호출부 책임 — 위 KDoc. 전역 구역에 실제로 심는 자리는
    // DefaultFieldTemplateRepository이고 그쪽이 planPlant로 기존 key를 거른다)
    suspend fun insertField(field: FieldDefinition): Long =
        fieldDefinitionDao.insert(field)

    /** 전역 구역을 담을 수 있다 — 유일성 책임은 [insertField]의 KDoc과 같다. */
    // 전역키 보증(호출부 책임 — 위 KDoc)
    suspend fun insertAllFields(fields: List<FieldDefinition>) =
        fieldDefinitionDao.insertAll(fields)

    suspend fun updateField(field: FieldDefinition) =
        fieldDefinitionDao.update(field)

    suspend fun updateFieldsOrder(fields: List<FieldDefinition>) =
        fieldDefinitionDao.updateAll(fields)

    suspend fun deleteField(field: FieldDefinition) {
        db.withTransaction {
            // 상태변화 이력(fieldKey 문자열 참조) 정리는 캐릭터 필드에만 해당.
            // 사건·작품 필드값은 FK CASCADE로 함께 삭제된다.
            if (field.entityType == FieldDefinition.ENTITY_CHARACTER) {
                // 같은 key를 가진 다른 세계관의 필드가 없으면 전체 삭제 (고아 캐릭터 포함)
                // 있으면 해당 세계관의 캐릭터에 한정하여 삭제
                val otherFieldsWithSameKey = fieldDefinitionDao.countFieldsByKeyExcluding(field.key, field.id)
                val fieldUniverseId = field.universeId
                if (otherFieldsWithSameKey == 0) {
                    db.characterStateChangeDao().deleteChangesByFieldKey(field.key)
                } else if (fieldUniverseId != null) {
                    db.characterStateChangeDao().deleteChangesByFieldKeyAndUniverse(field.key, fieldUniverseId)
                }
                // 전역 구역 필드(null)에 같은 key의 형제가 남아 있는 경우는 지울 것이 없다 —
                // 상태변화는 연표(세계관) 기능이 만들고, 무소속 캐릭터에는 연표가 없다.
            }
            fieldDefinitionDao.delete(field)
        }
    }

    suspend fun deleteAllFieldsByUniverse(universeId: Long) =
        fieldDefinitionDao.deleteAllByUniverse(universeId)

    // ===== Batch count queries =====
    //
    // **집계인데 조각별 답을 그냥 이어도 되는 이유:** 두 질의 모두 `GROUP BY`의 키가 `IN` 열
    // 자신이라 한 세계관의 행은 **한 조각 안에 통째로** 들어간다. 묶음이 조각을 가로지르지
    // 않으므로 합칠 것이 없다. (`GROUP BY`가 다른 열이거나 `COUNT(*)` 하나를 돌려주는
    // 질의였다면 잇는 것이 아니라 [SqlInChunks.sum]으로 **더해야** 한다.)
    suspend fun getNovelCountsByUniverses(universeIds: List<Long>): Map<Long, Int> =
        SqlInChunks.flat(universeIds) { novelDao.getNovelCountsByUniverses(it) }
            .associate { it.universeId to it.cnt }

    suspend fun getFieldCountsByUniverses(universeIds: List<Long>): Map<Long, Int> =
        SqlInChunks.flat(universeIds) { fieldDefinitionDao.getFieldCountsByUniverses(it) }
            .associate { it.universeId to it.cnt }
}
