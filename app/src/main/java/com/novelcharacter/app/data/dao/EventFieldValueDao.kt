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

    @Query("DELETE FROM event_field_values")
    suspend fun deleteAll()
}
