package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable, app-owned idempotency key. The AI cannot choose this key. */
@Entity(tableName = "natural_batch_operations", indices = [
    Index(value = ["operationKey"], unique = true), Index("executionId")
])
data class NaturalBatchAppliedOperation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationKey: String,
    val executionId: String,
    val sessionId: String,
    val scopeRevision: Long,
    val inputRevision: Long,
    val operationId: String,
    val characterId: Long,
    val characterNovelId: Long?,
    val fieldId: Long?,
    val fieldUniverseId: Long?,
    val fieldKey: String,
    val fieldType: String,
    val fieldConfig: String,
    val undone: Boolean = false,
    val appliedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'field'") val entityKind: String = "field",
    /** Stable endpoint/scope identities for relationship records; legacy field metadata stays above. */
    val guardJson: String? = null
)
