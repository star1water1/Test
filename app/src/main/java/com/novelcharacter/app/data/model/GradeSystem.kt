package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 세계관 단위 등급 체계 (U-1) — 라벨 집합 + 라벨별 **기본** 숫자.
 *
 * 근거는 실사용 대조 §3-B ④다: 같은 등급 문자가 6~7필드에 반복되는데, 프리셋 하나 안에서도
 * 라벨 집합은 같고 숫자 표는 갈린다(`{"C":0.5,…}`와 `{"C":1,…}`가 공존). 그래서 체계는
 * **기본 숫자**만 들고, 필드는 체계를 참조하되 숫자를 개별 재정의할 수 있다(사용자 확정안 —
 * `repair_plan` 7-2). 참조·재정의·실효 표의 config 표현은 [GradeSystemRef]가 단일 소스다.
 *
 * 필드 쪽 참조는 FK가 아니라 config 안의 [code]다 — 실효 표가 config에 물질화되어 있어
 * 체계가 사라져도 필드는 독자 표로 동작하고(관대 수용), 엑셀·월드패키지·휴지통을 건너도
 * DB id 재발급과 무관하게 같은 체계에 다시 붙는다(R-1과 같은 원칙).
 */
@Entity(
    tableName = "grade_systems",
    foreignKeys = [
        ForeignKey(
            entity = Universe::class,
            parentColumns = ["id"],
            childColumns = ["universeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("universeId"),
        // 이름 해석(엑셀 '등급체계' 열, 화면 목록)이 유일해야 하므로 세계관 안에서 유니크.
        Index(value = ["universeId", "name"], unique = true),
        Index(value = ["code"], unique = true)
    ]
)
data class GradeSystem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val universeId: Long,
    val name: String,
    /** 라벨 → 기본 숫자 JSON 객체 (필드 config의 `grades`와 같은 형식 — 새 형식을 만들지 않는다). */
    val gradesJson: String = "{}",
    val displayOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** 안정 식별자 — 필드 config·엑셀·월드패키지·휴지통이 이 값으로 참조한다. */
    val code: String = generateEntityCode()
)
