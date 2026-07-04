package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 필드 정의 DAO. entityType 파라미터는 기본값 "character"로,
 * 기존 캐릭터 경로 호출부(통계·편집·엑셀 등)는 시그니처 변경 없이 캐릭터 필드만 받는다.
 * 사건 필드(B-10)는 entityType = "event"를 명시해 조회한다.
 */
@Dao
interface FieldDefinitionDao {
    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId AND entityType = :entityType ORDER BY displayOrder ASC")
    fun getFieldsByUniverse(universeId: Long, entityType: String = FieldDefinition.ENTITY_CHARACTER): LiveData<List<FieldDefinition>>

    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId AND entityType = :entityType ORDER BY displayOrder ASC")
    suspend fun getFieldsByUniverseList(universeId: Long, entityType: String = FieldDefinition.ENTITY_CHARACTER): List<FieldDefinition>

    @Query("SELECT * FROM field_definitions WHERE id = :id")
    suspend fun getFieldById(id: Long): FieldDefinition?

    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId AND `key` = :key AND entityType = :entityType")
    suspend fun getFieldByKey(universeId: Long, key: String, entityType: String = FieldDefinition.ENTITY_CHARACTER): FieldDefinition?

    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId AND type = :type AND entityType = :entityType ORDER BY displayOrder ASC")
    suspend fun getFieldsByType(universeId: Long, type: String, entityType: String = FieldDefinition.ENTITY_CHARACTER): List<FieldDefinition>

    @Query("SELECT groupName FROM field_definitions WHERE universeId = :universeId AND entityType = :entityType GROUP BY groupName ORDER BY MIN(displayOrder)")
    suspend fun getGroupNames(universeId: Long, entityType: String = FieldDefinition.ENTITY_CHARACTER): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(field: FieldDefinition): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(fields: List<FieldDefinition>)

    @Update
    suspend fun update(field: FieldDefinition)

    @Update
    suspend fun updateAll(fields: List<FieldDefinition>)

    @Delete
    suspend fun delete(field: FieldDefinition)

    @Query("SELECT * FROM field_definitions WHERE entityType = :entityType ORDER BY universeId ASC, displayOrder ASC")
    suspend fun getAllFieldsList(entityType: String = FieldDefinition.ENTITY_CHARACTER): List<FieldDefinition>

    @Query("DELETE FROM field_definitions WHERE universeId = :universeId")
    suspend fun deleteAllByUniverse(universeId: Long)

    @Query("SELECT universeId, COUNT(*) as cnt FROM field_definitions WHERE universeId IN (:universeIds) GROUP BY universeId")
    suspend fun getFieldCountsByUniverses(universeIds: List<Long>): List<UniverseFieldCount>

    /** 특정 필드를 제외하고 같은 key를 가진 필드가 존재하는지 확인 (같은 entityType 내) */
    @Query("SELECT COUNT(*) FROM field_definitions WHERE `key` = :key AND id != :excludeId AND entityType = :entityType")
    suspend fun countFieldsByKeyExcluding(key: String, excludeId: Long, entityType: String = FieldDefinition.ENTITY_CHARACTER): Int

    @Query("DELETE FROM field_definitions")
    suspend fun deleteAll()
}

data class UniverseFieldCount(val universeId: Long, val cnt: Int)
