package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.novelcharacter.app.data.model.OperationLog

@Dao
interface OperationLogDao {

    @Query("SELECT * FROM operation_logs ORDER BY createdAt DESC LIMIT :limit")
    fun getRecent(limit: Int = OperationLogConstants.DEFAULT_LIMIT): LiveData<List<OperationLog>>

    @Query("SELECT * FROM operation_logs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentList(limit: Int = OperationLogConstants.DEFAULT_LIMIT): List<OperationLog>

    @Insert
    suspend fun insert(log: OperationLog): Long

    @Query("SELECT COUNT(*) FROM operation_logs")
    suspend fun getCount(): Int

    /** 상한 초과분(오래된 것) 정리 — insert 후 호출 */
    @Query("DELETE FROM operation_logs WHERE id NOT IN (SELECT id FROM operation_logs ORDER BY createdAt DESC LIMIT :maxEntries)")
    suspend fun trimToMax(maxEntries: Int)

    @Query("DELETE FROM operation_logs")
    suspend fun clear()
}

object OperationLogConstants {
    const val DEFAULT_LIMIT = 200
}
