package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.novelcharacter.app.data.model.TrashSnapshot

/**
 * 휴지통 DAO.
 *
 * 정리·복원의 단위는 **삭제 작업**이다(B-1/B-14). 작업 키는
 * `COALESCE(operationId, 'row:' || id)` — 구버전 행(operationId 없음)은 자기 자신만의 작업이
 * 되어 종전과 동일하게 동작한다. 이 식은 `TrashSnapshot.operationKey`와 **같은 문자열**을
 * 만들어야 한다(어긋나면 보호 목록이 빗나가 방금 만든 백업을 태운다 — R-3가 막으려는 바로 그것).
 */
@Dao
interface TrashSnapshotDao {

    @Query("SELECT * FROM trash_snapshots ORDER BY deletedAt DESC, id DESC")
    fun getAll(): LiveData<List<TrashSnapshot>>

    @Query("SELECT * FROM trash_snapshots ORDER BY deletedAt DESC, id DESC")
    suspend fun getAllList(): List<TrashSnapshot>

    @Query("SELECT * FROM trash_snapshots WHERE id = :id")
    suspend fun getById(id: Long): TrashSnapshot?

    @Query("SELECT COUNT(*) FROM trash_snapshots")
    suspend fun count(): Int

    /**
     * 작업 단위 요약 — **오래된 작업부터**. 정리 대상 선택이 이 목록 위에서 이뤄진다.
     *
     * 작업의 시각은 그 작업이 만든 스냅샷 중 **가장 최근**(MAX)이다. MIN을 쓰면 오래 걸린
     * 대량 삭제가 만들어지자마자 기한 초과로 판정될 수 있다.
     */
    @Query(
        """SELECT COALESCE(operationId, 'row:' || id) AS opKey,
                  MAX(deletedAt) AS newestAt,
                  COUNT(*) AS itemCount
           FROM trash_snapshots
           GROUP BY opKey
           ORDER BY newestAt ASC, opKey ASC"""
    )
    suspend fun getOperationsOldestFirst(): List<TrashOperationSummary>

    /** 작업에 속한 스냅샷 전부 (정리·일괄 복원용) */
    @Query(
        """SELECT * FROM trash_snapshots
           WHERE COALESCE(operationId, 'row:' || id) = :opKey
           ORDER BY deletedAt ASC, id ASC"""
    )
    suspend fun getByOperation(opKey: String): List<TrashSnapshot>

    @Insert
    suspend fun insert(snapshot: TrashSnapshot): Long

    @Query("DELETE FROM trash_snapshots WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM trash_snapshots")
    suspend fun deleteAll()
}

/** 작업 단위 요약 행 — [TrashSnapshotDao.getOperationsOldestFirst] 결과. */
data class TrashOperationSummary(
    val opKey: String,
    val newestAt: Long,
    val itemCount: Int
)
