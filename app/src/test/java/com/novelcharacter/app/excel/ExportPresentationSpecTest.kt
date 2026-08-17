package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldType
import org.apache.poi.ss.usermodel.CellType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ── cellStyleKindFor (셀 스타일 종류의 우선순위) ──

    @Test
    fun `읽기전용은 서식을 가리지 않는다 - 밀리초와 CALCULATED 소수`() {
        assertEquals(
            CellStyleKind.READ_ONLY_MILLIS,
            cellStyleKindFor(ColumnSpec("생성일", readOnly = true, millis = true), fractionalCalc = false)
        )
        // 전 열이 읽기전용인 '전체 캐릭터'의 CALCULATED 소수가 "78.5"로 보이던 자리 —
        // 시행 형태의 "앱 표시와 글자까지 동일"(확정 16장 Q-1)은 읽기전용 열에서도 성립해야 한다.
        assertEquals(
            CellStyleKind.READ_ONLY_CALC_DECIMAL,
            cellStyleKindFor(ColumnSpec("전투력", readOnly = true, calc = true), fractionalCalc = true)
        )
        assertEquals(
            CellStyleKind.READ_ONLY,
            cellStyleKindFor(ColumnSpec("코드", readOnly = true), fractionalCalc = false)
        )
    }

    @Test
    fun `편집 열의 종류 판정 - 소수 CALCULATED·밀리초·wrap·기본`() {
        assertEquals(
            CellStyleKind.CALC_DECIMAL,
            cellStyleKindFor(ColumnSpec("전투력", calc = true), fractionalCalc = true)
        )
        // 정수 CALCULATED는 서식 없이도 앱과 같은 글자다(FormulaDisplay.format의 정수 갈래) — PLAIN.
        assertEquals(
            CellStyleKind.PLAIN,
            cellStyleKindFor(ColumnSpec("전투력", calc = true), fractionalCalc = false)
        )
        assertEquals(
            CellStyleKind.MILLIS,
            cellStyleKindFor(ColumnSpec("뗀날짜", millis = true), fractionalCalc = false)
        )
        assertEquals(
            CellStyleKind.WRAP,
            cellStyleKindFor(ColumnSpec("설명", wrap = true), fractionalCalc = false)
        )
        assertEquals(CellStyleKind.PLAIN, cellStyleKindFor(ColumnSpec("이름"), fractionalCalc = false))
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

    // ── 글꼴 (P-10 — 2026.08.17 사용자 지시 "엑셀파일의 폰트를 고딕으로") ──

    /**
     * 글꼴은 **두 자리**에 걸리고 한쪽만으로는 워크북이 덮이지 않는다
     * ([applyExportBaseFont] + [createExportFont]). 이 시험이 그 둘을 **재현하지 않고 부른다** —
     * `ExcelStyles`가 private이라 시험이 닿지 않는데, 규칙을 베껴 적으면 그 사본이 곧 두 벌이 된다.
     *
     * 세 모양을 함께 재는 것이 요점이다. ⓐ 스타일이 아예 없는 셀 ⓑ 폰트를 걸지 않은 스타일의
     * 셀(지금의 `dataStyle` 비-읽기전용 갈래·`guideBody`가 그 모양이다) ⓒ 명시 폰트를 건 셀.
     * ⓐ·ⓑ는 기본 폰트가, ⓒ는 [createExportFont]가 덮는다 — **한 자리를 지우면 그 몫이 빨간불이다.**
     *
     * 파일에 써서 되읽는 것도 요점이다. 메모리 위에서만 보면 통과하고 파일에서 갈리는 축이
     * 스트리밍에 실재한다([ExportWorkbooks] 머리 — 창을 넘긴 행은 디스크로 흘러간다).
     */
    @Test
    fun `내보낸 파일의 모든 셀이 고딕이다 - DOM과 스트리밍 양쪽`() {
        for (streaming in listOf(false, true)) {
            val label = if (streaming) "스트리밍" else "DOM"
            val workbook = ExportWorkbooks.create(streaming)
            val file: java.io.File
            try {
                applyExportBaseFont(workbook)

                val styledFont = createExportFont(workbook).apply { bold = true }
                val withFont = workbook.createCellStyle().apply { setFont(styledFont) }
                // 폰트를 걸지 않은 스타일 — 지금의 데이터 셀이 정확히 이 모양이다
                val withoutFont = workbook.createCellStyle().apply {
                    verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.TOP
                }

                val row = workbook.createSheet("글꼴").createRow(0)
                row.createCell(0).setCellValue("스타일 없음")
                row.createCell(1).apply { setCellValue("명시 폰트"); cellStyle = withFont }
                row.createCell(2).apply { setCellValue("폰트 없는 스타일"); cellStyle = withoutFont }

                file = java.io.File.createTempFile("font", ".xlsx")
                java.io.FileOutputStream(file).use { workbook.write(it) }
            } finally {
                ExportWorkbooks.release(workbook)
            }

            try {
                org.apache.poi.xssf.usermodel.XSSFWorkbook(file).use { back ->
                    val row = back.getSheetAt(0).getRow(0)
                    for (col in 0..2) {
                        val cell = row.getCell(col)
                        val font = back.getFontAt(cell.cellStyle.fontIndex)
                        assertEquals(
                            "$label: '${cell.stringCellValue}' 칸이 고딕이 아니다",
                            EXPORT_FONT_NAME, font.fontName
                        )
                    }
                }
            } finally {
                file.delete()
            }
        }
    }

    /** 기본 폰트를 고치지 않으면 POI 기본값이 남는다 — 위 시험의 두 자리가 왜 둘인지의 근거. */
    @Test
    fun `기본 폰트를 고치지 않으면 셀이 POI 기본값을 쓴다`() {
        val workbook = ExportWorkbooks.create(streaming = false)
        try {
            // 일부러 applyExportBaseFont를 부르지 않는다
            assertNotEquals(EXPORT_FONT_NAME, workbook.getFontAt(0).fontName)
            // createFont()가 기본값을 물려주지 않는다는 것도 함께 — createExportFont가 필요한 이유다
            assertNotEquals(EXPORT_FONT_NAME, workbook.createFont().fontName)
            assertEquals(EXPORT_FONT_NAME, createExportFont(workbook).fontName)
        } finally {
            ExportWorkbooks.release(workbook)
        }
    }

    private fun assertEquals(expected: Double, actual: Double?) =
        org.junit.Assert.assertEquals(expected, actual!!, 0.0)
}
