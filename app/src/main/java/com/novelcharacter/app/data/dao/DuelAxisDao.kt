package com.novelcharacter.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.novelcharacter.app.data.model.DuelAxis

@Dao
interface DuelAxisDao {

    @Query("SELECT * FROM duel_axes WHERE universeId = :universeId ORDER BY displayOrder ASC, id ASC")
    suspend fun getByUniverseList(universeId: Long): List<DuelAxis>

    @Query(
        "SELECT * FROM duel_axes WHERE universeId = :universeId AND targetType = :targetType " +
            "ORDER BY displayOrder ASC, id ASC"
    )
    suspend fun getByUniverseAndTarget(universeId: Long, targetType: String): List<DuelAxis>

    @Query("SELECT * FROM duel_axes ORDER BY universeId ASC, displayOrder ASC, id ASC")
    suspend fun getAllList(): List<DuelAxis>

    @Query("SELECT * FROM duel_axes WHERE id = :id")
    suspend fun getById(id: Long): DuelAxis?

    @Query("SELECT * FROM duel_axes WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): DuelAxis?

    @Query(
        "SELECT * FROM duel_axes WHERE universeId = :universeId AND targetType = :targetType " +
            "AND name = :name LIMIT 1"
    )
    suspend fun getByUniverseAndName(universeId: Long, targetType: String, name: String): DuelAxis?

    @Insert
    suspend fun insert(axis: DuelAxis): Long

    @Update
    suspend fun update(axis: DuelAxis)

    @Delete
    suspend fun delete(axis: DuelAxis)
}
