package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Each changed Room row is recorded by its real ID, including semantic side effects. */
@Entity(
    tableName = "natural_batch_row_changes",
    foreignKeys = [ForeignKey(
        entity = NaturalBatchAppliedOperation::class,
        parentColumns = ["operationKey"], childColumns = ["operationKey"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("operationKey"), Index(value = ["operationKey", "rowKind", "rowId"], unique = true)]
)
data class NaturalBatchRowChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationKey: String,
    val rowKind: String,
    val rowId: Long,
    val beforeJson: String?,
    val afterJson: String?
)
