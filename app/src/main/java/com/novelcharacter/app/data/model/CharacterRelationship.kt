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
        ),
        ForeignKey(
            entity = Faction::class,
            parentColumns = ["id"],
            childColumns = ["factionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("characterId1"), Index("characterId2"), Index("createdAt"),
        Index(value = ["characterId1", "characterId2", "relationshipType"], unique = true),
        Index(value = ["displayOrder", "createdAt"]),
        Index("factionId"),
        Index(value = ["code"], unique = true)
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
    /**
     * 왕복 안정 식별자. 관계의 자연키는 (캐릭터1, 캐릭터2, 관계 유형)이라 엑셀에서 유형을 고치면
     * rename인지 신규인지 구별할 수 없었다 — 이 코드가 있으면 유형을 바꿔도 같은 관계로 인식한다.
     * v41 이전 행은 마이그레이션에서 백필하므로 실제로 null인 행은 없다(엔티티는 사건·상태변화와
     * 동일하게 nullable로 두어 ALTER TABLE ADD COLUMN 경로를 그대로 쓴다).
     */
    val code: String? = generateEntityCode(),
    val createdAt: Long = System.currentTimeMillis(),
    val factionId: Long? = null          // null=수동 관계, 값 있으면 해당 세력의 자동 관계
) {
    companion object {
        /** @deprecated 세계관별 커스텀 관계 유형을 사용하세요: Universe.getRelationshipTypes() */
        val TYPES = Universe.DEFAULT_RELATIONSHIP_TYPES
    }
}
