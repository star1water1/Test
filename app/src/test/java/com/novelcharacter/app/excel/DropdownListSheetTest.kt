package com.novelcharacter.app.excel

import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * 긴 드롭다운 목록의 숨김 시트가 **실제 파일에 제대로 실리는가** (B-221).
 *
 * [DropdownListLimitsTest]가 잠그는 것은 *언제 이 길로 가는가*이고, 여기는 *가서 무엇이
 * 만들어지는가*다. 그 둘을 갈라 두는 이유는 뒤엣것이 **POI와 파일 왕복 없이는 못 재는 것**이라
 * 순수 산술 시험으로는 겉만 초록이 되기 때문이다.
 *
 * **두 구현 모두에 시킨다**(DOM·스트리밍 — [ExportWorkbooks]). 스트리밍은 창을 넘긴 행을
 * 버리므로, 목록을 열마다 나눠 되돌아가 쓰는 구현이었다면 **여기서만 값이 사라진다.**
 * 그것이 이 시험이 파리티 축을 함께 드는 이유다.
 */
class DropdownListSheetTest {

    private val sheetName = DROPDOWN_LIST_SHEET_NAME

    /** 명시 목록으로는 못 싣는 크기 — 판정과 같은 함수로 확인해 시험이 헛돌지 않게 한다. */
    private val longList = (1..80).map { "작품 $it" }
    private val otherList = (1..60).map { "세력 $it" }

    private fun write(workbook: Workbook, block: (Workbook) -> Unit): File {
        // 데이터 시트가 하나는 있어야 한다 — 전부 숨김인 워크북은 엑셀이 열지 못한다.
        workbook.createSheet("데이터").createRow(0).createCell(0).setCellValue("머리")
        block(workbook)
        val file = File.createTempFile("dropdown-list", ".xlsx")
        FileOutputStream(file).use { workbook.write(it) }
        (workbook as? SXSSFWorkbook)?.dispose()
        workbook.close()
        return file
    }

    @Test
    fun `긴 목록이라야 이 길로 온다`() {
        assertFalse(DropdownListLimits.fitsExplicitList(longList))
        assertFalse(DropdownListLimits.fitsExplicitList(otherList))
    }

    @Test
    fun `쓰지 않으면 시트가 생기지 않는다`() {
        // 필요 없는 export에까지 시트가 붙으면 **모든 산출물이 바뀐다** — 그 자체가 결함이다.
        val lists = DropdownListSheet(sheetName)
        val file = write(XSSFWorkbook()) { }
        assertFalse(lists.created)
        WorkbookFactory.create(file).use { assertEquals(1, it.numberOfSheets) }
        file.delete()
    }

    @Test
    fun `두 구현 모두에서 숨김 시트와 참조가 같다`() {
        for (streaming in listOf(false, true)) {
            val lists = DropdownListSheet(sheetName)
            var refA = ""
            var refB = ""
            var refAgain = ""
            val file = write(ExportWorkbooks.create(streaming)) { wb ->
                refA = lists.referenceFor(wb, longList)
                refB = lists.referenceFor(wb, otherList)
                refAgain = lists.referenceFor(wb, longList)
            }

            // 같은 목록은 다시 적지 않는다 — 열 곳이 함께 쓰는 목록이 열 벌 실리면 안 된다.
            assertEquals("streaming=$streaming", refA, refAgain)
            assertEquals("streaming=$streaming", longList.size + otherList.size, lists.rowsWritten)
            // 두 목록은 겹치지 않는 구간이다.
            assertEquals("'$sheetName'!\$A\$1:\$A\$${longList.size}", refA)
            assertEquals(
                "'$sheetName'!\$A\$${longList.size + 1}:\$A\$${longList.size + otherList.size}",
                refB
            )

            WorkbookFactory.create(file).use { reread ->
                val idx = reread.getSheetIndex(sheetName)
                assertTrue("streaming=$streaming: 시트가 없다", idx >= 0)
                assertTrue("streaming=$streaming: 숨겨지지 않았다", reread.isSheetHidden(idx))
                val sheet = reread.getSheetAt(idx)
                // **참조가 가리키는 칸에 실제로 그 값이 있는가** — 여기가 이 시험의 요점이다.
                // 되돌아가 쓰는 구현이었다면 스트리밍에서 앞 구간이 비어 있다.
                assertEquals(longList.size + otherList.size - 1, sheet.lastRowNum)
                assertEquals(longList.first(), sheet.getRow(0).getCell(0).stringCellValue)
                assertEquals(longList.last(), sheet.getRow(longList.size - 1).getCell(0).stringCellValue)
                assertEquals(otherList.first(), sheet.getRow(longList.size).getCell(0).stringCellValue)
                assertEquals(
                    otherList.last(),
                    sheet.getRow(longList.size + otherList.size - 1).getCell(0).stringCellValue
                )
            }
            file.delete()
        }
    }

    @Test
    fun `가져오기는 이 시트를 예약명으로 알아본다`() {
        // 예약명이 아니면 가져오기가 "인식되지 않아 무시되었습니다" 경고를 낸다 —
        // 앱이 만든 시트를 앱이 낯설다고 말하는 자리가 된다.
        assertTrue(DROPDOWN_LIST_SHEET_NAME in RESERVED_SHEET_NAMES)
    }

    @Test
    fun `범위 참조는 유효성 검사 수식으로 실린다`() {
        val lists = DropdownListSheet(sheetName)
        var ref = ""
        val wb = XSSFWorkbook()
        val data = wb.createSheet("데이터")
        data.createRow(0).createCell(0).setCellValue("머리")
        ref = lists.referenceFor(wb, longList)
        val helper = data.dataValidationHelper
        data.addValidationData(
            helper.createValidation(
                helper.createFormulaListConstraint(ref),
                org.apache.poi.ss.util.CellRangeAddressList(1, 50, 0, 0)
            )
        )
        val file = File.createTempFile("dropdown-dv", ".xlsx")
        FileOutputStream(file).use { wb.write(it) }
        wb.close()

        WorkbookFactory.create(file).use { reread ->
            val dv = reread.getSheet("데이터").dataValidations
            assertEquals(1, dv.size)
            val formula = dv[0].validationConstraint.formula1
            assertNotNull(formula)
            // POI가 시트명 따옴표를 다듬을 수 있으므로 구간 자체로 견준다.
            assertTrue(formula, formula.contains("\$A\$1:\$A\$${longList.size}"))
            assertTrue(formula, formula.contains(sheetName))
        }
        file.delete()
    }
}
