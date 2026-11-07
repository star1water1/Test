package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.RecentActivity

@Dao
interface RecentActivityDao {
    /**
     * 최근 활동 — **제목은 원본 테이블의 지금 이름**으로 읽는다.
     *
     * `title` 열은 목록에서 눌렀던 순간의 이름을 적어 둔 것이라, 편집기·가져오기·AI 등
     * 다른 경로로 이름이 바뀌면 홈·위젯이 옛 이름을 그대로 보였다(S1 에뮬레이터 점검에서 발견).
     * 쓰는 자리를 모두 쫓아 갱신하는 대신 읽을 때 원본을 본다 — 원본 행이 없을 때만 적어 둔
     * 제목으로 물러선다. 원본 테이블을 질의가 짚으므로 LiveData도 이름 변경에 다시 울린다.
     */
    @Query(LIVE_TITLE_SELECT + " ORDER BY ra.accessedAt DESC LIMIT :limit")
    fun getRecentActivities(limit: Int = 10): LiveData<List<RecentActivity>>

    @Query(LIVE_TITLE_SELECT + " ORDER BY ra.accessedAt DESC LIMIT :limit")
    suspend fun getRecentActivitiesList(limit: Int = 10): List<RecentActivity>

    companion object {
        const val LIVE_TITLE_SELECT =
            "SELECT ra.id AS id, ra.entityType AS entityType, ra.entityId AS entityId, " +
                "COALESCE(CASE ra.entityType " +
                "WHEN 'character' THEN (SELECT c.name FROM characters c WHERE c.id = ra.entityId) " +
                "WHEN 'novel' THEN (SELECT n.title FROM novels n WHERE n.id = ra.entityId) " +
                "WHEN 'universe' THEN (SELECT u.name FROM universes u WHERE u.id = ra.entityId) " +
                "END, ra.title) AS title, " +
                "ra.accessedAt AS accessedAt FROM recent_activities ra"
    }

    /**
     * Upsert: if (entityType, entityId) already exists, update title and accessedAt.
     * Otherwise insert new row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(activity: RecentActivity): Long

    @Query("DELETE FROM recent_activities WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteByEntity(entityType: String, entityId: Long)

    @Query("SELECT COUNT(*) FROM recent_activities")
    suspend fun getCount(): Int

    /**
     * Trim to keep only the most recent N entries.
     */
    @Query("DELETE FROM recent_activities WHERE id NOT IN (SELECT id FROM recent_activities ORDER BY accessedAt DESC LIMIT :maxEntries)")
    suspend fun trimToMax(maxEntries: Int = 10)

    /** 여러 엔티티의 최근 활동 일괄 삭제 (배치 삭제용) */
    @Query("DELETE FROM recent_activities WHERE entityType = :entityType AND entityId IN (:entityIds)")
    suspend fun deleteByEntityIds(entityType: String, entityIds: List<Long>)

    @Query("DELETE FROM recent_activities")
    suspend fun deleteAll()
}
