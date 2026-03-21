package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "character_relationship_changes",
    foreignKeys = [
        ForeignKey(
            entity = CharacterRelationship::class,
            parentColumns = ["id"],
            childColumns = ["relationshipId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TimelineEvent::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("relationshipId"),
        Index("year"),
        Index("eventId")
    ]
)
data class CharacterRelationshipChange(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val relationshipId: Long,
    val year: Int,
    val month: Int? = null,
    val day: Int? = null,
    val relationshipType: String,
    val description: String = "",
    val intensity: Int = 5,          // 1~10, 관계 강도 (그래프 선 굵기)
    val isBidirectional: Boolean = true,
    val eventId: Long? = null,       // 연결된 사건 ID (null이면 미연결)
    val createdAt: Long = System.currentTimeMillis()
)
