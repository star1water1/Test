package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.Faction

@Dao
interface FactionDao {
    @Query("SELECT * FROM factions WHERE universeId = :universeId ORDER BY displayOrder ASC, createdAt DESC")
    fun getFactionsByUniverse(universeId: Long): LiveData<List<Faction>>

    @Query("SELECT * FROM factions WHERE universeId = :universeId ORDER BY displayOrder ASC, createdAt DESC")
    suspend fun getFactionsByUniverseList(universeId: Long): List<Faction>

    @Query("SELECT * FROM factions ORDER BY displayOrder ASC, createdAt DESC")
    suspend fun getAllFactionsList(): List<Faction>

    @Query("SELECT * FROM factions WHERE id = :id")
    suspend fun getById(id: Long): Faction?

    @Query("SELECT * FROM factions WHERE code = :code")
    suspend fun getByCode(code: String): Faction?

    @Query("SELECT * FROM factions WHERE name = :name AND universeId = :universeId LIMIT 1")
    suspend fun getByNameAndUniverse(name: String, universeId: Long): Faction?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(faction: Faction): Long

    @Update
    suspend fun update(faction: Faction)

    @Delete
    suspend fun delete(faction: Faction)

    @Update
    suspend fun updateAll(factions: List<Faction>)

    /**
     * 순서만 바꾼다 — **엔티티 통짜를 되쓰지 않는다** (필드 정의 쪽과 같은 처방).
     *
     * `@Update`로 통짜를 되쓰면, 화면이 목록을 든 사이 같은 행이 다른 경로에서 바뀐 것이
     * **되감긴다.** 세력 관리 화면은 목록을 어댑터에 들고 있다가 드래그가 끝난 뒤에야
     * 저장하므로, 그사이 세력 편집 다이얼로그가 고친 이름·색·설명이 옛 값으로 돌아갔다.
     */
    @Query("UPDATE factions SET displayOrder = :order WHERE id = :id")
    suspend fun setDisplayOrder(id: Long, order: Int)

    @Query("DELETE FROM factions")
    suspend fun deleteAll()

    @Query("SELECT id FROM factions")
    suspend fun getAllFactionIds(): List<Long>

    @Query("DELETE FROM factions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 휴지통 복원의 참조 재해석용 일괄 조회 (B-1). 호출부에서 900개 단위로 청크할 것. */
    @Query("SELECT * FROM factions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Faction>

    @Query("SELECT * FROM factions WHERE code IN (:codes)")
    suspend fun getByCodes(codes: List<String>): List<Faction>
}
