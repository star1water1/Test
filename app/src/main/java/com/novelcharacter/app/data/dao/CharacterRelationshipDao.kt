package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterRelationship

@Dao
interface CharacterRelationshipDao {
    @Query("SELECT * FROM character_relationships WHERE characterId1 = :characterId OR characterId2 = :characterId ORDER BY createdAt DESC")
    fun getRelationshipsForCharacter(characterId: Long): LiveData<List<CharacterRelationship>>

    @Query("SELECT * FROM character_relationships WHERE characterId1 = :characterId OR characterId2 = :characterId ORDER BY createdAt DESC")
    suspend fun getRelationshipsForCharacterList(characterId: Long): List<CharacterRelationship>

    @Query("SELECT * FROM character_relationships ORDER BY createdAt DESC")
    suspend fun getAllRelationships(): List<CharacterRelationship>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(relationship: CharacterRelationship): Long

    @Update
    suspend fun update(relationship: CharacterRelationship)

    @Delete
    suspend fun delete(relationship: CharacterRelationship)
}
