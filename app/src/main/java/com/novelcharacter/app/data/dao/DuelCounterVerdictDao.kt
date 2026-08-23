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

    /**
     * [getByAxis]의 **일괄 짝** — 짝인 `DuelMatchDao.getByAxes`와 같은 이유·같은 계약이다
     * (R-54 통로 필수 · 순서는 `WorldPackageDuels.toPortable`이 다시 세운다).
     */
    @Query("SELECT * FROM duel_counter_verdicts WHERE axisId IN (:axisIds) ORDER BY decidedAt ASC, id ASC")
    suspend fun getByAxes(axisIds: List<Long>): List<DuelCounterVerdict>

    @Query("SELECT * FROM duel_counter_verdicts WHERE axisId = :axisId AND memberKey = :memberKey LIMIT 1")
    suspend fun getByMemberKey(axisId: Long, memberKey: String): DuelCounterVerdict?

    @Query("SELECT * FROM duel_counter_verdicts WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): DuelCounterVerdict?

    /**
     * 표 전체 — 가져오기가 **행마다** [getByCode]·[getByMemberKey]를 치던 자리를 한 번 읽기로
     * 바꾸려고 둔다 (B-210). 화면에서 쓰지 말 것 — 축 단위로 받는 [getByAxis]가 그쪽의 자리다.
     */
    @Query("SELECT * FROM duel_counter_verdicts")
    suspend fun getAllList(): List<DuelCounterVerdict>

    /**
     * 주어진 코드 중 **이미 쓰이고 있는 것**만 — 월드패키지 가져오기의 코드 충돌 판정
     * ([com.novelcharacter.app.share.WorldPackageCodes.Registry])이 쓴다.
     *
     * 표를 전량 읽지 않는 것이 요점이다: 알아야 하는 것은 *이 패키지가 원하는 코드가 겹치는가*
     * 하나이므로 비용이 **패키지 크기**에 붙고 기기에 쌓인 양과 무관해진다(형제 표 셋이 같은
     * 규약을 쓴다 — `DuelMatchDao.getExistingCodes`가 그중 가장 큰 자리다).
     */
    @Query("SELECT code FROM duel_counter_verdicts WHERE code IN (:codes)")
    suspend fun getExistingCodes(codes: List<String>): List<String>

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
