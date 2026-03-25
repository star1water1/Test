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

    @Query("DELETE FROM faction_memberships")
    suspend fun deleteAll()
}
