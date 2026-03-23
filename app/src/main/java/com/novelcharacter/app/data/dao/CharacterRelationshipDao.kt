package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterRelationship

@Dao
interface CharacterRelationshipDao {
    @Query("SELECT * FROM character_relationships WHERE characterId1 = :characterId OR characterId2 = :characterId ORDER BY displayOrder ASC, createdAt DESC")
    fun getRelationshipsForCharacter(characterId: Long): LiveData<List<CharacterRelationship>>

    @Query("SELECT * FROM character_relationships WHERE characterId1 = :characterId OR characterId2 = :characterId ORDER BY displayOrder ASC, createdAt DESC")
    suspend fun getRelationshipsForCharacterList(characterId: Long): List<CharacterRelationship>

    @Update
    suspend fun updateAll(relationships: List<CharacterRelationship>)

    @Query("SELECT * FROM character_relationships WHERE id = :id")
    suspend fun getById(id: Long): CharacterRelationship?

    @Query("SELECT * FROM character_relationships ORDER BY createdAt DESC")
    suspend fun getAllRelationships(): List<CharacterRelationship>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(relationship: CharacterRelationship): Long

    @Update
    suspend fun update(relationship: CharacterRelationship)

    @Delete
    suspend fun delete(relationship: CharacterRelationship)

    @Query("DELETE FROM character_relationships WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 특정 세력의 자동 관계 중, 지정 캐릭터가 포함된 것 삭제 */
    @Query("""
        DELETE FROM character_relationships
        WHERE factionId = :factionId
        AND (characterId1 = :characterId OR characterId2 = :characterId)
    """)
    suspend fun deleteFactionRelationshipsForCharacter(factionId: Long, characterId: Long)

    /** 특정 세력의 모든 자동 관계 삭제 */
    @Query("DELETE FROM character_relationships WHERE factionId = :factionId")
    suspend fun deleteAllByFaction(factionId: Long)

    /** 특정 세력의 자동 관계 중 지정 캐릭터가 포함된 것 조회 */
    @Query("""
        SELECT * FROM character_relationships
        WHERE factionId = :factionId
        AND (characterId1 = :characterId OR characterId2 = :characterId)
    """)
    suspend fun getFactionRelationshipsForCharacter(factionId: Long, characterId: Long): List<CharacterRelationship>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(relationships: List<CharacterRelationship>): List<Long>

    @Query("DELETE FROM character_relationships")
    suspend fun deleteAll()
}
