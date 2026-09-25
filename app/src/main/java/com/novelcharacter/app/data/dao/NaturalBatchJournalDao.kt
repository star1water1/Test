package com.novelcharacter.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.novelcharacter.app.data.model.NaturalBatchAppliedOperation
import com.novelcharacter.app.data.model.NaturalBatchRowChange

@Dao
interface NaturalBatchJournalDao {
    @Query("SELECT COUNT(*) FROM natural_batch_operations")
    suspend fun countOperations(): Int

    @Query("SELECT * FROM natural_batch_operations WHERE operationKey = :key")
    suspend fun operation(key: String): NaturalBatchAppliedOperation?

    @Query("SELECT operationKey FROM natural_batch_operations WHERE operationKey IN (:keys)")
    suspend fun existingKeys(keys: List<String>): List<String>

    @Query("SELECT * FROM natural_batch_operations WHERE executionId = :executionId ORDER BY id")
    suspend fun operations(executionId: String): List<NaturalBatchAppliedOperation>

    @Query("SELECT * FROM natural_batch_row_changes WHERE operationKey = :key ORDER BY id")
    suspend fun changes(key: String): List<NaturalBatchRowChange>

    @Insert suspend fun insert(operation: NaturalBatchAppliedOperation)
    @Insert suspend fun insertChanges(changes: List<NaturalBatchRowChange>)

    @Query("UPDATE natural_batch_operations SET undone = 1 WHERE operationKey = :key AND undone = 0")
    suspend fun markUndone(key: String): Int

    @Query("DELETE FROM natural_batch_operations")
    suspend fun deleteAll()
}
