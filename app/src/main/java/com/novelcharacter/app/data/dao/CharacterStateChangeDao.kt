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

    /** 생일(month/day)이 있는 상태변경 전체 — 일회성 조회 (Worker/Widget용) */
    @Query("SELECT * FROM character_state_changes WHERE fieldKey = :fieldKey AND month IS NOT NULL AND day IS NOT NULL")
    suspend fun getChangesWithDate(fieldKey: String): List<CharacterStateChange>

    /** 생일(month/day)이 있는 상태변경 전체 — 반응형 (테이블 변경 시 자동 갱신) */
    @Query("SELECT * FROM character_state_changes WHERE fieldKey = :fieldKey AND month IS NOT NULL AND day IS NOT NULL")
    fun observeChangesWithDate(fieldKey: String): LiveData<List<CharacterStateChange>>

    @Query("DELETE FROM character_state_changes WHERE fieldKey = :fieldKey")
    suspend fun deleteChangesByFieldKey(fieldKey: String)

    @Query("""
        DELETE FROM character_state_changes
        WHERE fieldKey = :fieldKey
          AND characterId IN (
              SELECT c.id FROM characters c
              INNER JOIN novels n ON c.novelId = n.id
              WHERE n.universeId = :universeId
          )
    """)
    suspend fun deleteChangesByFieldKeyAndUniverse(fieldKey: String, universeId: Long)

    /** 세계관에 속한 모든 필드의 state change 삭제 (세계관 삭제 시 사용) */
    @Query("""
        DELETE FROM character_state_changes
        WHERE fieldKey IN (
            SELECT `key` FROM field_definitions WHERE universeId = :universeId
        )
        AND characterId IN (
            SELECT c.id FROM characters c
            INNER JOIN novels n ON c.novelId = n.id
            WHERE n.universeId = :universeId
        )
    """)
    suspend fun deleteAllChangesByUniverse(universeId: Long)

    @Query("DELETE FROM character_state_changes")
    suspend fun deleteAll()

    @Query("SELECT id FROM character_state_changes")
    suspend fun getAllChangeIds(): List<Long>

    @Query("DELETE FROM character_state_changes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
