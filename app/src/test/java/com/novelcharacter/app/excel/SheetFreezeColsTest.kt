package com.novelcharacter.app.excel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시트마다 **틀 고정 열이 정해져 있는가**(시각 개편 V-6 — *"오른쪽으로 스크롤해도 행의 주인이
 * 보인다"*).
 *
 * 실측(2026.08.24 사용자가 내보낸 파일): 스물여덟 spec 중 **열넷이 이 칸을 안 적어** 머리글
 * 한 줄만 고정된 채로 나갔다. 그중에는 열이 16개인 '관계 변화', 12개인 '대결 축'·'목록 프리셋',
 * 10개인 세력 셋이 있다 — 오른쪽으로 넘기면 **그 행이 누구 것인지 사라진다.**
 *
 * 이제 `freezeCols`에 기본값이 없어 **컴파일이 답을 강제**하지만(그 KDoc), 컴파일이 막지 못하는
 * 것이 하나 남는다: *답으로 `0`을 적는 것*. 그것을 이 시험이 본다 — 0이 옳은 시트가 생기면
 * 여기가 큰소리로 실패하고, 그때 사유를 적고 예외를 세우면 된다(실패의 방향 — R-43).
 */
class SheetFreezeColsTest {

    /**
     * 검사 대상 — [RESERVED_SHEET_NAMES]가 부르는 그 목록과 **같은 벌**이다.
     * 캐릭터 시트는 세계관마다 만들어져 이름이 동적이라 대표 하나를 세운다.
     */
    private fun allSpecs(): List<SheetSpec> = listOf(
        universeSpec(),
        novelSpec(emptyList()),
        fieldDefinitionSpec(emptyList()),
        gradeSystemSpec(),
        defaultFieldSpec(),
        fieldValueLibrarySpec(),
        characterFieldValueSpec(),
        novelFieldValueSpec(),
        eventFieldValueSpec(),
        characterSpec(emptyList(), emptyList()),
        allCharactersSpec(emptyList()),
        timelineSpec(emptyList()),
        stateChangeSpec(),
        quoteSpec(),
        relationshipSpec(),
        relationshipChangeSpec(),
        nameBankSpec(),
        factionSpec(),
        factionMembershipSpec(),
        factionRelationshipSpec(),
        userPresetTemplateSpec(),
        searchPresetSpec(),
        characterListPresetSpec(),
        appSettingsSpec(),
        imageMetaSpec(),
        duelAxisSpec(),
        duelMatchSpec(),
        duelVerdictSpec()
    )

    @Test
    fun `모든 시트가 정체 열을 하나 이상 고정한다`() {
        val zero = allSpecs().filter { it.freezeCols < 1 }
        assertTrue(
            "틀 고정이 없는 시트: ${zero.map { it.sheetName }} — " +
                "오른쪽으로 넘기면 행의 주인이 사라진다(V-6). 0이 옳다면 그 사유를 여기 적고 예외를 세울 것",
            zero.isEmpty()
        )
    }

    @Test
    fun `고정 열이 그 시트의 열 수를 넘지 않는다`() {
        val over = allSpecs().filter { it.freezeCols > it.columns.size }
        assertTrue(
            "열 수보다 많이 고정하는 시트: ${over.map { "${it.sheetName}(${it.freezeCols}>${it.columns.size})" }}",
            over.isEmpty()
        )
    }

    @Test
    fun `넓은 시트일수록 고정이 특히 필요하다 — 열 여덟 이상은 전부 고정돼 있다`() {
        val wideUnfrozen = allSpecs().filter { it.columns.size >= 8 && it.freezeCols < 1 }
        assertTrue(
            "넓은데 고정이 없는 시트: ${wideUnfrozen.map { "${it.sheetName}(${it.columns.size}열)" }}",
            wideUnfrozen.isEmpty()
        )
    }

    @Test
    fun `이 목록이 예약 시트명 전량을 덮는다`() {
        // 손으로 적은 목록이라 낡을 수 있다 — 낡으면 새 시트가 조용히 검사 밖에 남는다(R-43).
        val covered = allSpecs().map { it.sheetName }.toSet()
        val expected = RESERVED_SHEET_NAMES -
            setOf(GUIDE_SHEET_NAME, UNCLASSIFIED_SHEET_NAME, DROPDOWN_LIST_SHEET_NAME, "")
        val missing = expected - covered
        assertTrue("이 시험이 안 보는 예약 시트: $missing", missing.isEmpty())
    }
}
