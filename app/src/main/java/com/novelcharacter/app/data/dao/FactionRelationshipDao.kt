package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.novelcharacter.app.data.model.FactionRelationship

@Dao
interface FactionRelationshipDao {

    /** 특정 세력이 어느 쪽으로든 참여한 관계 전부 */
    @Query("SELECT * FROM faction_relationships WHERE factionId1 = :factionId OR factionId2 = :factionId ORDER BY displayOrder, createdAt")
    suspend fun getRelationshipsForFactionList(factionId: Long): List<FactionRelationship>

    @Query("SELECT * FROM faction_relationships WHERE factionId1 = :factionId OR factionId2 = :factionId ORDER BY displayOrder, createdAt")
    fun getRelationshipsForFaction(factionId: Long): LiveData<List<FactionRelationship>>

    /** 세계관 내 모든 세력 간 관계 (그래프/엑셀용) */
    @Query(
        """SELECT fr.* FROM faction_relationships fr
           INNER JOIN factions f1 ON fr.factionId1 = f1.id
           WHERE f1.universeId = :universeId
           ORDER BY fr.displayOrder, fr.createdAt"""
    )
    suspend fun getRelationshipsByUniverseList(universeId: Long): List<FactionRelationship>

    @Query("SELECT * FROM faction_relationships ORDER BY displayOrder, createdAt")
    suspend fun getAllRelationshipsList(): List<FactionRelationship>

    @Query("SELECT * FROM faction_relationships WHERE id = :id")
    suspend fun getById(id: Long): FactionRelationship?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(relationship: FactionRelationship): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(relationships: List<FactionRelationship>): List<Long>

    @Update
    suspend fun update(relationship: FactionRelationship)

    @Delete
    suspend fun delete(relationship: FactionRelationship)

    @Query("DELETE FROM faction_relationships WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM faction_relationships")
    suspend fun deleteAll()

    @Query("SELECT id FROM faction_relationships")
    suspend fun getAllRelationshipIds(): List<Long>
}
