package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.Universe

@Dao
interface UniverseDao {
    @Query("SELECT * FROM universes ORDER BY createdAt DESC")
    fun getAllUniverses(): LiveData<List<Universe>>

    @Query("SELECT * FROM universes ORDER BY createdAt DESC")
    suspend fun getAllUniversesList(): List<Universe>

    @Query("SELECT * FROM universes WHERE id = :id")
    suspend fun getUniverseById(id: Long): Universe?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(universe: Universe): Long

    @Update
    suspend fun update(universe: Universe)

    @Query("SELECT * FROM universes WHERE name = :name LIMIT 1")
    suspend fun getUniverseByName(name: String): Universe?

    @Query("SELECT * FROM universes WHERE code = :code LIMIT 1")
    suspend fun getUniverseByCode(code: String): Universe?

    @Delete
    suspend fun delete(universe: Universe)
}
