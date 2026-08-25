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
        /**
         * 표시 순서 — **`null`이면 이 행이 순서를 말하지 않았다**(빈칸이거나 해석 불가).
         *
         * 종전에는 `Int = 0`이라 빈칸이 곧 `0`이었고, 열 머리만 남아 있으면
         * [ColumnPresence.displayOrder]가 참이므로 **기존 순서가 0으로 덮였다** —
         * 열두 형제 시트는 전부 `Int?` + `?: existing`으로 기존을 지키는데 이 시트만 밖이었다.
         */
        val displayOrder: Int? = null
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

    /** 방향을 지운 쌍 키 — 한 쌍은 한 행이므로 (1,2)와 (2,1)은 같은 쌍이다. */
    private fun pairKey(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a

    /**
     * 한 행의 매칭 결과 — [matchRow]가 낸다. 형제([com.novelcharacter.app.util.RelationshipRowMatch])와
     * 같은 모양이라 부르는 쪽의 고지 문구도 같은 갈래로 짠다.
     *
     * @property existing 이 행이 고칠 기존 관계. `null`이면 새로 만든다.
     * @property codeOfOtherPair 파일의 코드가 **다른 두 세력의 관계**를 가리켰을 때 그 관계.
     */
    data class RowMatch(
        val existing: FactionRelationship?,
        val codeOfOtherPair: FactionRelationship?
    ) {
        /** 새로 만들 때 파일의 코드를 그대로 쓸 수 있는가 — 남이 이미 든 코드면 못 쓴다(유니크 열). */
        val canReuseFileCode: Boolean get() = codeOfOtherPair == null
    }

    /**
     * 이 행이 고칠 기존 관계를 고른다 — **코드(안정 식별자) 우선 → 자연키(쌍+유형) 폴백**
     * (v58, 2026.08.25). 규약·조건·사유가 전부 형제
     * [com.novelcharacter.app.util.RelationshipIndexes.matchRow]와 같다.
     *
     * ## 왜 코드가 먼저인가
     *
     * 자연키에 **관계 유형이 들어 있다.** 그래서 코드가 없던 종전에는 시트에서 유형을 고치는
     * 순간 그 행이 *다른 관계*가 됐다 — 가져오기는 새 관계를 만들고 옛 관계는 그대로 남는다.
     * 사용자 파일에 이미 그 모양이 있었다(같은 쌍이 '동맹'과 '동' 두 행). 종전 판은 이 자리를
     * 안내 문구로 막았고("앱에서 지우고 새로 만드세요"), 이 함수가 그것을 대신한다.
     *
     * ## 코드에 붙는 한 가지 조건
     *
     * '코드' 열은 회색(readOnly)이라 **행을 복사하면 남의 코드가 따라온다.** 조건이 없으면
     * 그 코드를 따라가 남의 관계가 이 행의 값으로 덮이고, 이 행이 말한 관계는 만들어지지
     * 않는다(둘 다 말이 없다). 그래서 **같은 두 세력의 관계일 때만** 코드를 따른다.
     */
    fun matchRow(
        byKey: Map<Triple<Long, Long, String>, FactionRelationship>,
        byCode: Map<String, FactionRelationship>,
        relCode: String,
        factionId1: Long,
        factionId2: Long,
        relationType: String
    ): RowMatch {
        val coded = if (relCode.isNotBlank()) byCode[relCode] else null
        val samePair = coded != null &&
            pairKey(coded.factionId1, coded.factionId2) == pairKey(factionId1, factionId2)
        return RowMatch(
            existing = if (samePair) coded else match(byKey, factionId1, factionId2, relationType),
            codeOfOtherPair = if (coded != null && !samePair) coded else null
        )
    }

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
        // **말하지 않은 것은 바꾸지 않는다** — 열이 없으면(presence=false) 물론이고,
        // 열은 있는데 칸이 비었거나 해석이 안 되면(`row.displayOrder == null`) 그때도 그대로 둔다.
        displayOrder = if (presence.displayOrder) row.displayOrder ?: existing.displayOrder else existing.displayOrder
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
