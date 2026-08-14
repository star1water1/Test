package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldType
import org.apache.poi.ss.usermodel.CellType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시각 개편(2026.08.14 — 사용자 확정 Q-1~Q-3)의 순수 판정들.
 *
 * ## 왜 [numericExportValueOrNull]이 [ExcelCellValue]와 한 시험에 있는가
 *
 * 그 함수의 계약은 *"가져오기의 숫자 정규화가 이 수를 도로 문자열로 만들면 저장 원문과 같다"*이다.
 * 조건을 내보내기 쪽 규칙 사본으로 적으면 가져오기 쪽([ExcelCellValue.normalizeNumeric])이 바뀔 때
 * 둘이 조용히 갈린다 — 그래서 **수용한 모든 값에 대해 실제 normalize를 돌려 원문과 견주는**
 * 왕복 성질 시험을 여기 둔다. 이 시험이 깨지면 숫자 셀이 무편집 왕복에서 값을 바꾸고 있다는 뜻이다.
 */
class ExportPresentationSpecTest {

    // ── numericExportValueOrNull (Q-1 ⓐ — 왕복 멱등 단서) ──

    @Test
    fun `정수와 최단 소수만 숫자 셀이 된다`() {
        assertEquals(24.0, numericExportValueOrNull("24"))
        assertEquals(-3.0, numericExportValueOrNull("-3"))
        assertEquals(0.0, numericExportValueOrNull("0"))
        assertEquals(24.5, numericExportValueOrNull("24.5"))
        assertEquals(0.5, numericExportValueOrNull("0.5"))
        assertEquals(1721834567890.0, numericExportValueOrNull("1721834567890"))
    }

    @Test
    fun `원문이 보존되지 않는 표기는 문자열로 남는다`() {
        assertNull("소수 끝 0은 정규화가 지운다", numericExportValueOrNull("24.50"))
        assertNull("선행 0은 정규화가 지운다", numericExportValueOrNull("007"))
        assertNull("과학표기는 평문으로 풀린다", numericExportValueOrNull("1e5"))
        assertNull("공백은 trim되어 달라진다", numericExportValueOrNull(" 24"))
        assertNull("앞자리 없는 소수", numericExportValueOrNull(".5"))
        assertNull("음의 0", numericExportValueOrNull("-0"))
        assertNull("빈 값", numericExportValueOrNull(""))
        assertNull("숫자가 아니다", numericExportValueOrNull("스물넷"))
        assertNull("무한대", numericExportValueOrNull("Infinity"))
        assertNull("NaN", numericExportValueOrNull("NaN"))
        assertNull("double 정밀도 밖 정수", numericExportValueOrNull("9007199254740993"))
        assertNull("천 단위 구분", numericExportValueOrNull("1,234"))
    }

    @Test
    fun `수용한 값은 가져오기의 정규화를 지나도 원문 그대로다 - 왕복 성질`() {
        val samples = listOf(
            "24", "-3", "0", "24.5", "0.5", "-12.25", "1721834567890",
            "3.14159", "100000", "912"
        )
        for (raw in samples) {
            val d = numericExportValueOrNull(raw) ?: continue
            val back = ExcelCellValue.normalize(
                ExcelCellValue.Primitives(CellType.NUMERIC, numericValue = d),
                dateHint = false
            )
            assertEquals("숫자 셀로 실은 '$raw'가 가져오기에서 다른 글자가 되면 안 된다", raw, back)
        }
        // 위 목록이 전부 수용되는지도 잠근다 — continue로 조용히 비는 시험이 되지 않게.
        assertTrue(samples.all { numericExportValueOrNull(it) != null })
    }

    @Test
    fun `거부한 값도 이유가 왕복이다 - 정규화가 실제로 다른 글자를 낸다`() {
        for (raw in listOf("24.50", "007", "1e5")) {
            val d = raw.trim().toDouble()
            val back = ExcelCellValue.normalize(
                ExcelCellValue.Primitives(CellType.NUMERIC, numericValue = d),
                dateHint = false
            )
            assertTrue("'$raw'는 정규화가 '$back'을 내므로 거부가 맞다", back != raw)
        }
    }

    // ── estimateWrapLines (V-4 — 행 높이 기록의 추정기) ──

    @Test
    fun `빈 값과 한 줄은 1이다`() {
        assertEquals(1, estimateWrapLines("", 10000))
        assertEquals(1, estimateWrapLines("짧은 메모", 10000))
    }

    @Test
    fun `개행은 그대로 줄이 된다`() {
        assertEquals(2, estimateWrapLines("첫 줄\n둘째 줄", 10000))
        assertEquals(3, estimateWrapLines("가\n나\n다", 10000))
    }

    @Test
    fun `열 폭을 넘치는 장문은 접힌 줄수로 센다 - 한글은 2칸`() {
        // 폭 5000 = 19.5칸 ≈ 한글 9자. 한글 20자는 40칸 → 3줄.
        val korean20 = "가".repeat(20)
        assertEquals(3, estimateWrapLines(korean20, 5000))
        // 같은 글자수여도 ASCII는 반 폭 — 20칸 → 2줄.
        assertEquals(2, estimateWrapLines("a".repeat(20), 5000))
    }

    @Test
    fun `상한 4줄을 넘지 않는다`() {
        assertEquals(WRAP_MAX_LINES, estimateWrapLines("가".repeat(4000), 5000))
        assertEquals(WRAP_MAX_LINES, estimateWrapLines("줄\n".repeat(40), 10000))
    }

    // ── customFieldColumnWidth (V-8 — 타입별 기본 너비) ──

    @Test
    fun `쉼표 목록이 가장 넓고 짧은 값 타입이 가장 좁다`() {
        assertEquals(8000, customFieldColumnWidth(FieldType.MULTI_TEXT, multiToken = true))
        assertEquals(8000, customFieldColumnWidth(FieldType.TEXT, multiToken = true))
        assertEquals(4000, customFieldColumnWidth(FieldType.NUMBER, multiToken = false))
        assertEquals(4000, customFieldColumnWidth(FieldType.GRADE, multiToken = false))
        assertEquals(4500, customFieldColumnWidth(FieldType.SELECT, multiToken = false))
        assertEquals(4500, customFieldColumnWidth(FieldType.CALCULATED, multiToken = false))
        assertEquals(6000, customFieldColumnWidth(FieldType.TEXT, multiToken = false))
        assertEquals(6000, customFieldColumnWidth(null, multiToken = false))
    }

    // ── SheetTabColors (P-8 — 그룹 배정의 완전성) ──

    @Test
    fun `모든 예약 시트가 정확히 한 그룹에 속한다 - 새 시트는 여기서 배정을 요구받는다`() {
        val groups = listOf(
            setOf(GUIDE_SHEET_NAME),
            SheetTabColors.STRUCTURE_SHEETS,
            SheetTabColors.RECORD_SHEETS,
            SheetTabColors.TOOL_SHEETS,
            SheetTabColors.DERIVED_SHEETS,
            SheetTabColors.CHARACTER_SHEETS
        )
        // 서로 겹치지 않는다
        val union = mutableSetOf<String>()
        var total = 0
        for (g in groups) { union += g; total += g.size }
        assertEquals("그룹이 겹친다", total, union.size)
        // 예약명 전수를 덮는다 — 덜 덮으면 새 예약 시트가 등재 없이 캐릭터색으로 조용히 빠진 것이다
        assertEquals(RESERVED_SHEET_NAMES, union)
    }

    @Test
    fun `예약명 밖 시트는 캐릭터색이다`() {
        assertTrue(SheetTabColors.forSheet("아르카디아 대륙").contentEquals(SheetTabColors.CHARACTERS))
        assertTrue(SheetTabColors.forSheet("세계관(2)").contentEquals(SheetTabColors.CHARACTERS))
        assertTrue(SheetTabColors.forSheet(UNCLASSIFIED_SHEET_NAME).contentEquals(SheetTabColors.CHARACTERS))
        assertTrue(SheetTabColors.forSheet(GUIDE_SHEET_NAME).contentEquals(SheetTabColors.GUIDE))
        assertTrue(SheetTabColors.forSheet("전체 캐릭터").contentEquals(SheetTabColors.DERIVED))
    }

    private fun assertEquals(expected: Double, actual: Double?) =
        org.junit.Assert.assertEquals(expected, actual!!, 0.0)
}
