package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FactionMembership

/**
 * 엑셀 '세력 소속' 행 ↔ 기존 소속 이력의 매칭 규칙 — **가져오기와 복원 미리보기의 단일 소스.**
 *
 * **왜 여기로 모았는가:** 두 곳이 같은 규칙을 각자 들고 있다가 갈렸다.
 * 가져오기는 "활성만 보면 탈퇴 이력을 영원히 못 찾아 매번 중복 행을 만든다"는 이유로
 * 3단 계층으로 고쳐졌는데, 미리보기 분석은 `getActiveMembership`(활성만)에 남아 있었다.
 * 그래서 탈퇴 이력이 있는 행은 아무것도 안 고친 파일을 그대로 다시 넣어도
 * **'신규'로 세어지고 '백업에 없음'(=덮어쓰기 시 삭제 대상)으로도 세어졌다** —
 * 실제 가져오기는 정확히 매칭해 아무것도 바꾸지 않는데 미리보기만 삭제를 예고한 것이다.
 * 규칙을 한 곳에 두어 **구조적으로 다시 갈릴 수 없게** 한다.
 */
object FactionMembershipMatcher {

    /**
     * 엑셀 한 행이 말하는 소속 값.
     *
     * `createdAt`이 null이면 시트에 '생성일' 열이 없다는 뜻이며, 그때는 안정 식별자가
     * 없으므로 자연키부터 본다.
     */
    data class RowValues(
        val joinYear: Int? = null,
        val leaveYear: Int? = null,
        val leaveType: String? = null,
        val departedRelationType: String? = null,
        val departedIntensity: Int? = null,
        val createdAt: Long? = null
    )

    /**
     * 시트에 그 열이 **있었는가**. 없는 열은 기존값을 유지한다 —
     * '빈칸'(=지우라는 뜻)과 '열 자체가 없음'(=말한 바 없음)은 다르다.
     */
    data class ColumnPresence(
        val joinYear: Boolean = true,
        val leaveYear: Boolean = true,
        val leaveType: Boolean = true,
        val departedRelationType: Boolean = true,
        val departedIntensity: Boolean = true,
        val createdAt: Boolean = true
    )

    /**
     * 소속 이력의 **자연키** — `(세력, 가입연도, 탈퇴연도, 탈퇴유형)`.
     *
     * `faction_memberships`에는 유니크 제약이 없다(**재가입 이력을 보존하려고 그렇게 뒀다**).
     * 그래서 "같은 이력인가"를 묻는 자리마다 키를 손으로 적게 되는데, 그것이 두 벌로 적히면
     * 반드시 갈린다 — 실제로 되돌리기가 세력 id 하나만 보고 접어 **같은 세력의 재가입 이력
     * 두 줄 중 첫 줄만 되살리고 나머지를 말없이 버렸다**(계획 쪽은 네 칸을 다 보고 있었다).
     *
     * @param factionId 해석된 **지금의** 세력 id — 옛 id를 그대로 넣으면 수렴을 못 본다.
     */
    fun naturalKey(factionId: Long, membership: FactionMembership): List<Any?> =
        listOf(factionId, membership.joinYear, membership.leaveYear, membership.leaveType)

    /**
     * ① 생성일(안정 식별자) 일치 → ② 자연키(가입·탈퇴연도·탈퇴유형) 일치 → ③ 후보가 유일하면 상태 전이.
     *
     * [candidates]는 같은 (세력, 캐릭터) 쌍의 이력 **전체**에서 이미 다른 행이 가져간 것을
     * 뺀 나머지여야 한다. 활성만 걸러 넣으면 이 함수가 있는 이유가 사라진다.
     */
    fun match(candidates: List<FactionMembership>, row: RowValues): FactionMembership? {
        if (row.createdAt != null) {
            candidates.find { it.createdAt == row.createdAt }?.let { return it }
        }
        candidates.find {
            it.joinYear == row.joinYear && it.leaveYear == row.leaveYear && it.leaveType == row.leaveType
        }?.let { return it }
        return candidates.singleOrNull()
    }

    /** 매칭된 이력에 행을 적용한 결과. 시트에 없던 열은 [existing]의 값을 그대로 둔다. */
    fun apply(
        existing: FactionMembership,
        row: RowValues,
        presence: ColumnPresence = ColumnPresence()
    ): FactionMembership = existing.copy(
        joinYear = if (presence.joinYear) row.joinYear else existing.joinYear,
        leaveYear = if (presence.leaveYear) row.leaveYear else existing.leaveYear,
        leaveType = if (presence.leaveType) row.leaveType else existing.leaveType,
        departedRelationType = if (presence.departedRelationType) row.departedRelationType else existing.departedRelationType,
        departedIntensity = if (presence.departedIntensity) row.departedIntensity else existing.departedIntensity,
        createdAt = if (presence.createdAt && row.createdAt != null) row.createdAt else existing.createdAt
    )

    /**
     * 이 행을 넣으면 실제로 무언가 바뀌는가 — 미리보기의 '변경'과 '동일'을 가르는 자리.
     * 가져오기가 쓰는 [apply]와 같은 함수로 판정하므로 미리보기가 예고한 것과 실제가 어긋나지 않는다.
     */
    fun changes(
        existing: FactionMembership,
        row: RowValues,
        presence: ColumnPresence = ColumnPresence()
    ): Boolean = apply(existing, row, presence) != existing
}
