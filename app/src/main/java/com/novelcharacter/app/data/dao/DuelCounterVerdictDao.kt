package com.novelcharacter.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.novelcharacter.app.data.model.DuelCounterVerdict

@Dao
interface DuelCounterVerdictDao {

    @Query("SELECT * FROM duel_counter_verdicts WHERE axisId = :axisId ORDER BY decidedAt ASC, id ASC")
    suspend fun getByAxis(axisId: Long): List<DuelCounterVerdict>

    @Query("SELECT * FROM duel_counter_verdicts WHERE axisId = :axisId AND memberKey = :memberKey LIMIT 1")
    suspend fun getByMemberKey(axisId: Long, memberKey: String): DuelCounterVerdict?

    @Query("SELECT * FROM duel_counter_verdicts WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): DuelCounterVerdict?

    /**
     * 같은 관계에 다시 판정하면 **덮어쓴다** — ②미정으로 미뤄 둔 것을 나중에 ③상성으로 굳히는
     * 것이 확정된 흐름이라(2-1 층 B), 그때 행이 둘로 늘면 어느 쪽이 현행인지 알 수 없다.
     * 유니크 인덱스가 `(axisId, memberKey)`에 서 있어 이 대체가 성립한다.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(verdict: DuelCounterVerdict): Long

    @Insert
    suspend fun insertAll(verdicts: List<DuelCounterVerdict>)

    @Update
    suspend fun update(verdict: DuelCounterVerdict)

    @Delete
    suspend fun delete(verdict: DuelCounterVerdict)
}
