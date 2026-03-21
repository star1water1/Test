package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "character_relationships",
    foreignKeys = [
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["characterId1"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["characterId2"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("characterId1"), Index("characterId2"), Index("createdAt"),
        Index(value = ["characterId1", "characterId2", "relationshipType"], unique = true),
        Index(value = ["displayOrder", "createdAt"])
    ]
)
data class CharacterRelationship(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId1: Long,
    val characterId2: Long,
    val relationshipType: String,
    val description: String = "",
    val intensity: Int = 5,              // 1~10 관계 강도 (그래프 선 굵기)
    val isBidirectional: Boolean = true, // false이면 단방향 (characterId1 → characterId2)
    val displayOrder: Int = 0,           // 드래그앤드롭 정렬용
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** @deprecated 세계관별 커스텀 관계 유형을 사용하세요: Universe.getRelationshipTypes() */
        val TYPES = Universe.DEFAULT_RELATIONSHIP_TYPES
    }
}
