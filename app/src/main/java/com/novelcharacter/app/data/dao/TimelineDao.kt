package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef

@Dao
interface TimelineDao {
    @Query("SELECT * FROM timeline_events ORDER BY year ASC")
    fun getAllEvents(): LiveData<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events ORDER BY year ASC")
    suspend fun getAllEventsList(): List<TimelineEvent>

    @Query("SELECT * FROM timeline_events WHERE novelId = :novelId ORDER BY year ASC")
    fun getEventsByNovel(novelId: Long): LiveData<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events WHERE id = :id")
    suspend fun getEventById(id: Long): TimelineEvent?

    @Query("SELECT * FROM timeline_events WHERE year BETWEEN :startYear AND :endYear ORDER BY year ASC")
    fun getEventsByYearRange(startYear: Int, endYear: Int): LiveData<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events WHERE description LIKE '%' || :query || '%' ORDER BY year ASC")
    fun searchEvents(query: String): LiveData<List<TimelineEvent>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: TimelineEvent): Long

    @Update
    suspend fun update(event: TimelineEvent)

    @Delete
    suspend fun delete(event: TimelineEvent)

    @Query("DELETE FROM timeline_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(events: List<TimelineEvent>)

    // Cross-reference (연표-캐릭터 연결)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: TimelineCharacterCrossRef)

    @Delete
    suspend fun deleteCrossRef(crossRef: TimelineCharacterCrossRef)

    @Transaction
    suspend fun replaceEventCharacters(eventId: Long, characterIds: List<Long>) {
        deleteCrossRefsByEvent(eventId)
        characterIds.forEach { characterId ->
            insertCrossRef(TimelineCharacterCrossRef(eventId, characterId))
        }
    }

    @Query("DELETE FROM timeline_character_cross_ref WHERE eventId = :eventId")
    suspend fun deleteCrossRefsByEvent(eventId: Long)

    @Query("SELECT characterId FROM timeline_character_cross_ref WHERE eventId = :eventId")
    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long>

    @Query("SELECT eventId FROM timeline_character_cross_ref WHERE characterId = :characterId")
    suspend fun getEventIdsForCharacter(characterId: Long): List<Long>

    @Query("""
        SELECT te.* FROM timeline_events te
        INNER JOIN timeline_character_cross_ref tcr ON te.id = tcr.eventId
        WHERE tcr.characterId = :characterId
        ORDER BY te.year ASC
    """)
    fun getEventsForCharacter(characterId: Long): LiveData<List<TimelineEvent>>

    @Query("""
        SELECT c.* FROM characters c
        INNER JOIN timeline_character_cross_ref tcr ON c.id = tcr.characterId
        WHERE tcr.eventId = :eventId
        ORDER BY c.name ASC
    """)
    suspend fun getCharactersForEvent(eventId: Long): List<com.novelcharacter.app.data.model.Character>

    @Query("SELECT * FROM timeline_events WHERE universeId = :universeId ORDER BY year ASC")
    fun getEventsByUniverse(universeId: Long): LiveData<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events WHERE year = :year AND (:month IS NULL OR month = :month) AND (:day IS NULL OR day = :day) ORDER BY year ASC")
    fun getEventsByYearMonthDay(year: Int, month: Int?, day: Int?): LiveData<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events WHERE year = :year AND description = :description AND novelId = :novelId LIMIT 1")
    suspend fun getEventByNaturalKey(year: Int, description: String, novelId: Long): TimelineEvent?

    @Query("SELECT * FROM timeline_events WHERE year = :year AND description = :description AND novelId IS NULL LIMIT 1")
    suspend fun getEventByNaturalKeyNoNovel(year: Int, description: String): TimelineEvent?

    // Timeline filtering
    @Query("""
        SELECT te.* FROM timeline_events te
        INNER JOIN timeline_character_cross_ref tcr ON te.id = tcr.eventId
        WHERE tcr.characterId = :characterId AND te.year BETWEEN :startYear AND :endYear
        ORDER BY te.year ASC
    """)
    fun getEventsForCharacterInRange(characterId: Long, startYear: Int, endYear: Int): LiveData<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events WHERE novelId = :novelId AND year BETWEEN :startYear AND :endYear ORDER BY year ASC")
    fun getEventsByNovelInRange(novelId: Long, startYear: Int, endYear: Int): LiveData<List<TimelineEvent>>
}
