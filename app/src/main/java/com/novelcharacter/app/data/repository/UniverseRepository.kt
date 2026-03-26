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
    suspend fun deleteUniverse(universe: Universe) {
        // 이미지 파일 경로를 트랜잭션 전에 파싱 (DB 삭제 후에는 접근 불가)
        val imageFiles = parseImagePaths(universe.imagePaths)

        db.withTransaction {
            // FieldDefinition은 FK CASCADE로 삭제되지만,
            // CharacterStateChange는 string fieldKey 참조라 CASCADE 대상이 아니므로 명시적 삭제
            // (field_definitions 테이블이 아직 존재하는 시점에 실행해야 서브쿼리 동작)
            db.characterStateChangeDao().deleteAllChangesByUniverse(universe.id)
            db.recentActivityDao().deleteByEntity(RecentActivity.TYPE_UNIVERSE, universe.id)
            universeDao.delete(universe)
        }

        // DB 트랜잭션 성공 후 디스크에서 이미지 파일 삭제
        for (file in imageFiles) {
            try {
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                android.util.Log.w("UniverseRepository", "Failed to delete image: ${file.absolutePath}", e)
            }
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
            // 같은 key를 가진 다른 세계관의 필드가 없으면 전체 삭제 (고아 캐릭터 포함)
            // 있으면 해당 세계관의 캐릭터에 한정하여 삭제
            val otherFieldsWithSameKey = fieldDefinitionDao.countFieldsByKeyExcluding(field.key, field.id)
            if (otherFieldsWithSameKey == 0) {
                db.characterStateChangeDao().deleteChangesByFieldKey(field.key)
            } else {
                db.characterStateChangeDao().deleteChangesByFieldKeyAndUniverse(field.key, field.universeId)
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
