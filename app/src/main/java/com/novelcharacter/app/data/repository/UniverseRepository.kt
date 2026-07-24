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
    suspend fun insertUniverse(universe: Universe): Long {
        return db.withTransaction {
            val next = universeDao.getNextDisplayOrder()
            universeDao.insert(universe.copy(displayOrder = next))
        }
    }
    suspend fun updateUniverse(universe: Universe) = universeDao.update(universe)

    /**
     * 세계관 삭제 — 하위 작품·캐릭터·사건까지 계단식으로 함께 삭제한다.
     * (세력·필드 정의·필드값·사건 연결은 FK CASCADE로 정리)
     * 캐릭터는 삭제 전 개별 휴지통 스냅샷을 남겨 복원 가능하게 한다(변수 제어 — 말없는 유실 방지).
     */
    suspend fun deleteUniverse(universe: Universe) {
        val trash = TrashRepository(db)
        // 이미지 파일 경로를 트랜잭션 전에 파싱 (DB 삭제 후에는 접근 불가)
        // 하위 작품의 이미지도 함께 정리 대상 — 트랜잭션 전에 수집한다.
        val novels = novelDao.getNovelsByUniverseList(universe.id)
        val imageFiles = parseImagePaths(universe.imagePaths) +
            novels.flatMap { parseImagePaths(it.imagePaths) }

        db.withTransaction {
            // 1. 하위 캐릭터 계단식 삭제 (휴지통 스냅샷 후 삭제 — 태그/필드값/관계 등은 FK CASCADE)
            val characterIds = db.characterDao().getCharactersByUniverseList(universe.id).map { it.id }
            CharacterRepository.deleteCharactersCascade(db, trash, characterIds)

            // 2. 하위 작품 삭제 (다른 세계관의 imageNovelId 참조는 FK SET_NULL이 정리)
            if (novels.isNotEmpty()) {
                novels.map { it.id }.chunked(900).forEach { chunk ->
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
    suspend fun updateUniverseDisplayOrders(universes: List<Universe>) = universeDao.updateAll(universes)

    // ===== FieldDefinition =====
    fun getFieldsByUniverse(universeId: Long): LiveData<List<FieldDefinition>> =
        fieldDefinitionDao.getFieldsByUniverse(universeId)

    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        fieldDefinitionDao.getFieldsByUniverseList(universeId)

    // 사건 필드 (B-10) — entityType = "event"
    fun getEventFieldsByUniverse(universeId: Long): LiveData<List<FieldDefinition>> =
        fieldDefinitionDao.getFieldsByUniverse(universeId, FieldDefinition.ENTITY_EVENT)

    suspend fun getEventFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        fieldDefinitionDao.getFieldsByUniverseList(universeId, FieldDefinition.ENTITY_EVENT)

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

    suspend fun updateFieldsOrder(fields: List<FieldDefinition>) =
        fieldDefinitionDao.updateAll(fields)

    suspend fun deleteField(field: FieldDefinition) {
        db.withTransaction {
            // 상태변화 이력(fieldKey 문자열 참조) 정리는 캐릭터 필드에만 해당.
            // 사건 필드값은 FK CASCADE로 함께 삭제된다.
            if (field.entityType == FieldDefinition.ENTITY_CHARACTER) {
                // 같은 key를 가진 다른 세계관의 필드가 없으면 전체 삭제 (고아 캐릭터 포함)
                // 있으면 해당 세계관의 캐릭터에 한정하여 삭제
                val otherFieldsWithSameKey = fieldDefinitionDao.countFieldsByKeyExcluding(field.key, field.id)
                if (otherFieldsWithSameKey == 0) {
                    db.characterStateChangeDao().deleteChangesByFieldKey(field.key)
                } else {
                    db.characterStateChangeDao().deleteChangesByFieldKeyAndUniverse(field.key, field.universeId)
                }
            }
            fieldDefinitionDao.delete(field)
        }
    }

    suspend fun deleteAllFieldsByUniverse(universeId: Long) =
        fieldDefinitionDao.deleteAllByUniverse(universeId)

    // ===== Batch count queries =====
    suspend fun getNovelCountsByUniverses(universeIds: List<Long>): Map<Long, Int> {
        if (universeIds.isEmpty()) return emptyMap()
        return novelDao.getNovelCountsByUniverses(universeIds).associate { it.universeId to it.cnt }
    }

    suspend fun getFieldCountsByUniverses(universeIds: List<Long>): Map<Long, Int> {
        if (universeIds.isEmpty()) return emptyMap()
        return fieldDefinitionDao.getFieldCountsByUniverses(universeIds).associate { it.universeId to it.cnt }
    }
}
