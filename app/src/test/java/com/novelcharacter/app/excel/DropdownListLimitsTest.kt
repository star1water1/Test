package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 드롭다운이 엑셀 한도에 걸려 통째로 사라지는 것을 막는 판정 (B-221).
 *
 * **이 시험이 잠그는 것은 세 가지다.**
 * 1. **길이 판정이 POI가 실제로 만드는 문자열과 같은 것을 세는가** — 여기가 어긋나면
 *    "한도 안"이라 판정한 목록이 엑셀에서 벗겨진다. 그 실패는 **파일을 열어 봐야만** 보인다.
 * 2. **쉼표·따옴표는 길이와 무관하게 명시 목록을 깬다** — 짧은 목록이라도 범위 참조로 가야 한다.
 * 3. **요약이 한도를 넘지 않으면서 뺀 수를 말하는가** — 상자 본문에도 한도가 있어서,
 *    목록을 그대로 넣으면 **목록이 길어질수록 안내가 먼저 깨졌다.**
 */
class DropdownListLimitsTest {

    // ── 1. 길이 ──

    @Test
    fun `수식 길이는 양끝 따옴표와 쉼표를 함께 센다`() {
        // "가,나,다" = 2(따옴표) + 3(값) + 2(쉼표)
        assertEquals(7, DropdownListLimits.explicitFormulaLength(listOf("가", "나", "다")))
        assertEquals(2, DropdownListLimits.explicitFormulaLength(emptyList()))
        assertEquals(3, DropdownListLimits.explicitFormulaLength(listOf("가")))
    }

    @Test
    fun `한도 경계에서 갈린다`() {
        // 값 하나로 정확히 255자를 만든다: 따옴표 2 + 값 253.
        val exactly = listOf("가".repeat(DropdownListLimits.MAX_FORMULA_CHARS - 2))
        assertEquals(DropdownListLimits.MAX_FORMULA_CHARS, DropdownListLimits.explicitFormulaLength(exactly))
        assertTrue(DropdownListLimits.fitsExplicitList(exactly))

        val oneOver = listOf("가".repeat(DropdownListLimits.MAX_FORMULA_CHARS - 1))
        assertFalse(DropdownListLimits.fitsExplicitList(oneOver))
    }

    @Test
    fun `실사용 규모의 작품 목록은 한도를 넘는다`() {
        // 목표 규모에서 이 자리가 실제로 걸린다는 것이 이 행의 발단이다.
        val novels = (1..60).map { "작품 $it" }
        assertFalse(DropdownListLimits.fitsExplicitList(novels))
    }

    // ── 2. 길이 말고도 깨는 글자 ──

    @Test
    fun `값 안의 쉼표는 짧아도 명시 목록을 쓸 수 없다`() {
        // 쉼표가 구분자라 "가,나" 한 값이 두 항목으로 쪼개진다 — 드롭다운이 조용히 틀린다.
        assertFalse(DropdownListLimits.fitsExplicitList(listOf("서울, 대한민국", "부산")))
        assertFalse(DropdownListLimits.fitsExplicitList(listOf("이름 \"별칭\"")))
        assertTrue(DropdownListLimits.fitsExplicitList(listOf("서울", "부산")))
    }

    // ── 3. 범위 참조 ──

    @Test
    fun `범위 참조는 절대 참조이고 시트명을 감싼다`() {
        assertEquals("'목록 데이터'!\$A\$1:\$A\$12", DropdownListLimits.rangeFormula("목록 데이터", 0, 0, 11))
        // 홑따옴표가 든 이름은 두 번 적어야 참조가 닫히지 않는다.
        assertEquals("'it''s'!\$B\$3:\$B\$3", DropdownListLimits.rangeFormula("it's", 1, 2, 2))
    }

    @Test
    fun `열 문자는 Z를 넘어간다`() {
        assertEquals("A", DropdownListLimits.columnLetter(0))
        assertEquals("Z", DropdownListLimits.columnLetter(25))
        assertEquals("AA", DropdownListLimits.columnLetter(26))
        assertEquals("AB", DropdownListLimits.columnLetter(27))
        assertEquals("BA", DropdownListLimits.columnLetter(52))
    }

    // ── 4. 상자 본문 요약 ──

    @Test
    fun `짧은 목록은 그대로 적는다`() {
        assertEquals("Y, N", DropdownListLimits.summarize(listOf("Y", "N"), 255))
        assertEquals("", DropdownListLimits.summarize(emptyList(), 255))
    }

    @Test
    fun `긴 목록은 한도 안으로 줄이고 뺀 수를 말한다`() {
        val many = (1..200).map { "작품 $it" }
        for (limit in listOf(DropdownListLimits.MAX_ERROR_CHARS, DropdownListLimits.MAX_PROMPT_CHARS, 40, 16)) {
            val text = DropdownListLimits.summarize(many, limit)
            assertTrue("limit=$limit len=${text.length}: $text", text.length <= limit)
            assertTrue("limit=$limit: $text", text.contains("외 "))
        }
    }

    @Test
    fun `한 항목조차 안 들어가면 그 항목을 잘라 넣는다`() {
        // 잘렸다는 것을 말없이 넘기지 않는다 — 상자가 빈 채로 뜨면 사용자는 목록이 없는 줄 안다.
        val huge = listOf("가".repeat(400), "나")
        val text = DropdownListLimits.summarize(huge, 30)
        assertTrue(text.length <= 30)
        assertTrue(text, text.contains("…"))
        assertTrue(text, text.contains("외 1개"))
    }
}
