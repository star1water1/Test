package com.novelcharacter.app.excel

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * ExcelCellValue(가져오기 셀 값 정규화의 단일 소스) 계약 테스트.
 *
 * 실제 POI Cell을 인메모리 워크북으로 만들어, fromCell+normalize가 기존 getCellString과
 * 동일한 문자열을 내는지(왕복 무결성 — 값 왜곡 방지) 검증한다. SAX 스트리밍 경로도 같은
 * ExcelCellValue를 태우므로, 이 계약이 곧 두 경로의 동치 보증이다.
 */
class ExcelCellValueTest {

    private lateinit var wb: XSSFWorkbook

    @Before fun setUp() { wb = XSSFWorkbook() }
    @After fun tearDown() { wb.close() }

    /** 시트/행에 셀 하나를 만들어 반환하는 헬퍼. */
    private fun cell(configure: (Cell) -> Unit): Cell {
        val sheet = wb.createSheet("s" + wb.numberOfSheets)
        val row = sheet.createRow(0)
        val c = row.createCell(0)
        configure(c)
        return c
    }

    private fun normalize(c: Cell, dateHint: Boolean = false): String =
        ExcelCellValue.normalize(ExcelCellValue.fromCell(c), dateHint)

    @Test fun string_isTrimmed() {
        assertEquals("hello", normalize(cell { it.setCellValue("  hello  ") }))
    }

    @Test fun string_empty() {
        assertEquals("", normalize(cell { it.setCellValue("") }))
    }

    @Test fun numeric_integerHasNoDecimalPoint() {
        assertEquals("42", normalize(cell { it.setCellValue(42.0) }))
    }

    @Test fun numeric_negativeInteger() {
        assertEquals("-7", normalize(cell { it.setCellValue(-7.0) }))
    }

    @Test fun numeric_decimalKept() {
        assertEquals("3.14", normalize(cell { it.setCellValue(3.14) }))
    }

    @Test fun numeric_largeIntegerNotScientific() {
        // 정수형은 toLong 경유 — 지수표기 없이 그대로.
        assertEquals("1234567", normalize(cell { it.setCellValue(1234567.0) }))
    }

    @Test fun boolean_trueIsY() {
        assertEquals("Y", normalize(cell { it.setCellValue(true) }))
    }

    @Test fun boolean_falseIsN() {
        assertEquals("N", normalize(cell { it.setCellValue(false) }))
    }

    @Test fun dateFormatted_yieldsYyyyMmDd() {
        val c = cell {
            val style = wb.createCellStyle()
            style.dataFormat = wb.createDataFormat().getFormat("yyyy-mm-dd")
            it.cellStyle = style
            val cal = Calendar.getInstance().apply { clear(); set(2023, Calendar.JUNE, 15) }
            it.setCellValue(cal.time)
        }
        assertEquals("2023-06-15", normalize(c))
    }

    @Test fun numericGeneral_noDateHint_isPlainNumber() {
        // 서식 없는(General) 정수 셀은 dateHint=false에서 숫자 그대로 — getCellString과 동일.
        assertEquals("44000", normalize(cell { it.setCellValue(44000.0) }, dateHint = false))
    }

    @Test fun numericGeneral_withDateHint_aggressiveDateDetection() {
        // dateHint=true + General + 유효 시리얼 정수는 날짜로 적극 감지된다(생일 필드 동작).
        // getCellString의 isCellLikelyDate dateHint 분기와 동일 — 숫자 문자열이 아니라 날짜 형식이 된다.
        val result = normalize(cell { it.setCellValue(44000.0) }, dateHint = true)
        // 시리얼 44000 → 날짜 문자열(YYYY-MM-DD, TZ 의존). 숫자 "44000"이 **아님**을 확인.
        assertEquals(true, result != "44000")
        assertEquals(true, Regex("""\d{1,4}-\d{2}-\d{2}""").matches(result))
    }

    @Test fun blankCell_normalizesToEmpty() {
        // BLANK 셀(값 미설정)은 "" — fromCell/normalize가 else 분기로 처리.
        assertEquals("", normalize(cell { /* no value */ }))
    }
}
