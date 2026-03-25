package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "timeline_event_novel_cross_ref",
    primaryKeys = ["eventId", "novelId"],
    foreignKeys = [
        ForeignKey(
            entity = TimelineEvent::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("eventId"), Index("novelId")]
)
data class TimelineEventNovelCrossRef(
    val eventId: Long,
    val novelId: Long
)

/** DAO 조회 결과용 DTO — 사건별 연결 작품명 */
data class EventNovelName(
    val eventId: Long,
    val title: String
)
