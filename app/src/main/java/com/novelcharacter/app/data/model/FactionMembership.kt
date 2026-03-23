package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "faction_memberships",
    foreignKeys = [
        ForeignKey(
            entity = Faction::class,
            parentColumns = ["id"],
            childColumns = ["factionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("factionId"),
        Index("characterId")
        // unique 제약 없음 — 재가입 이력 보존을 위해 Repository 레벨에서 활성 멤버십 중복 체크
    ]
)
data class FactionMembership(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val factionId: Long,
    val characterId: Long,
    val joinYear: Int? = null,                  // 가입 시점 (null = 시점 불명/처음부터)
    val leaveYear: Int? = null,                 // 탈퇴 시점 (null = 현재 소속 중)
    val leaveType: String? = null,              // null=활성 | "removed"=순수제거 | "departed"=설정상탈퇴
    val departedRelationType: String? = null,   // 탈퇴 후 관계 유형 (사용자 지정)
    val departedIntensity: Int? = null,         // 탈퇴 후 관계 강도
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 주어진 연도에 이 멤버십이 활성 상태인지 판별 */
    fun isActiveAtYear(year: Int): Boolean {
        // removed: 멤버십 자체가 삭제된 것이므로 leaveYear 이후부터 비활성
        // departed: 설정상 탈퇴이므로 leaveYear 이후부터 비활성 (이전 기간은 활성)
        val afterJoin = joinYear == null || year >= joinYear
        val beforeLeave = leaveYear == null || year < leaveYear
        return afterJoin && beforeLeave
    }

    companion object {
        const val LEAVE_REMOVED = "removed"
        const val LEAVE_DEPARTED = "departed"
    }
}
