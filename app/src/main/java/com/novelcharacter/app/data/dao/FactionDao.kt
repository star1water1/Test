package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.Faction

@Dao
interface FactionDao {
    @Query("SELECT * FROM factions WHERE universeId = :universeId ORDER BY displayOrder ASC, createdAt DESC")
    fun getFactionsByUniverse(universeId: Long): LiveData<List<Faction>>

    @Query("SELECT * FROM factions WHERE universeId = :universeId ORDER BY displayOrder ASC, createdAt DESC")
    suspend fun getFactionsByUniverseList(universeId: Long): List<Faction>

    @Query("SELECT * FROM factions ORDER BY displayOrder ASC, createdAt DESC")
    suspend fun getAllFactionsList(): List<Faction>

    @Query("SELECT * FROM factions WHERE id = :id")
    suspend fun getById(id: Long): Faction?

    @Query("SELECT * FROM factions WHERE code = :code")
    suspend fun getByCode(code: String): Faction?

    @Query("SELECT * FROM factions WHERE name = :name AND universeId = :universeId LIMIT 1")
    suspend fun getByNameAndUniverse(name: String, universeId: Long): Faction?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(faction: Faction): Long

    @Update
    suspend fun update(faction: Faction)

    @Delete
    suspend fun delete(faction: Faction)

    @Update
    suspend fun updateAll(factions: List<Faction>)
}
