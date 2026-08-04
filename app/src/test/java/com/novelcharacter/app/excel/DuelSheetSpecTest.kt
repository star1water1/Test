package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.util.DuelFieldLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 시트 셋(B-104 ㄹ1)의 왕복 계약.
 *
 * **여기서 지키는 것 셋:** ① 시트 이름이 예약되어 세계관에 빼앗기지 않는다 ② 시트 헤더가
 * 서로를, 그리고 캐릭터 시트를 침범하지 않는다 ③ **필드 연결이 엑셀 글 ↔ 저장 형식으로
 * 왕복해도 순위와 방향이 그대로다**(개발 의도 4번 — 엑셀 왕복 무결성).
 */
class DuelSheetSpecTest {

    private val axisSpec = duelAxisSpec()
    private val matchSpec = duelMatchSpec()
    private val verdictSpec = duelVerdictSpec()

    // ── 이름 ──

    @Test
    fun `대결 시트 셋은 예약명이라 세계관에 빼앗기지 않는다`() {
        for (spec in listOf(axisSpec, matchSpec, verdictSpec)) {
            assertTrue("${spec.sheetName}이 예약명에 없다", spec.sheetName in RESERVED_SHEET_NAMES)
            // 같은 이름의 세계관이 있어도 예약 시트가 원명을 지킨다.
            val used = mutableSetOf<String>()
            val universeSheet = assignSheetName(spec.sheetName, used)
            val reservedSheet = assignSheetName(spec.sheetName, used, ownerOf = spec.sheetName)
            assertNotEquals(spec.sheetName, universeSheet)
            assertEquals(spec.sheetName, reservedSheet)
        }
    }

    /**
     * 첫 열이 서로 달라야 시트 정체가 갈린다. 축 시트의 첫 열을 '이름'으로 두지 않은 것도
     * 이 때문이다 — 그러면 캐릭터 시트와 첫 열이 같아진다.
     */
    @Test
    fun `대결 시트의 첫 열은 캐릭터 시트와 겹치지 않는다`() {
        assertEquals("축이름", axisSpec.firstColumnHeader)
        assertEquals("축", matchSpec.firstColumnHeader)
        assertEquals("축", verdictSpec.firstColumnHeader)
        assertNotEquals("이름", axisSpec.firstColumnHeader)
    }

    /** 기록과 상성은 첫 열이 같으므로 **둘째 열부터** 갈려야 한다(`headersMatchSpec`의 규약). */
    @Test
    fun `기록 시트와 상성 시트는 헤더로 서로를 구별한다`() {
        val matchHeaders = matchSpec.columns.map { it.header }
        val verdictHeaders = verdictSpec.columns.map { it.header }
        assertTrue(headersMatchSpec(matchHeaders, matchSpec))
        assertTrue(headersMatchSpec(verdictHeaders, verdictSpec))
        assertTrue("기록 헤더가 상성 시트로 읽히면 안 된다", !headersMatchSpec(matchHeaders, verdictSpec))
        assertTrue("상성 헤더가 기록 시트로 읽히면 안 된다", !headersMatchSpec(verdictHeaders, matchSpec))
    }

    // ── 어휘 ──

    @Test
    fun `시트가 쓰는 말과 저장값이 서로 짝이 맞는다`() {
        assertEquals(2, DuelSheetLabels.TARGETS.size)
        assertTrue(DuelAxis.TARGET_CHARACTER in DuelAxis.TARGET_TYPES)
        assertTrue(DuelAxis.TARGET_IMAGE in DuelAxis.TARGET_TYPES)
        assertEquals(listOf(DuelSheetLabels.KIND_COUNTER, DuelSheetLabels.KIND_UNDECIDED), DuelSheetLabels.KINDS)
        // 저장값 쪽도 둘뿐이다 — 라벨이 늘면 여기서 먼저 어긋난다.
        assertNotEquals(DuelCounterVerdict.KIND_COUNTER, DuelCounterVerdict.KIND_UNDECIDED)
    }

    /** 대상 열의 드롭다운이 실제 저장값 수와 같아야 한다 — 하나가 빠지면 그 축은 엑셀에서 만들 수 없다. */
    @Test
    fun `대상 드롭다운이 저장값 전부를 덮는다`() {
        val target = axisSpec.columns.first { it.header == "대상" }
        assertEquals(DuelAxis.TARGET_TYPES.size, target.dropdownOptions?.size)
    }

    // ── 필드 연결의 왕복 (개발 의도 4번) ──

    /**
     * 엑셀 칸에 적힌 글 → 저장 형식 → 다시 엑셀 칸. **순위와 방향이 그대로여야 한다.**
     * 여기가 어긋나면 파일을 한 번 내보냈다 들이는 것만으로 영향력 순위가 바뀐다.
     */
    @Test
    fun `필드 연결은 엑셀 글과 저장 형식 사이를 왕복한다`() {
        val text = "mana, -age, attr"
        val stored = DuelFieldLinks.encode(DuelFieldLinks.parseText(text))
        assertEquals(text, DuelFieldLinks.toText(DuelFieldLinks.decode(stored)))

        // 순위는 적은 차례 그대로다 — 정렬하면 1순위가 바뀐다.
        assertEquals(listOf("mana", "age", "attr"), DuelFieldLinks.decode(stored).map { it.key })
        assertEquals(listOf(true, false, true), DuelFieldLinks.decode(stored).map { it.higherWins })
    }

    @Test
    fun `빈 칸은 연결 없음으로 왕복한다`() {
        assertEquals("", DuelFieldLinks.toText(DuelFieldLinks.parseText("")))
        assertEquals("[]", DuelFieldLinks.encode(DuelFieldLinks.parseText("   ")))
    }
}
