package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEventNovelCrossRef
import com.novelcharacter.app.data.model.EventNovelName

@Dao
interface TimelineDao {
    @Query("SELECT * FROM timeline_events ORDER BY year ASC, month ASC, day ASC, displayOrder ASC")
    fun getAllEvents(): LiveData<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events ORDER BY year ASC, month ASC, day ASC, displayOrder ASC")
    suspend fun getAllEventsList(): List<TimelineEvent>

    @Query("""
        SELECT te.* FROM timeline_events te
        INNER JOIN timeline_event_novel_cross_ref tenc ON te.id = tenc.eventId
        WHERE tenc.novelId = :novelId
        ORDER BY te.year ASC, te.month ASC, te.day ASC, te.displayOrder ASC
    """)
    fun getEventsByNovel(novelId: Long): LiveData<List<TimelineEvent>>

    @Query("""
        SELECT te.* FROM timeline_events te
        INNER JOIN timeline_event_novel_cross_ref tenc ON te.id = tenc.eventId
        WHERE tenc.novelId = :novelId
        ORDER BY te.year ASC, te.month ASC, te.day ASC, te.displayOrder ASC
    """)
    suspend fun getEventsByNovelList(novelId: Long): List<TimelineEvent>

    @Query("SELECT * FROM timeline_events WHERE id = :id")
    suspend fun getEventById(id: Long): TimelineEvent?

    @Query("SELECT * FROM timeline_events WHERE year BETWEEN :startYear AND :endYear ORDER BY year ASC, month ASC, day ASC, displayOrder ASC")
    fun getEventsByYearRange(startYear: Int, endYear: Int): LiveData<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events WHERE description LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY year ASC, month ASC, day ASC, displayOrder ASC")
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

    @Query("SELECT * FROM timeline_character_cross_ref")
    suspend fun getAllCrossRefs(): List<TimelineCharacterCrossRef>

    @Query("SELECT eventId FROM timeline_character_cross_ref WHERE characterId = :characterId")
    suspend fun getEventIdsForCharacter(characterId: Long): List<Long>

    @Transaction
    @Query("""
        SELECT te.* FROM timeline_events te
        INNER JOIN timeline_character_cross_ref tcr ON te.id = tcr.eventId
        WHERE tcr.characterId = :characterId
        ORDER BY te.year ASC, te.month ASC, te.day ASC, te.displayOrder ASC
    """)
    fun getEventsForCharacter(characterId: Long): LiveData<List<TimelineEvent>>

    @Query("""
        SELECT te.* FROM timeline_events te
        INNER JOIN timeline_character_cross_ref tcr ON te.id = tcr.eventId
        WHERE tcr.characterId = :characterId
        ORDER BY te.year ASC, te.month ASC, te.day ASC, te.displayOrder ASC
    """)
    suspend fun getEventsForCharacterList(characterId: Long): List<TimelineEvent>

    @Transaction
    @Query("""
        SELECT c.* FROM characters c
        INNER JOIN timeline_character_cross_ref tcr ON c.id = tcr.characterId
        WHERE tcr.eventId = :eventId
        ORDER BY c.name ASC
    """)
    suspend fun getCharactersForEvent(eventId: Long): List<com.novelcharacter.app.data.model.Character>

    @Query("SELECT * FROM timeline_events WHERE universeId = :universeId ORDER BY year ASC, month ASC, day ASC, displayOrder ASC")
    fun getEventsByUniverse(universeId: Long): LiveData<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events WHERE universeId = :universeId ORDER BY year ASC, month ASC, day ASC, displayOrder ASC")
    suspend fun getEventsByUniverseList(universeId: Long): List<TimelineEvent>

    /** 세계관 계단식 삭제용 — 사건-캐릭터/작품 연결·사건 필드값은 FK CASCADE로 함께 정리된다. */
    @Query("DELETE FROM timeline_events WHERE universeId = :universeId")
    suspend fun deleteAllByUniverse(universeId: Long)

    @Query("SELECT COUNT(*) FROM timeline_events WHERE universeId = :universeId")
    suspend fun countByUniverse(universeId: Long): Int

    @Query("SELECT * FROM timeline_events WHERE year = :year AND (:month IS NULL OR month = :month) AND (:day IS NULL OR day = :day) ORDER BY year ASC, month ASC, day ASC, displayOrder ASC")
    fun getEventsByYearMonthDay(year: Int, month: Int?, day: Int?): LiveData<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events WHERE year = :year AND description = :description LIMIT 1")
    suspend fun getEventByNaturalKey(year: Int, description: String): TimelineEvent?

    /** 엑셀 왕복 안정 식별자 매칭 — 코드 우선, 자연키는 구버전 파일 폴백 */
    @Query("SELECT * FROM timeline_events WHERE code = :code LIMIT 1")
    suspend fun getEventByCode(code: String): TimelineEvent?

    /** 휴지통 복원의 참조 재해석용 일괄 조회 (B-1). 호출부에서 900개 단위로 청크할 것. */
    @Query("SELECT * FROM timeline_events WHERE id IN (:ids)")
    suspend fun getEventsByIds(ids: List<Long>): List<TimelineEvent>

    @Query("SELECT * FROM timeline_events WHERE code IN (:codes)")
    suspend fun getEventsByCodes(codes: List<String>): List<TimelineEvent>

    // Timeline filtering
    @Transaction
    @Query("""
        SELECT te.* FROM timeline_events te
        INNER JOIN timeline_character_cross_ref tcr ON te.id = tcr.eventId
        WHERE tcr.characterId = :characterId AND te.year BETWEEN :startYear AND :endYear
        ORDER BY te.year ASC, te.month ASC, te.day ASC, te.displayOrder ASC
    """)
    fun getEventsForCharacterInRange(characterId: Long, startYear: Int, endYear: Int): LiveData<List<TimelineEvent>>

    @Query("""
        SELECT te.* FROM timeline_events te
        INNER JOIN timeline_event_novel_cross_ref tenc ON te.id = tenc.eventId
        WHERE tenc.novelId = :novelId AND te.year BETWEEN :startYear AND :endYear
        ORDER BY te.year ASC, te.month ASC, te.day ASC, te.displayOrder ASC
    """)
    fun getEventsByNovelInRange(novelId: Long, startYear: Int, endYear: Int): LiveData<List<TimelineEvent>>

    // AND 조합 필터: 소설 + 캐릭터 동시 필터
    @Transaction
    @Query("""
        SELECT DISTINCT te.* FROM timeline_events te
        INNER JOIN timeline_character_cross_ref tcr ON te.id = tcr.eventId
        INNER JOIN timeline_event_novel_cross_ref tenc ON te.id = tenc.eventId
        WHERE tcr.characterId = :characterId AND tenc.novelId = :novelId
            AND te.year BETWEEN :startYear AND :endYear
        ORDER BY te.year ASC, te.month ASC, te.day ASC, te.displayOrder ASC
    """)
    fun getEventsForCharacterAndNovelInRange(
        characterId: Long, novelId: Long, startYear: Int, endYear: Int
    ): LiveData<List<TimelineEvent>>

    // displayOrder 관련
    @Update
    suspend fun updateAll(events: List<TimelineEvent>)

    /**
     * **같은 날짜의 사건 전량** — 화면 밖(필터·창에 가린) 형제까지 포함한다.
     *
     * `displayOrder`는 같은 `(year, month, day)` 안의 타이브레이크일 뿐이라, 화면에 실린
     * 부분집합에만 새 번호를 매기면 **화면 밖 형제가 옛 번호를 든 채 남아** 번호가 겹치고
     * 순서가 부정(不定)이 된다. 재정렬 저장이 이 질의로 묶음 전량을 읽어 다시 번호를 매긴다.
     *
     * `IS`를 쓰는 것이 요점이다 — `month`·`day`는 nullable이고 `=`는 NULL에 대해 NULL을
     * 내므로 '월·일이 없는 사건' 묶음이 통째로 안 잡힌다.
     */
    @Query("""
        SELECT * FROM timeline_events
        WHERE year = :year AND month IS :month AND day IS :day
        ORDER BY displayOrder ASC
    """)
    suspend fun getEventsByDate(year: Int, month: Int?, day: Int?): List<TimelineEvent>

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM timeline_events")
    suspend fun getNextDisplayOrder(): Int

    // 사건 밀도 조회: 연도별 사건 수
    @Query("SELECT year, COUNT(*) as count FROM timeline_events GROUP BY year ORDER BY year ASC")
    suspend fun getEventDensity(): List<YearCount>

    // 통계용 쿼리
    @Query("SELECT COUNT(*) FROM timeline_events")
    suspend fun getEventCount(): Int

    @Query("SELECT COUNT(*) FROM timeline_event_novel_cross_ref WHERE novelId = :novelId")
    suspend fun getEventCountByNovel(novelId: Long): Int

    @Query("""
        SELECT COUNT(DISTINCT tcr.characterId) FROM timeline_character_cross_ref tcr
        INNER JOIN timeline_events te ON tcr.eventId = te.id
        WHERE te.id = :eventId
    """)
    suspend fun getCharacterCountForEvent(eventId: Long): Int

    /** 일괄 삭제 영향 요약용 — 선택 캐릭터의 사건 연계(사건-캐릭터 교차) 수. */
    @Query("SELECT COUNT(*) FROM timeline_character_cross_ref WHERE characterId IN (:ids)")
    suspend fun countEventLinksForCharacters(ids: List<Long>): Int

    @Query("DELETE FROM timeline_events")
    suspend fun deleteAllEvents()

    @Query("DELETE FROM timeline_character_cross_ref")
    suspend fun deleteAllCrossRefs()

    // ===== Timeline-Novel cross-ref (다대다: 사건 ↔ 작품) =====
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventNovelCrossRef(crossRef: TimelineEventNovelCrossRef)

    @Delete
    suspend fun deleteEventNovelCrossRef(crossRef: TimelineEventNovelCrossRef)

    @Query("DELETE FROM timeline_event_novel_cross_ref WHERE eventId = :eventId")
    suspend fun deleteEventNovelCrossRefsByEvent(eventId: Long)

    @Query("SELECT novelId FROM timeline_event_novel_cross_ref WHERE eventId = :eventId")
    suspend fun getNovelIdsForEvent(eventId: Long): List<Long>

    @Query("SELECT eventId FROM timeline_event_novel_cross_ref WHERE novelId = :novelId")
    suspend fun getEventIdsByNovel(novelId: Long): List<Long>

    @Query("SELECT * FROM timeline_event_novel_cross_ref")
    suspend fun getAllEventNovelCrossRefs(): List<TimelineEventNovelCrossRef>

    @Transaction
    suspend fun replaceEventNovels(eventId: Long, novelIds: List<Long>) {
        deleteEventNovelCrossRefsByEvent(eventId)
        novelIds.forEach { novelId ->
            insertEventNovelCrossRef(TimelineEventNovelCrossRef(eventId, novelId))
        }
    }

    // 사건별 연결 작품명 일괄 조회 (N+1 방지)
    @Query("""
        SELECT tenc.eventId, n.title FROM timeline_event_novel_cross_ref tenc
        INNER JOIN novels n ON tenc.novelId = n.id
    """)
    suspend fun getAllEventNovelNames(): List<EventNovelName>

    @Query("DELETE FROM timeline_event_novel_cross_ref")
    suspend fun deleteAllEventNovelCrossRefs()

    @Query("SELECT id FROM timeline_events")
    suspend fun getAllEventIds(): List<Long>

    /**
     * 여러 사건의 캐릭터 크로스레프 일괄 조회 — 월드패키지 내보내기의 범위 질의(R-54 통로 필수).
     * 종전에는 전량을 올린 뒤 `events.any { it.id == cr.eventId }`로 걸러 **곱**이었다.
     */
    @Query("SELECT * FROM timeline_character_cross_ref WHERE eventId IN (:eventIds)")
    suspend fun getCrossRefsByEventIds(eventIds: List<Long>): List<TimelineCharacterCrossRef>

    /**
     * **여러 사건의 등장 캐릭터를 한 번에** — 연표 카드가 바인딩마다 치던 질의의 통로.
     *
     * 종전에는 카드가 그려질 때마다 [getCharactersForEvent]를 코루틴으로 띄웠다. 목록을
     * 한 번 훑는 것만으로 질의가 카드 수만큼 나고, 되돌아오면 같은 카드에 또 난다 —
     * 사건이 수백 건인 연표에서 스크롤이 그대로 느려지는 자리다('받쳐주는 확장성').
     *
     * 정렬은 [getCharactersForEvent]와 **글자 그대로 같아야 한다** — 두 경로가 같은 카드에
     * 다른 차례를 그리면 그것이 곧 다른 화면이다. 999-변수 상한은 [SqlInChunks]가 든다.
     */
    @Query("""
        SELECT tcr.eventId AS eventId, c.* FROM characters c
        INNER JOIN timeline_character_cross_ref tcr ON c.id = tcr.characterId
        WHERE tcr.eventId IN (:eventIds)
        ORDER BY c.name ASC
    """)
    suspend fun getCharactersForEvents(eventIds: List<Long>): List<EventCharacterRow>

    /** [getCharactersForEvents]의 한 행 — 사건 id를 캐릭터에 붙여 돌려준다. */
    data class EventCharacterRow(
        val eventId: Long,
        @androidx.room.Embedded val character: com.novelcharacter.app.data.model.Character
    )

    /** 여러 작품의 사건 크로스레프 — 내보내기가 '작품에 걸린 사건'을 찾는 통로(R-54 통로 필수). */
    @Query("SELECT * FROM timeline_event_novel_cross_ref WHERE novelId IN (:novelIds)")
    suspend fun getEventNovelCrossRefsByNovelIds(novelIds: List<Long>): List<TimelineEventNovelCrossRef>

    /** 여러 사건의 작품 크로스레프 — 짝은 [getEventNovelCrossRefsByNovelIds](R-54 통로 필수). */
    @Query("SELECT * FROM timeline_event_novel_cross_ref WHERE eventId IN (:eventIds)")
    suspend fun getEventNovelCrossRefsByEventIds(eventIds: List<Long>): List<TimelineEventNovelCrossRef>
}

data class YearCount(
    val year: Int,
    val count: Int
)
