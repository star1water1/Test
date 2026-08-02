package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FactionRelationship

/**
 * 엑셀 '세력 관계' 행 ↔ 기존 관계의 매칭·변경 규칙 — **가져오기와 복원 미리보기의 단일 소스.**
 *
 * **왜 여기로 모았는가(B-87):** 미리보기 분석에는 '동일'을 셀 자리가 아예 없었다
 * (`CategoryAnalysis`의 unchanged 자리에 상수 `0`이 박혀 있었다). 그래서 (세력1, 세력2, 유형)이
 * 맞기만 하면 **설명·강도·양방향·표시순서가 한 글자도 다르지 않아도 '변경'으로 세었고**,
 * 아무것도 고치지 않은 파일을 그대로 다시 넣어도 미리보기가 "변경 N"이라 말했다 —
 * 실제 가져오기는 같은 값을 다시 쓸 뿐 바뀌는 것이 없는데도(왕복 멱등 확인 A7이 걸리는 자리).
 *
 * **'동일'의 기준은 매칭 키가 아니라 비교 대상 전체다**(사용자 판정 2026.08.02):
 * 관계를 **찾는** 것은 (세력1, 세력2, 유형)이되, **같다고 부르는** 것은 가져오기가 실제로
 * 쓰는 값 전체가 일치할 때다. [FactionMembershipMatcher]가 세운 `match`/`apply`/`changes`
 * 분리를 그대로 따르며, 가져오기와 분석이 **같은 함수**를 부르므로 구조적으로 다시 갈릴 수 없다.
 */
object FactionRelationshipMatcher {

    /** 엑셀 한 행이 말하는 관계 값 — 기본값은 [FactionRelationship]의 기본값과 같다. */
    data class RowValues(
        val description: String = "",
        val intensity: Int = 5,
        val isBidirectional: Boolean = true,
        val displayOrder: Int = 0
    )

    /**
     * 시트에 그 열이 **있었는가**. 없는 열은 기존값을 유지한다 —
     * '빈칸'(=지우라는 뜻)과 '열 자체가 없음'(=말한 바 없음)은 다르다(F1-A).
     */
    data class ColumnPresence(
        val description: Boolean = true,
        val intensity: Boolean = true,
        val isBidirectional: Boolean = true,
        val displayOrder: Boolean = true
    )

    /** 저장 방향 그대로의 키. 한 쌍은 한 행으로만 저장된다(유니크 인덱스). */
    fun key(factionId1: Long, factionId2: Long, relationType: String): Triple<Long, Long, String> =
        Triple(factionId1, factionId2, relationType)

    /**
     * 정방향·역방향을 모두 본다 — 앱은 한 쌍을 한 행으로 저장하므로(`isBidirectional`),
     * 시트에서 세력1·세력2가 뒤바뀌어 있어도 **같은 관계**다. 이것을 빠뜨리면 재가져오기마다
     * 뒤집힌 행이 중복으로 쌓인다.
     */
    fun match(
        byKey: Map<Triple<Long, Long, String>, FactionRelationship>,
        factionId1: Long,
        factionId2: Long,
        relationType: String
    ): FactionRelationship? =
        byKey[key(factionId1, factionId2, relationType)]
            ?: byKey[key(factionId2, factionId1, relationType)]

    /**
     * 매칭된 관계에 행을 적용한 결과. 시트에 없던 열은 [existing]의 값을 그대로 둔다.
     * `createdAt`은 갱신하지 않는다 — 안정 식별자를 조용히 바꾸지 않는다.
     */
    fun apply(
        existing: FactionRelationship,
        row: RowValues,
        presence: ColumnPresence = ColumnPresence()
    ): FactionRelationship = existing.copy(
        description = if (presence.description) row.description else existing.description,
        intensity = if (presence.intensity) row.intensity else existing.intensity,
        isBidirectional = if (presence.isBidirectional) row.isBidirectional else existing.isBidirectional,
        displayOrder = if (presence.displayOrder) row.displayOrder else existing.displayOrder
    )

    /**
     * 이 행을 넣으면 실제로 무언가 바뀌는가 — 미리보기의 '변경'과 '동일'을 가르는 자리.
     * 가져오기가 쓰는 [apply]와 같은 함수로 판정하므로 미리보기가 예고한 것과 실제가 어긋나지 않는다.
     */
    fun changes(
        existing: FactionRelationship,
        row: RowValues,
        presence: ColumnPresence = ColumnPresence()
    ): Boolean = apply(existing, row, presence) != existing
}
