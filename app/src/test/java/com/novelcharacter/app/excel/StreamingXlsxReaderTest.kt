package com.novelcharacter.app.excel

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/**
 * StreamingXlsxReader ↔ DOM 동치 통합 테스트.
 *
 * 인메모리 워크북을 파일로 쓴 뒤, (1) 스트리밍 리더로 읽은 값과 (2) DOM(XSSFWorkbook)에서
 * ExcelCellValue로 정규화한 값을 **셀 단위로 대조**한다. 두 경로 모두 ExcelCellValue.normalize를
 * 태우므로, 이 테스트는 곧 **SAX 원시 추출이 POI Cell 접근과 동치**임을 강제한다(왕복 무결성 보증).
 * 순수 JVM — CI testDebugUnitTest에서 실행된다.
 */
class StreamingXlsxReaderTest {

    private lateinit var file: File

    @Before fun setUp() { file = File.createTempFile("stream-test", ".xlsx") }
    @After fun tearDown() { file.delete() }

    private fun buildWorkbook(): XSSFWorkbook {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("Data")

        // 0행: 헤더(문자열) — 일부는 중복되어 공유문자열 테이블(SST)을 타게 함
        sheet.createRow(0).apply {
            createCell(0).setCellValue("이름")
            createCell(1).setCellValue("나이")
            createCell(2).setCellValue("이름")   // 중복 → SST 공유
            createCell(3).setCellValue("  공백트림  ")
        }
        // 1행: 숫자(정수/소수/대형정수/음수) + 불리언
        sheet.createRow(1).apply {
            createCell(0).setCellValue(42.0)         // 정수
            createCell(1).setCellValue(3.14)         // 소수
            createCell(2).setCellValue(1234567.0)    // 대형정수 비지수
            createCell(3).setCellValue(-7.0)         // 음수
            createCell(4).setCellValue(true)         // 불리언 Y
        }
        // 2행: 날짜 서식 셀 + 열 공백(1,2 비움) + 빈 문자열
        sheet.createRow(2).apply {
            val style = wb.createCellStyle()
            style.dataFormat = wb.createDataFormat().getFormat("yyyy-mm-dd")
            val dateCell = createCell(0)
            dateCell.cellStyle = style
            dateCell.setCellValue(Calendar.getInstance().apply { clear(); set(2023, Calendar.JUNE, 15) }.time)
            // col 1,2 미생성(공백)
            createCell(3).setCellValue("")           // 빈 문자열 → 정규화 "" → 생략
            createCell(5).setCellValue("끝")         // 공백 뒤 값
        }
        return wb
    }

    /** ExcelCellValue를 통한 DOM 기대값(getCellString과 동일 로직). 빈 값은 맵에서 제외. */
    private fun domExpected(wb: XSSFWorkbook): Map<Int, Map<Int, String>> {
        val out = HashMap<Int, Map<Int, String>>()
        val sheet = wb.getSheet("Data")
        for (r in 0..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            val rowMap = HashMap<Int, String>()
            var c = 0
            while (c < row.lastCellNum) {
                val cell: Cell? = row.getCell(c)
                if (cell != null) {
                    val v = ExcelCellValue.normalize(ExcelCellValue.fromCell(cell), dateHint = false)
                    if (v.isNotEmpty()) rowMap[c] = v
                }
                c++
            }
            out[r] = rowMap
        }
        return out
    }

    @Test fun streamingMatchesDom_cellByCell() {
        val wb = buildWorkbook()
        FileOutputStream(file).use { wb.write(it) }
        val expected = domExpected(wb)
        wb.close()

        val streaming = HashMap<Int, Map<Int, String>>()
        StreamingXlsxReader(file).use { reader ->
            reader.readSheet("Data") { rowIndex, cells -> streaming[rowIndex] = HashMap(cells) }
        }

        // 존재하는 모든 행이 동일 맵인지 대조
        for ((r, exp) in expected) {
            assertEquals("행 $r 셀 맵 불일치", exp, streaming[r] ?: emptyMap<Int, String>())
        }
        // 스트리밍이 기대에 없는 행을 만들어내지 않았는지(빈 행은 콜백돼도 빈 맵)
        for ((r, got) in streaming) {
            assertEquals("행 $r 은 기대에 없거나 달라야 함", expected[r] ?: emptyMap<Int, String>(), got)
        }
    }

    @Test fun sharedStrings_resolveCorrectly() {
        val wb = buildWorkbook()
        FileOutputStream(file).use { wb.write(it) }
        wb.close()

        val streaming = HashMap<Int, Map<Int, String>>()
        StreamingXlsxReader(file).use { r -> r.readSheet("Data") { i, c -> streaming[i] = HashMap(c) } }

        // 0행 col0/col2 는 같은 문자열("이름")이 공유문자열로 저장됐어도 올바로 해석돼야 함
        assertEquals("이름", streaming[0]?.get(0))
        assertEquals("이름", streaming[0]?.get(2))
        assertEquals("공백트림", streaming[0]?.get(3)) // trim 적용
    }

    @Test fun numericAndDate_normalization() {
        val wb = buildWorkbook()
        FileOutputStream(file).use { wb.write(it) }
        wb.close()
        val streaming = HashMap<Int, Map<Int, String>>()
        StreamingXlsxReader(file).use { r -> r.readSheet("Data") { i, c -> streaming[i] = HashMap(c) } }

        assertEquals("42", streaming[1]?.get(0))        // 정수 소수점 없음
        assertEquals("3.14", streaming[1]?.get(1))
        assertEquals("1234567", streaming[1]?.get(2))   // 지수표기 아님
        assertEquals("-7", streaming[1]?.get(3))
        assertEquals("Y", streaming[1]?.get(4))         // 불리언
        assertEquals("2023-06-15", streaming[2]?.get(0)) // 날짜 서식
    }

    @Test fun emptyCellsAndGaps_omitted() {
        val wb = buildWorkbook()
        FileOutputStream(file).use { wb.write(it) }
        wb.close()
        val streaming = HashMap<Int, Map<Int, String>>()
        StreamingXlsxReader(file).use { r -> r.readSheet("Data") { i, c -> streaming[i] = HashMap(c) } }

        val row2 = streaming[2] ?: emptyMap()
        assertTrue("공백 열 1은 없어야 함", !row2.containsKey(1))
        assertTrue("공백 열 2는 없어야 함", !row2.containsKey(2))
        assertTrue("빈 문자열 열 3은 없어야 함", !row2.containsKey(3))
        assertEquals("끝", row2[5])
    }

    @Test fun sheetNames_listsSheet() {
        val wb = buildWorkbook()
        FileOutputStream(file).use { wb.write(it) }
        wb.close()
        StreamingXlsxReader(file).use { r ->
            assertEquals(listOf("Data"), r.sheetNames())
        }
    }

    @Test fun missingSheet_isNoop() {
        val wb = buildWorkbook()
        FileOutputStream(file).use { wb.write(it) }
        wb.close()
        var called = false
        StreamingXlsxReader(file).use { r -> r.readSheet("없는시트") { _, _ -> called = true } }
        assertTrue("없는 시트는 콜백 없음", !called)
    }
}
