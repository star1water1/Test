package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.Universe

/**
 * 관계 시트 두 장의 **'관계 유형' 드롭다운 목록** — 순수 계산이라 테스트가 고정한다.
 *
 * ## 무엇이 깨지고 있었나
 *
 * 목록을 `기본 유형 + 세계관 커스텀 유형`으로만 지었다. 그런데 그 시트에 실제로 실리는 값은
 * 그 둘이 전부가 아니다:
 *
 * - **세력의 자동관계유형**([com.novelcharacter.app.data.model.Faction.autoRelationType])은
 *   세력을 만들 때 사용자가 **자유롭게 적는 글자**다. 그 세력에 멤버가 둘 이상 붙는 순간
 *   그 글자를 든 관계가 자동으로 생기고, 그것이 '캐릭터 관계' 시트의 행이 된다.
 * - **세력 간 관계 유형**은 캐릭터 관계와 어휘 자체가 다르다(동맹·적대…). 그런데 '세력 관계'
 *   시트가 캐릭터용 목록을 그대로 쓰고 있었다.
 *
 * 실측(2026.08.24 사용자가 내보낸 파일): **'캐릭터 관계' 135행 중 87행**(가문원 81 · 월아 6),
 * **'세력 관계' 2행 중 2행**(동맹 · 동)이 자기 시트의 드롭다운 밖 값이었다.
 *
 * ## 왜 그것이 결함인가
 *
 * 유효성 검사는 `showError = true`로 실린다. 그래서 사용자가 그 칸을 고쳤다가 **원래 값으로
 * 되돌리려 하면 엑셀이 막는다** — 안내 시트는 바로 그 자리에서 *"관계 유형은 드롭다운에서
 * 선택"*이라 적는다. 목록이 자기 시트의 값을 모르는 한 그 문장은 참이 아니다.
 *
 * ## 처방 — **쓰이는 값을 재료에 넣는다**
 *
 * 형제 열들은 이미 그렇게 한다(작품 열은 작품 목록을, 세력 열은 세력 목록을 재료로 쓴다).
 * 여기만 *정의된 목록*만 보고 *쓰이는 값*을 안 봤다. 자르지 않는다 — 목록이 길어지면
 * [DropdownListLimits]가 숨김 시트 범위 참조로 옮겨 실으므로 개수 한도가 없다.
 *
 * **차례는 고정이다**: 기본 → 세계관 커스텀 → (세력 자동) → 그 밖에 쓰이는 값(사전순).
 * 마지막을 정렬하는 것은 출력이 실행마다 흔들리지 않게 하기 위한 것이다
 * ([AllCharactersSheet.sharedFields]가 이름을 고르는 자리와 같은 근거).
 */
object RelationshipTypeOptions {

    /**
     * '캐릭터 관계' 시트의 목록.
     *
     * @param customTypes 세계관들이 정의한 커스텀 관계 유형(전 세계관 합집합)
     * @param factionAutoTypes 세력들의 자동관계유형 — 이 시트의 행을 실제로 만드는 글자다
     * @param typesInUse 지금 DB의 관계들이 든 유형 — 위 셋 어디에도 없는 값이 남을 수 있다
     *   (세력을 지운 뒤 남은 자동관계, 옛 파일로 들여온 유형)
     */
    fun forCharacterRelations(
        customTypes: List<String>,
        factionAutoTypes: List<String>,
        typesInUse: List<String>
    ): List<String> = build(
        ordered = Universe.DEFAULT_RELATIONSHIP_TYPES + customTypes + factionAutoTypes,
        remainder = typesInUse
    )

    /**
     * '세력 관계' 시트의 목록. 세력 자동관계유형은 **캐릭터 사이**의 관계라 여기 넣지 않는다 —
     * 넣으면 세력끼리 고를 수 없는 값이 목록에 섞인다.
     */
    fun forFactionRelations(
        customTypes: List<String>,
        typesInUse: List<String>
    ): List<String> = build(
        ordered = Universe.DEFAULT_RELATIONSHIP_TYPES + customTypes,
        remainder = typesInUse
    )

    /** 차례가 있는 재료를 먼저, 남은 것은 사전순으로. 빈 글자는 목록이 되지 못한다. */
    private fun build(ordered: List<String>, remainder: List<String>): List<String> {
        val head = ordered.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val taken = head.toHashSet()
        val tail = remainder.map { it.trim() }
            .filter { it.isNotEmpty() && it !in taken }
            .distinct()
            .sorted()
        return head + tail
    }
}
