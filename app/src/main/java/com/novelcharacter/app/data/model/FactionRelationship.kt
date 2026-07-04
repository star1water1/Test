package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 세력 간 관계 (B-3).
 * CharacterRelationship과 동형 설계 — 단일 행 + isBidirectional로 방향 표현.
 * 관계 유형은 세계관 커스텀 유형(Universe.getRelationshipTypes())을 공유한다.
 */
@Entity(
    tableName = "faction_relationships",
    foreignKeys = [
        ForeignKey(
            entity = Faction::class,
            parentColumns = ["id"],
            childColumns = ["factionId1"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Faction::class,
            parentColumns = ["id"],
            childColumns = ["factionId2"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("factionId1"), Index("factionId2"),
        Index(value = ["factionId1", "factionId2", "relationType"], unique = true)
    ]
)
data class FactionRelationship(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val factionId1: Long,
    val factionId2: Long,
    val relationType: String,
    val description: String = "",
    val intensity: Int = 5,              // 1~10 관계 강도 (그래프 선 굵기)
    val isBidirectional: Boolean = true, // false이면 단방향 (factionId1 → factionId2)
    val displayOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
