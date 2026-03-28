package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.RecentActivity

@Dao
interface RecentActivityDao {
    @Query("SELECT * FROM recent_activities ORDER BY accessedAt DESC LIMIT :limit")
    fun getRecentActivities(limit: Int = 10): LiveData<List<RecentActivity>>

    @Query("SELECT * FROM recent_activities ORDER BY accessedAt DESC LIMIT :limit")
    suspend fun getRecentActivitiesList(limit: Int = 10): List<RecentActivity>

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
}
