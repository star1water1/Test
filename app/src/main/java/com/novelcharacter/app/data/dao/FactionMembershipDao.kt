package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.FactionMembership

@Dao
interface FactionMembershipDao {
    @Query("SELECT * FROM faction_memberships WHERE factionId = :factionId ORDER BY createdAt DESC")
    fun getMembershipsByFaction(factionId: Long): LiveData<List<FactionMembership>>

    @Query("SELECT * FROM faction_memberships WHERE factionId = :factionId ORDER BY createdAt DESC")
    suspend fun getMembershipsByFactionList(factionId: Long): List<FactionMembership>

    /** 활성 멤버십만 조회 (순수 제거되지 않은 멤버) */
    @Query("SELECT * FROM faction_memberships WHERE factionId = :factionId AND leaveType IS NULL")
    suspend fun getActiveMembershipsByFaction(factionId: Long): List<FactionMembership>

    /** 특정 캐릭터의 모든 세력 소속 조회 */
    @Query("SELECT * FROM faction_memberships WHERE characterId = :characterId ORDER BY createdAt DESC")
    fun getMembershipsByCharacter(characterId: Long): LiveData<List<FactionMembership>>

    @Query("SELECT * FROM faction_memberships WHERE characterId = :characterId ORDER BY createdAt DESC")
    suspend fun getMembershipsByCharacterList(characterId: Long): List<FactionMembership>

    /** 이미 활성 멤버십이 있는지 체크 (재가입 중복 방지) */
    @Query("SELECT * FROM faction_memberships WHERE factionId = :factionId AND characterId = :characterId AND leaveType IS NULL LIMIT 1")
    suspend fun getActiveMembership(factionId: Long, characterId: Long): FactionMembership?

    /**
     * 한 (세력, 캐릭터) 쌍의 **모든** 소속 이력 — 엑셀 가져오기의 계층 매칭용.
     * getActiveMembership은 leaveType IS NULL만 보므로 탈퇴 이력을 영원히 매칭하지 못해
     * 재가져오기마다 중복 행이 쌓인다(왕복 비멱등). 가져오기는 이 쿼리로 후보 전체를 본다.
     */
    @Query("SELECT * FROM faction_memberships WHERE factionId = :factionId AND characterId = :characterId ORDER BY createdAt ASC, id ASC")
    suspend fun getAllMembershipsForPair(factionId: Long, characterId: Long): List<FactionMembership>

    /**
     * 여러 캐릭터의 **지금 소속**을 한 번에 (B-171 — 대결 축의 `sys:faction`).
     *
     * ⚠️ **`leaveType IS NULL`은 [com.novelcharacter.app.util.FactionStanding.SQL_CURRENT]의
     * 쌍둥이다.** Room 질의는 코틀린 함수를 부를 수 없어 같은 술어가 여기 글자로 한 벌 더
     * 사는데, 갈리면 대결 카드와 통계가 다른 세력을 말한다. **그 한 벌은
     * `tools/check_faction_standing.sh`가 지킨다** — 술어를 고치면 검사가 먼저 운다.
     *
     * 이력 전량이 아니라 지금 것만 읽는 것은 비용 때문이다 — 탈퇴 줄까지 끌어오면 캐릭터
     * 수백 명 × 재가입 이력만큼이 그대로 메모리에 올라오고, 부르는 쪽은 그중 지금 것만 쓴다.
     * (그래도 부르는 쪽이 [com.novelcharacter.app.util.FactionStanding]을 다시 지나는 것은
     * 일부러다 — 이 질의가 언젠가 넓어져도 답이 흔들리지 않는다.)
     */
    @Query(
        "SELECT * FROM faction_memberships WHERE characterId IN (:characterIds) " +
            "AND leaveType IS NULL ORDER BY characterId ASC, createdAt ASC, id ASC"
    )
    suspend fun getCurrentMembershipsForCharacters(characterIds: List<Long>): List<FactionMembership>

    @Query("SELECT * FROM faction_memberships WHERE id = :id")
    suspend fun getById(id: Long): FactionMembership?

    @Query("SELECT * FROM faction_memberships")
    suspend fun getAllMembershipsList(): List<FactionMembership>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(membership: FactionMembership): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(memberships: List<FactionMembership>)

    @Update
    suspend fun update(membership: FactionMembership)

    @Delete
    suspend fun delete(membership: FactionMembership)

    @Query("DELETE FROM faction_memberships WHERE factionId = :factionId AND characterId = :characterId AND leaveType IS NULL")
    suspend fun deleteActiveMembership(factionId: Long, characterId: Long)

    /** 지정 세계관에 속하지 않는 세력의 소속 기록 삭제 (배치 작품 변경 시 사용) */
    @Query("""
        DELETE FROM faction_memberships
        WHERE characterId = :characterId
          AND factionId NOT IN (SELECT id FROM factions WHERE universeId = :universeId)
    """)
    suspend fun deleteMembershipsNotInUniverse(characterId: Long, universeId: Long)

    /** 삭제 예정(세계관 이동 시 제거될) 소속 기록 수 — 파괴 전 스냅샷 판단·고지용 */
    @Query("""
        SELECT COUNT(*) FROM faction_memberships
        WHERE characterId = :characterId
          AND factionId NOT IN (SELECT id FROM factions WHERE universeId = :universeId)
    """)
    suspend fun countMembershipsNotInUniverse(characterId: Long, universeId: Long): Int

    /** 일괄 삭제 영향 요약용 — 선택 캐릭터의 세력 소속 수. */
    @Query("SELECT COUNT(*) FROM faction_memberships WHERE characterId IN (:ids)")
    suspend fun countByCharacterIds(ids: List<Long>): Int

    @Query("DELETE FROM faction_memberships")
    suspend fun deleteAll()

    @Query("SELECT id FROM faction_memberships")
    suspend fun getAllMembershipIds(): List<Long>

    @Query("DELETE FROM faction_memberships WHERE id = :id")
    suspend fun deleteById(id: Long)
}
