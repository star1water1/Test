package com.novelcharacter.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.novelcharacter.app.data.model.DuelMatch

/**
 * 대결 기록의 DAO.
 *
 * ⚠️ **이 표가 이 앱에서 가장 커질 수 있다**(수만 행 — `docs/scalability_performance_2026-07.md` 4장).
 * 점수 적합은 원리적으로 전 판을 봐야 하므로 [getByAxis]가 통째로 읽는 것은 피할 수 없지만,
 * **축 단위로 갈려 있어** 한 번에 읽는 양은 한 축의 기록이다. 축을 넘나드는 전수 조회는 두지
 * 않는다 — 두면 화면 하나가 무심코 전체를 메모리에 올린다.
 */
@Dao
interface DuelMatchDao {

    /** 한 축의 판 전부 — 점수 적합·짝 고르기·상성 검출이 같은 목록을 본다(집계를 세 곳에서 따로 세지 않는다). */
    @Query("SELECT * FROM duel_matches WHERE axisId = :axisId ORDER BY decidedAt ASC, id ASC")
    suspend fun getByAxis(axisId: Long): List<DuelMatch>

    @Query("SELECT COUNT(*) FROM duel_matches WHERE axisId = :axisId")
    suspend fun countByAxis(axisId: Long): Int

    /** 가장 최근 판부터 — 되돌리기(층 B ①)가 "방금 그 판"을 집는 경로다. */
    @Query("SELECT * FROM duel_matches WHERE axisId = :axisId ORDER BY decidedAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(axisId: Long, limit: Int): List<DuelMatch>

    /** 한 화면(k지선다)이 낸 판들 — 되돌리기가 묶음 단위로 돈다. */
    @Query("SELECT * FROM duel_matches WHERE groupId = :groupId ORDER BY id ASC")
    suspend fun getByGroup(groupId: String): List<DuelMatch>

    @Query("SELECT * FROM duel_matches WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): DuelMatch?

    @Query("SELECT code FROM duel_matches WHERE code IN (:codes)")
    suspend fun getExistingCodes(codes: List<String>): List<String>

    /** 이 참가자가 낀 판 수 — 삭제·정리가 "무엇이 점수에서 빠지는가"를 먼저 알릴 때 쓴다(R-4). */
    @Query("SELECT COUNT(*) FROM duel_matches WHERE axisId = :axisId AND (aCode = :code OR bCode = :code)")
    suspend fun countForParticipant(axisId: Long, code: String): Int

    @Insert
    suspend fun insert(match: DuelMatch): Long

    @Insert
    suspend fun insertAll(matches: List<DuelMatch>)

    @Delete
    suspend fun delete(match: DuelMatch)

    @Query("DELETE FROM duel_matches WHERE groupId = :groupId")
    suspend fun deleteByGroup(groupId: String)
}
