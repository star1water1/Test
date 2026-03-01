package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_events",
    foreignKeys = [
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("novelId"), Index("year")]
)
data class TimelineEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val year: Int,                           // 음수 가능 (-20000 ~ )
    val calendarType: String = "천개력",      // 천개력전, 천개력 등
    val description: String,
    val novelId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
