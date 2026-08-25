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
        Index(value = ["factionId1", "factionId2", "relationType"], unique = true),
        Index(value = ["code"], unique = true)
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
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 왕복 안정 식별자 — **형제인 [CharacterRelationship.code]와 같은 이유로, 같은 모양으로**
     * (v58, 2026.08.25).
     *
     * 이 표만 그 칸이 없었다. 자연키가 (세력1, 세력2, 관계 유형)이라 **엑셀에서 유형을 고치면
     * rename인지 신규인지 구별할 수 없었고**, 가져오기는 새 관계를 만들면서 옛 관계를 그대로
     * 남겼다 — 한 쌍이 두 줄이 된다. 사용자 파일에 이미 그 모양이 있었다(`2 ↔ 1`이 '동맹'과
     * '동' 두 행, 생성일 13초 차이).
     *
     * 종전 판(2026.08.24)은 이 자리를 **안내 문구로** 막았다: *"유형을 바꾸려면 그 행을 고치는
     * 대신 앱에서 지우고 새로 만드세요."* 그것은 형제 시트가 코드로 푼 문제를 사용자에게
     * 떠넘긴 것이라, 근본 수리로 갈음한다(사용자 지시 — *"방편식 말고 근본적으로"*).
     *
     * v57 이전 행은 마이그레이션에서 백필하므로 실제로 null인 행은 없다. nullable로 두는 것도
     * 형제와 같다 — `DEFAULT` 절 없는 `ALTER TABLE ADD COLUMN` 결과가 이 선언과 정확히 맞는다.
     */
    val code: String? = generateEntityCode()
)
