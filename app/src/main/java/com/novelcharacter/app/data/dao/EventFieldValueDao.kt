package com.novelcharacter.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.novelcharacter.app.data.model.EventFieldValue

@Dao
interface EventFieldValueDao {

    @Query("SELECT * FROM event_field_values WHERE eventId = :eventId")
    suspend fun getValuesByEventList(eventId: Long): List<EventFieldValue>

    @Query("SELECT * FROM event_field_values")
    suspend fun getAllValuesList(): List<EventFieldValue>

    /**
     * 여러 사건의 필드값 일괄 조회 (연표 카드 표시 — B-5).
     * 호출부에서 900개 단위로 청크할 것 (SQLite 999-변수 상한).
     * 전량 조회(`getAllValuesList`)를 쓰면 화면에 보이는 사건 수와 무관하게 테이블 전체를 읽는다.
     */
    @Query("SELECT * FROM event_field_values WHERE eventId IN (:eventIds)")
    suspend fun getValuesByEvents(eventIds: List<Long>): List<EventFieldValue>

    /** 필드별 전체 값 (값 라이브러리 usageCount 재계산·전파용) */
    @Query("SELECT * FROM event_field_values WHERE fieldDefinitionId = :fieldDefId")
    suspend fun getValuesByFieldDef(fieldDefId: Long): List<EventFieldValue>

    /**
     * 여러 필드 정의의 값 일괄 조회 (휴지통 세계관 스냅샷용 — B-1).
     * 호출부에서 900개 단위로 청크할 것 (SQLite 999-변수 상한).
     */
    @Query("SELECT * FROM event_field_values WHERE fieldDefinitionId IN (:fieldDefIds)")
    suspend fun getValuesByFieldDefs(fieldDefIds: List<Long>): List<EventFieldValue>

    /**
     * 이 세계관 필드를 가리키지만 **그 세계관에 속하지 않은** 사건의 값 (휴지통 세계관 스냅샷 — B-1).
     * 사유는 `CharacterFieldValueDao.getOrphanValuesForUniverseFields`와 같다.
     */
    @Query(
        """SELECT * FROM event_field_values
           WHERE fieldDefinitionId IN (:fieldDefIds)
             AND eventId NOT IN (SELECT id FROM timeline_events WHERE universeId = :universeId)"""
    )
    suspend fun getOrphanValuesForUniverseFields(
        fieldDefIds: List<Long>,
        universeId: Long
    ): List<EventFieldValue>

    /** 사건 하나의 필드값 중 특정 정의의 값 (휴지통 복원 충돌 확인용) */
    @Query("SELECT * FROM event_field_values WHERE eventId = :eventId AND fieldDefinitionId = :fieldDefId")
    suspend fun getValue(eventId: Long, fieldDefId: Long): EventFieldValue?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(values: List<EventFieldValue>)

    @androidx.room.Update
    suspend fun update(value: EventFieldValue)

    @androidx.room.Delete
    suspend fun delete(value: EventFieldValue)

    // replaceAllByEvent/deleteAllByEvent(사건 단위 전멸)는 S-6에서 제거했다 — 마지막 호출부였던
    // 편집 저장이 replaceForFields(커버 범위 교체)로 옮겨 갔고, 남겨두면 "폼이 전체 진실"이라는
    // 잘못된 가정의 재도입 지점이 된다. 사건 삭제 시 값 정리는 FK CASCADE가 맡는다.

    @Query("DELETE FROM event_field_values WHERE eventId = :eventId AND fieldDefinitionId IN (:fieldIds)")
    suspend fun deleteByEventAndFields(eventId: Long, fieldIds: List<Long>)

    /**
     * **커버 집합 범위의 필드값 교체** — 엑셀 가져오기(F1-A: 시트에 실제로 존재한 필드 열)와
     * 사건 편집 저장(S-6: 폼이 실제로 렌더한 필드, `EventFieldValueMerge`)의 공용 경로.
     * 사건 단위 전량 교체는 폼/시트가 모르는 필드값까지 지우므로 쓰지 않는다(S-6에서 제거).
     *
     * 계약: fieldIds 안의 기존 값은 삭제 후 values로 대체되고, fieldIds 밖의 기존 값은 보존된다.
     * fieldIds 밖의 values는 REPLACE 삽입이 (eventId, fieldDefinitionId) 유니크 행을 덮어
     * 역시 대체가 된다 — 순수 모델은 `EventFieldValueMerge.resultingValues`,
     * 일치는 `EventFieldValueDaoReplaceTest`가 고정한다.
     */
    @Transaction
    suspend fun replaceForFields(eventId: Long, fieldIds: List<Long>, values: List<EventFieldValue>) {
        // SQLite 999-변수 상한 — 커버 필드 수가 커도 삭제가 깨지지 않게 나눠 지운다(받쳐주는 확장성)
        fieldIds.chunked(SQLITE_VAR_CHUNK).forEach { deleteByEventAndFields(eventId, it) }
        if (values.isNotEmpty()) insertAll(values)
    }

    companion object {
        /** IN(...) 절 변수 개수 상한(999) 아래의 안전 청크 크기 */
        const val SQLITE_VAR_CHUNK = 900
    }

    @Query("DELETE FROM event_field_values")
    suspend fun deleteAll()
}
