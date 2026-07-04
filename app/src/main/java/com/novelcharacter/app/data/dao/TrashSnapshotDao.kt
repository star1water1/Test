package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.novelcharacter.app.data.model.TrashSnapshot

@Dao
interface TrashSnapshotDao {

    @Query("SELECT * FROM trash_snapshots ORDER BY deletedAt DESC")
    fun getAll(): LiveData<List<TrashSnapshot>>

    @Query("SELECT * FROM trash_snapshots ORDER BY deletedAt DESC")
    suspend fun getAllList(): List<TrashSnapshot>

    @Query("SELECT * FROM trash_snapshots WHERE id = :id")
    suspend fun getById(id: Long): TrashSnapshot?

    @Query("SELECT COUNT(*) FROM trash_snapshots")
    suspend fun count(): Int

    /** 보관 개수 초과분 (오래된 순) — 정리 대상 조회 */
    @Query("SELECT * FROM trash_snapshots ORDER BY deletedAt ASC LIMIT :count")
    suspend fun getOldest(count: Int): List<TrashSnapshot>

    /** 보관 기한 초과분 — 정리 대상 조회 */
    @Query("SELECT * FROM trash_snapshots WHERE deletedAt < :threshold")
    suspend fun getExpired(threshold: Long): List<TrashSnapshot>

    @Insert
    suspend fun insert(snapshot: TrashSnapshot): Long

    @Query("DELETE FROM trash_snapshots WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM trash_snapshots")
    suspend fun deleteAll()
}
