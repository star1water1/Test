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

    @Query("DELETE FROM event_field_values WHERE eventId = :eventId")
    suspend fun deleteAllByEvent(eventId: Long)

    /** 사건의 필드값 전체 교체 — 빈 값은 저장하지 않는 호출부 규약과 함께 사용 */
    @Transaction
    suspend fun replaceAllByEvent(eventId: Long, values: List<EventFieldValue>) {
        deleteAllByEvent(eventId)
        if (values.isNotEmpty()) insertAll(values)
    }

    @Query("DELETE FROM event_field_values WHERE eventId = :eventId AND fieldDefinitionId IN (:fieldIds)")
    suspend fun deleteByEventAndFields(eventId: Long, fieldIds: List<Long>)

    /**
     * 엑셀 가져오기 전용 — **시트에 실제로 존재한 필드 열만** 교체한다(F1-A 열 단위 적용).
     * replaceAllByEvent는 사건 단위 전멸이라, 사용자가 필드 열 하나만 지운 시트를 가져오면
     * 시트에 없던 다른 필드값까지 함께 사라진다.
     */
    @Transaction
    suspend fun replaceForFields(eventId: Long, fieldIds: List<Long>, values: List<EventFieldValue>) {
        if (fieldIds.isEmpty()) return
        deleteByEventAndFields(eventId, fieldIds)
        if (values.isNotEmpty()) insertAll(values)
    }

    @Query("DELETE FROM event_field_values")
    suspend fun deleteAll()
}
