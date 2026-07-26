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
 *
 * ## payload를 함께 읽지 말 것
 * 캐릭터 전용 시절에는 행이 최대 30개라 `SELECT *`가 안전했다. 이제 세계관 하나를 지우면
 * 한 작업이 수백 행이고 한도는 **작업** 30건이라 테이블이 수만 행이 될 수 있다.
 * payload는 캐릭터 하나당 수십 KB짜리 JSON이므로, 목록·정리처럼 payload가 필요 없는 경로는
 * 반드시 아래 투영([TrashSnapshotSummary]/[TrashSnapshotImages])을 쓴다.
 */
@Dao
interface TrashSnapshotDao {

    /** 목록 표시용 — **payload를 읽지 않는다.** */
    @Query(
        """SELECT id, entityType, entityName, deletedAt, operationId, operationKind FROM trash_snapshots
           ORDER BY deletedAt DESC, id DESC"""
    )
    fun getAllSummaries(): LiveData<List<TrashSnapshotSummary>>

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

    /** 작업에 속한 스냅샷 전부 (일괄 복원용 — payload가 필요하다) */
    /**
     * @param opId 작업 id (구버전 행이면 null), @param legacyId 구버전 행의 id (아니면 -1).
     *
     * `COALESCE(operationId, 'row:'||id) = :key` 형태로 쓰면 **인덱스를 탈 수 없다**(비-sargable).
     * 행 수 상한이 '작업 30건'으로 바뀌어 테이블이 수만 행이 될 수 있으므로,
     * 삭제마다 도는 정리가 매번 전 테이블을 훑지 않도록 열 비교로 쪼갠다.
     * 호출부는 [operationLookup]으로 두 인자를 만든다.
     */
    @Query(
        """SELECT * FROM trash_snapshots
           WHERE (:opId IS NOT NULL AND operationId = :opId)
              OR (:opId IS NULL AND operationId IS NULL AND id = :legacyId)
           ORDER BY deletedAt ASC, id ASC"""
    )
    suspend fun getByOperation(opId: String?, legacyId: Long): List<TrashSnapshot>

    // ── 정리용 투영 (payload 제외) ───────────────────────────────────────────

    @Query("SELECT id, imagePaths FROM trash_snapshots WHERE id = :id")
    suspend fun getImagesById(id: Long): TrashSnapshotImages?

    /**
     * 한 작업의 이미지 보류분 — **종류까지 맞는 행만**.
     *
     * 한 작업이 삭제와 편집 직전 백업을 함께 만드는 경로가 있으므로(엑셀 임포트), 종류를
     * 거르지 않으면 삭제 묶음의 '영구 삭제'가 같은 임포트의 편집 백업까지 함께 태운다 —
     * 사용자가 지운 적 없는 항목의 되돌리기 경로가 조용히 사라진다.
     */
    @Query(
        """SELECT id, imagePaths FROM trash_snapshots
           WHERE ((:opId IS NOT NULL AND operationId = :opId)
              OR (:opId IS NULL AND operationId IS NULL AND id = :legacyId))
             AND (CASE WHEN :editBackup THEN operationKind = 'edit_backup'
                       ELSE operationKind IS NULL OR operationKind != 'edit_backup' END)"""
    )
    suspend fun getImagesByOperation(
        opId: String?,
        legacyId: Long,
        editBackup: Boolean
    ): List<TrashSnapshotImages>

    /** 보류 이미지 경로 전체 — 보호 집합 계산·저장소 리포트용. payload를 읽지 않는다. */
    @Query("SELECT id, imagePaths FROM trash_snapshots WHERE imagePaths != '' AND imagePaths != '[]'")
    suspend fun getAllImages(): List<TrashSnapshotImages>

    @Insert
    suspend fun insert(snapshot: TrashSnapshot): Long

    @Query("DELETE FROM trash_snapshots WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 호출부에서 900개 단위로 청크할 것 (SQLite 999-변수 상한). */
    @Query("DELETE FROM trash_snapshots WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM trash_snapshots")
    suspend fun deleteAll()
}

/** 작업 단위 요약 행 — [TrashSnapshotDao.getOperationsOldestFirst] 결과. */
data class TrashOperationSummary(
    val opKey: String,
    val newestAt: Long,
    val itemCount: Int
)

/**
 * 목록 표시에 필요한 최소 열 — payload를 뺀 투영.
 * 휴지통 화면은 이름·타입·시각·묶음만 있으면 되고, payload는 복원할 때만 필요하다.
 */
data class TrashSnapshotSummary(
    val id: Long,
    val entityType: String,
    val entityName: String,
    val deletedAt: Long,
    val operationId: String?,
    val operationKind: String? = null
) {
    /** [TrashSnapshot.operationKey]와 **같은 규칙**이어야 한다. */
    val operationKey: String get() = operationId ?: TrashSnapshot.legacyOperationKey(id)

    val isEditBackup: Boolean get() = operationKind == TrashSnapshot.KIND_EDIT_BACKUP
}

/**
 * 작업 키를 sargable 한 두 인자로 쪼갠다.
 * `"row:<id>"`면 (null, id), 아니면 (opKey, -1). SQL 쪽 표현과 짝을 이룬다.
 */
fun operationLookup(opKey: String): Pair<String?, Long> {
    val legacyId = opKey.removePrefix(TrashSnapshot.LEGACY_KEY_PREFIX).toLongOrNull()
    return if (opKey.startsWith(TrashSnapshot.LEGACY_KEY_PREFIX) && legacyId != null) {
        null to legacyId
    } else {
        opKey to -1L
    }
}

/** 이미지 정리에 필요한 최소 열 — payload를 뺀 투영. */
data class TrashSnapshotImages(
    val id: Long,
    val imagePaths: String
)
