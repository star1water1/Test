package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterStateChange

@Dao
interface CharacterStateChangeDao {
    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId ORDER BY year ASC, month ASC, day ASC")
    fun getChangesByCharacter(characterId: Long): LiveData<List<CharacterStateChange>>

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId ORDER BY year ASC, month ASC, day ASC")
    suspend fun getChangesByCharacterList(characterId: Long): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND year <= :year ORDER BY year ASC, month ASC, day ASC")
    suspend fun getChangesUpToYear(characterId: Long, year: Int): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND fieldKey = :fieldKey ORDER BY year ASC")
    suspend fun getChangesByField(characterId: Long, fieldKey: String): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes ORDER BY characterId ASC, year ASC, month ASC, day ASC")
    suspend fun getAllChangesList(): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE id = :id")
    suspend fun getChangeById(id: Long): CharacterStateChange?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(change: CharacterStateChange): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(changes: List<CharacterStateChange>)

    @Update
    suspend fun update(change: CharacterStateChange)

    @Delete
    suspend fun delete(change: CharacterStateChange)

    @Query("DELETE FROM character_state_changes WHERE characterId = :characterId")
    suspend fun deleteAllByCharacter(characterId: Long)

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND year = :year AND fieldKey = :fieldKey AND newValue = :newValue LIMIT 1")
    suspend fun getChangeByNaturalKey(characterId: Long, year: Int, fieldKey: String, newValue: String): CharacterStateChange?

    @Query("SELECT * FROM character_state_changes WHERE fieldKey = :fieldKey AND month = :month AND day = :day")
    suspend fun getChangesByFieldAndDate(fieldKey: String, month: Int, day: Int): List<CharacterStateChange>

    @Query("DELETE FROM character_state_changes WHERE fieldKey = :fieldKey")
    suspend fun deleteChangesByFieldKey(fieldKey: String)
}
