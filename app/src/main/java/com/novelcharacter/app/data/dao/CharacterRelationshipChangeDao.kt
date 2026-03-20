package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterRelationshipChange

@Dao
interface CharacterRelationshipChangeDao {
    @Query("SELECT * FROM character_relationship_changes WHERE relationshipId = :relationshipId ORDER BY year ASC, month ASC, day ASC")
    fun getChangesForRelationship(relationshipId: Long): LiveData<List<CharacterRelationshipChange>>

    @Query("SELECT * FROM character_relationship_changes WHERE relationshipId = :relationshipId ORDER BY year ASC, month ASC, day ASC")
    suspend fun getChangesForRelationshipList(relationshipId: Long): List<CharacterRelationshipChange>

    @Query("SELECT * FROM character_relationship_changes WHERE relationshipId = :relationshipId AND year <= :year ORDER BY year DESC, month DESC, day DESC LIMIT 1")
    suspend fun getChangeAtYear(relationshipId: Long, year: Int): CharacterRelationshipChange?

    @Query("SELECT * FROM character_relationship_changes ORDER BY year ASC")
    suspend fun getAllChanges(): List<CharacterRelationshipChange>

    @Query("SELECT * FROM character_relationship_changes WHERE relationshipId = :relationshipId AND year = :year AND (month IS :month OR (month IS NULL AND :month IS NULL)) AND (day IS :day OR (day IS NULL AND :day IS NULL)) LIMIT 1")
    suspend fun getChangeByNaturalKey(relationshipId: Long, year: Int, month: Int?, day: Int?): CharacterRelationshipChange?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(change: CharacterRelationshipChange): Long

    @Update
    suspend fun update(change: CharacterRelationshipChange)

    @Delete
    suspend fun delete(change: CharacterRelationshipChange)

    @Query("DELETE FROM character_relationship_changes WHERE relationshipId = :relationshipId")
    suspend fun deleteAllByRelationship(relationshipId: Long)
}
