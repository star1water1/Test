package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.CharacterDao
import com.novelcharacter.app.data.dao.NovelDao
import com.novelcharacter.app.data.dao.TimelineDao
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent

class AppRepository(
    private val novelDao: NovelDao,
    private val characterDao: CharacterDao,
    private val timelineDao: TimelineDao
) {
    // ===== Novel =====
    val allNovels: LiveData<List<Novel>> = novelDao.getAllNovels()

    suspend fun getAllNovelsList(): List<Novel> = novelDao.getAllNovelsList()
    suspend fun getNovelById(id: Long): Novel? = novelDao.getNovelById(id)
    suspend fun insertNovel(novel: Novel): Long = novelDao.insert(novel)
    suspend fun updateNovel(novel: Novel) = novelDao.update(novel)
    suspend fun deleteNovel(novel: Novel) = novelDao.delete(novel)

    // ===== Character =====
    val allCharacters: LiveData<List<Character>> = characterDao.getAllCharacters()

    suspend fun getAllCharactersList(): List<Character> = characterDao.getAllCharactersList()
    fun getCharactersByNovel(novelId: Long): LiveData<List<Character>> =
        characterDao.getCharactersByNovel(novelId)
    suspend fun getCharactersByNovelList(novelId: Long): List<Character> =
        characterDao.getCharactersByNovelList(novelId)
    suspend fun getCharacterById(id: Long): Character? = characterDao.getCharacterById(id)
    fun getCharacterByIdLive(id: Long): LiveData<Character?> = characterDao.getCharacterByIdLive(id)
    suspend fun getCharactersByBirthday(monthDay: String): List<Character> =
        characterDao.getCharactersByBirthday(monthDay)
    fun searchCharacters(query: String): LiveData<List<Character>> =
        characterDao.searchCharacters(query)
    suspend fun insertCharacter(character: Character): Long = characterDao.insert(character)
    suspend fun updateCharacter(character: Character) = characterDao.update(character)
    suspend fun deleteCharacter(character: Character) = characterDao.delete(character)
    suspend fun insertAllCharacters(characters: List<Character>) = characterDao.insertAll(characters)

    // ===== Timeline =====
    val allEvents: LiveData<List<TimelineEvent>> = timelineDao.getAllEvents()

    suspend fun getAllEventsList(): List<TimelineEvent> = timelineDao.getAllEventsList()
    fun getEventsByNovel(novelId: Long): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByNovel(novelId)
    suspend fun getEventById(id: Long): TimelineEvent? = timelineDao.getEventById(id)
    fun getEventsByYearRange(startYear: Int, endYear: Int): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByYearRange(startYear, endYear)
    fun searchEvents(query: String): LiveData<List<TimelineEvent>> =
        timelineDao.searchEvents(query)
    suspend fun insertEvent(event: TimelineEvent): Long = timelineDao.insert(event)
    suspend fun updateEvent(event: TimelineEvent) = timelineDao.update(event)
    suspend fun deleteEvent(event: TimelineEvent) = timelineDao.delete(event)
    suspend fun insertAllEvents(events: List<TimelineEvent>) = timelineDao.insertAll(events)

    // ===== Timeline-Character 연결 =====
    suspend fun linkCharacterToEvent(eventId: Long, characterId: Long) {
        timelineDao.insertCrossRef(TimelineCharacterCrossRef(eventId, characterId))
    }

    suspend fun unlinkCharacterFromEvent(eventId: Long, characterId: Long) {
        timelineDao.deleteCrossRef(TimelineCharacterCrossRef(eventId, characterId))
    }

    suspend fun updateEventCharacters(eventId: Long, characterIds: List<Long>) {
        timelineDao.deleteCrossRefsByEvent(eventId)
        characterIds.forEach { characterId ->
            timelineDao.insertCrossRef(TimelineCharacterCrossRef(eventId, characterId))
        }
    }

    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        timelineDao.getCharacterIdsForEvent(eventId)

    fun getEventsForCharacter(characterId: Long): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsForCharacter(characterId)

    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        timelineDao.getCharactersForEvent(eventId)
}
