package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.TimelineDao
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent

class TimelineRepository(
    private val timelineDao: TimelineDao
) {
    val allEvents: LiveData<List<TimelineEvent>> = timelineDao.getAllEvents()

    suspend fun getAllEventsList(): List<TimelineEvent> = timelineDao.getAllEventsList()
    fun getEventsByNovel(novelId: Long): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByNovel(novelId)
    suspend fun getEventsByNovelList(novelId: Long): List<TimelineEvent> =
        timelineDao.getEventsByNovelList(novelId)
    suspend fun getEventById(id: Long): TimelineEvent? = timelineDao.getEventById(id)
    fun getEventsByYearRange(startYear: Int, endYear: Int): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByYearRange(startYear, endYear)
    fun searchEvents(query: String): LiveData<List<TimelineEvent>> =
        timelineDao.searchEvents(query)
    suspend fun insertEvent(event: TimelineEvent): Long = timelineDao.insert(event)
    suspend fun updateEvent(event: TimelineEvent) = timelineDao.update(event)
    suspend fun deleteEvent(event: TimelineEvent) = timelineDao.delete(event)
    suspend fun insertAllEvents(events: List<TimelineEvent>) = timelineDao.insertAll(events)
    fun getEventsByUniverse(universeId: Long): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByUniverse(universeId)
    fun getEventsByYearMonthDay(year: Int, month: Int?, day: Int?): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByYearMonthDay(year, month, day)
    fun getEventsForCharacterInRange(characterId: Long, startYear: Int, endYear: Int): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsForCharacterInRange(characterId, startYear, endYear)
    fun getEventsByNovelInRange(novelId: Long, startYear: Int, endYear: Int): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByNovelInRange(novelId, startYear, endYear)

    fun getEventsForCharacterAndNovelInRange(
        characterId: Long, novelId: Long, startYear: Int, endYear: Int
    ): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsForCharacterAndNovelInRange(characterId, novelId, startYear, endYear)

    suspend fun getEventDensity() = timelineDao.getEventDensity()

    // ===== Timeline-Character linkage =====
    suspend fun linkCharacterToEvent(eventId: Long, characterId: Long) {
        timelineDao.insertCrossRef(TimelineCharacterCrossRef(eventId, characterId))
    }

    suspend fun unlinkCharacterFromEvent(eventId: Long, characterId: Long) {
        timelineDao.deleteCrossRef(TimelineCharacterCrossRef(eventId, characterId))
    }

    suspend fun updateEventCharacters(eventId: Long, characterIds: List<Long>) {
        timelineDao.replaceEventCharacters(eventId, characterIds)
    }

    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        timelineDao.getCharacterIdsForEvent(eventId)

    fun getEventsForCharacter(characterId: Long): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsForCharacter(characterId)

    suspend fun getEventsForCharacterList(characterId: Long): List<TimelineEvent> =
        timelineDao.getEventsForCharacterList(characterId)

    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        timelineDao.getCharactersForEvent(eventId)
}
