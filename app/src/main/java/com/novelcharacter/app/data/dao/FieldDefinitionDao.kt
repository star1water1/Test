package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.FieldDefinition

@Dao
interface FieldDefinitionDao {
    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId ORDER BY displayOrder ASC")
    fun getFieldsByUniverse(universeId: Long): LiveData<List<FieldDefinition>>

    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId ORDER BY displayOrder ASC")
    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition>

    @Query("SELECT * FROM field_definitions WHERE id = :id")
    suspend fun getFieldById(id: Long): FieldDefinition?

    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId AND `key` = :key")
    suspend fun getFieldByKey(universeId: Long, key: String): FieldDefinition?

    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId AND type = :type ORDER BY displayOrder ASC")
    suspend fun getFieldsByType(universeId: Long, type: String): List<FieldDefinition>

    @Query("SELECT DISTINCT groupName FROM field_definitions WHERE universeId = :universeId ORDER BY MIN(displayOrder)")
    suspend fun getGroupNames(universeId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(field: FieldDefinition): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(fields: List<FieldDefinition>)

    @Update
    suspend fun update(field: FieldDefinition)

    @Delete
    suspend fun delete(field: FieldDefinition)

    @Query("DELETE FROM field_definitions WHERE universeId = :universeId")
    suspend fun deleteAllByUniverse(universeId: Long)

    @Query("SELECT universeId, COUNT(*) as cnt FROM field_definitions WHERE universeId IN (:universeIds) GROUP BY universeId")
    suspend fun getFieldCountsByUniverses(universeIds: List<Long>): List<UniverseFieldCount>
}

data class UniverseFieldCount(val universeId: Long, val cnt: Int)
