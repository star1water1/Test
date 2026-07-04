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
        Index("eventId"),
        Index(value = ["code"], unique = true)
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
    val createdAt: Long = System.currentTimeMillis(),
    // 엑셀 왕복 안정 식별자 — 자연키(관계+연월일) 편집이 중복 생성으로 이어지지 않게 하는 기준.
    // nullable인 이유: v35 이전 Gson 스냅샷(휴지통) 역직렬화 시 null이 주입될 수 있다 (레거시 수용).
    val code: String? = generateEntityCode()
)
