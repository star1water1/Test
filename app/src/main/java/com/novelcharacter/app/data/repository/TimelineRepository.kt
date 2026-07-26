package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.TimelineDao
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.EventNovelName
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TimelineEventNovelCrossRef

class TimelineRepository(
    private val db: AppDatabase,
    private val timelineDao: TimelineDao = db.timelineDao()
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
        timelineDao.searchEvents(sanitizeLikeQuery(query))
    suspend fun insertEvent(event: TimelineEvent): Long = timelineDao.insert(event)
    suspend fun updateEvent(event: TimelineEvent) = timelineDao.update(event)
    /**
     * 사건 삭제 — 삭제 전 휴지통 스냅샷을 남긴다 (B-1).
     *
     * 사건이 죽으면 사건 필드값·참가 캐릭터 연결·작품 연결이 FK CASCADE로 죽고,
     * **관계 변화 이력의 사건 연결은 SET_NULL로 조용히 끊긴다.** 종전에는 이 전부가
     * 무통보 영구 삭제였다(연표 롱프레스에는 확인 다이얼로그조차 없다).
     *
     * 스냅샷과 삭제를 한 트랜잭션으로 묶는다 — 삭제만 커밋되고 스냅샷이 날아가면
     * 그 자체가 무통보 유실이다. 정리(pruneIfNeeded)는 커밋 이후에 수행한다
     * (롤백 시 스냅샷 이미지가 지워지는 것 방지 — 저장소 공통 관례).
     */
    suspend fun deleteEvent(event: TimelineEvent) {
        val trash = TrashRepository(db)
        db.withTransaction {
            trash.snapshotEvent(event)
            timelineDao.delete(event)
        }
        trash.pruneIfNeeded()
    }
    suspend fun insertAllEvents(events: List<TimelineEvent>) = timelineDao.insertAll(events)
    fun getEventsByUniverse(universeId: Long): LiveData<List<TimelineEvent>> =
        timelineDao.getEventsByUniverse(universeId)
    suspend fun getEventsByUniverseList(universeId: Long): List<TimelineEvent> =
        timelineDao.getEventsByUniverseList(universeId)
    suspend fun updateAllEvents(events: List<TimelineEvent>) =
        timelineDao.updateAll(events)
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

    // ===== Timeline-Novel linkage (다대다: 사건 ↔ 작품) =====
    suspend fun getNovelIdsForEvent(eventId: Long): List<Long> =
        timelineDao.getNovelIdsForEvent(eventId)

    suspend fun getEventIdsByNovel(novelId: Long): List<Long> =
        timelineDao.getEventIdsByNovel(novelId)

    suspend fun getEventIdsForCharacter(characterId: Long): List<Long> =
        timelineDao.getEventIdsForCharacter(characterId)

    suspend fun updateEventNovels(eventId: Long, novelIds: List<Long>) =
        timelineDao.replaceEventNovels(eventId, novelIds)

    suspend fun getAllEventNovelCrossRefs(): List<TimelineEventNovelCrossRef> =
        timelineDao.getAllEventNovelCrossRefs()

    suspend fun getAllEventNovelNames(): List<EventNovelName> =
        timelineDao.getAllEventNovelNames()

    suspend fun getEventByNaturalKey(year: Int, description: String): TimelineEvent? =
        timelineDao.getEventByNaturalKey(year, description)
}
