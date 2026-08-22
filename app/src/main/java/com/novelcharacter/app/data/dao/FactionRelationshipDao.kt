package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.novelcharacter.app.data.model.FactionRelationship

@Dao
interface FactionRelationshipDao {

    /** 특정 세력이 어느 쪽으로든 참여한 관계 전부 */
    @Query("SELECT * FROM faction_relationships WHERE factionId1 = :factionId OR factionId2 = :factionId ORDER BY displayOrder, createdAt")
    suspend fun getRelationshipsForFactionList(factionId: Long): List<FactionRelationship>

    @Query("SELECT * FROM faction_relationships WHERE factionId1 = :factionId OR factionId2 = :factionId ORDER BY displayOrder, createdAt")
    fun getRelationshipsForFaction(factionId: Long): LiveData<List<FactionRelationship>>

    /** 삭제 영향 고지용 — 이 세력이 한쪽으로 참여한 세력 간 관계 수(COUNT로 센다 — R-54). */
    @Query("SELECT COUNT(*) FROM faction_relationships WHERE factionId1 = :factionId OR factionId2 = :factionId")
    suspend fun countForFaction(factionId: Long): Int

    /** 세계관 내 모든 세력 간 관계 (그래프/엑셀용) */
    @Query(
        """SELECT fr.* FROM faction_relationships fr
           INNER JOIN factions f1 ON fr.factionId1 = f1.id
           WHERE f1.universeId = :universeId
           ORDER BY fr.displayOrder, fr.createdAt"""
    )
    suspend fun getRelationshipsByUniverseList(universeId: Long): List<FactionRelationship>

    @Query("SELECT * FROM faction_relationships ORDER BY displayOrder, createdAt")
    suspend fun getAllRelationshipsList(): List<FactionRelationship>

    /**
     * 한쪽 끝(factionId1)이 목록 안인 관계 — 월드패키지 내보내기의 범위 질의. 짝은 [getRelationshipsByEnd2].
     *
     * **끝마다 따로 묻는 이유는 바인드 상한이다** (R-54). `factionId1 IN (:ids) OR factionId2 IN (:ids)`는
     * Room이 목록을 **두 번** 펼쳐 변수를 `2 × 길이`만큼 쓰므로 조각 상한(900)이 1,800이 되어
     * API 31 미만의 기본 상한(999)을 넘긴다 — 캐릭터 관계가 2026.08.22까지 그 모양이었다
     * (`CharacterRelationshipDao.getRelationshipIdsByEnd1`이 같은 이유로 갈린 짝이다).
     * 두 끝이 서로 다른 조각에 나뉘어도 같은 행이 나오므로 **호출부가 id로 겹을 없앤다.**
     */
    @Query("SELECT * FROM faction_relationships WHERE factionId1 IN (:factionIds)")
    suspend fun getRelationshipsByEnd1(factionIds: List<Long>): List<FactionRelationship>

    /** 다른 쪽 끝(factionId2)이 목록 안인 관계 — 짝은 [getRelationshipsByEnd1]. */
    @Query("SELECT * FROM faction_relationships WHERE factionId2 IN (:factionIds)")
    suspend fun getRelationshipsByEnd2(factionIds: List<Long>): List<FactionRelationship>

    @Query("SELECT * FROM faction_relationships WHERE id = :id")
    suspend fun getById(id: Long): FactionRelationship?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(relationship: FactionRelationship): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(relationships: List<FactionRelationship>): List<Long>

    @Update
    suspend fun update(relationship: FactionRelationship)

    @Delete
    suspend fun delete(relationship: FactionRelationship)

    @Query("DELETE FROM faction_relationships WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM faction_relationships")
    suspend fun deleteAll()

    @Query("SELECT id FROM faction_relationships")
    suspend fun getAllRelationshipIds(): List<Long>
}
