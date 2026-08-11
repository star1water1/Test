package com.novelcharacter.app.excel

import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * 내보내기의 **두 구현이 같은 파일을 내는가** (B-72 · scalability S7).
 *
 * [ExportWorkbooks]가 DOM과 스트리밍을 둘 다 내주는 이유는 실기기 측정이 스트리밍의 대가를
 * 문제로 지목하면 한 인자로 되돌릴 수 있어야 하기 때문이다. **그 되돌리기가 안전한 것은
 * 두 구현의 산출이 같을 때뿐이고, 그것을 잠그는 것이 이 시험이다**(포맷 드리프트 방지).
 *
 * ## 왜 이 시험이 순수 JVM에 있는가
 *
 * [ExcelExporter]는 `Context`와 Room을 물어 순수 JVM이 닿지 않는다. 그래서 **그 파일이 쓰는
 * 방식**(행을 순서대로 만들고, 읽기 전용 칸은 그 행을 쓰는 자리에서 입히고, 시트 서식은
 * 마지막에 건다)을 여기서 같은 순서로 재현해 두 워크북에 시킨다. 재현이지 호출이 아니므로
 * **쓰는 방식이 바뀌면 이 시험도 함께 고쳐야 한다** — 그 사실 자체가 이 파일의 계약이다.
 *
 * ## 이 시험이 실제로 잡은 것
 *
 * *"다 쓴 뒤 되돌아가 스타일을 입힌다"*(종전 `applyReadOnlyColumn`)를 스트리밍에 그대로 태우면
 * **창을 넘긴 행에 조용히 아무 일도 하지 않는다.** [스트리밍은 되돌아갈 수 없다]가 그 모양을
 * 고정한다 — 오류가 나지 않고 회색만 사라지므로, 이 시험이 없으면 빠뜨렸다는 사실을 알 방법이
 * 없다(자동 검증은 보이는 것을 못 본다).
 */
class ExportWorkbookParityTest {

    /** [ExcelExporter]의 읽기 전용 열 규약을 흉내낸 최소 명세 — 인덱스 1이 읽기 전용이다. */
    private val readOnlyColumns = setOf(1)

    /** 창(100행)보다 넉넉히 커야 이 시험이 뜻을 갖는다 — 넘기지 않으면 두 구현이 같아 보인다. */
    private val rowCount = 350

    private fun readOnlyStyle(workbook: Workbook): CellStyle =
        workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

    /** 현행 방식 — 행을 쓰는 자리에서 마무리한다([ExcelExporter.finishDataRow]와 같은 순서). */
    private fun writeSheet(workbook: Workbook, revisitRows: Boolean): File {
        val sheet = workbook.createSheet("데이터")
        val readOnly = readOnlyStyle(workbook)
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("이름")
        header.createCell(1).setCellValue("코드")
        for (i in 1..rowCount) {
            val row = sheet.createRow(i)
            row.createCell(0).setCellValue("행$i")
            row.createCell(1).setCellValue("C$i")
            if (!revisitRows) finishRow(row, readOnly)
        }
        if (revisitRows) {
            // 종전 방식 — 다 쓴 뒤 되돌아간다. 스트리밍에서 무엇이 되는지를 보이기 위한 경로다.
            for (i in 1..rowCount) {
                val row = sheet.getRow(i) ?: continue
                finishRow(row, readOnly)
            }
        }
        // 시트 수준 서식은 셀을 만지지 않는다 — 둘 다 그대로 된다.
        sheet.createFreezePane(0, 1)
        sheet.setAutoFilter(CellRangeAddress(0, 0, 0, 1))
        sheet.setColumnWidth(0, 4000)
        val out = File.createTempFile("parity", ".xlsx")
        FileOutputStream(out).use { workbook.write(it) }
        return out
    }

    private fun finishRow(row: Row, readOnly: CellStyle) {
        for (col in readOnlyColumns) {
            val cell = row.getCell(col) ?: row.createCell(col)
            cell.cellStyle = readOnly
        }
    }

    /** 파일을 되읽어 **눈에 보이는 사실만** 추린다 — 값·회색 칸 수·시트 서식. */
    private data class Shape(
        val lastRowNum: Int,
        val values: List<String>,
        val styledCells: Int,
        val frozen: Boolean,
        val columnWidth: Int
    )

    private fun shapeOf(file: File): Shape = XSSFWorkbook(file).use { back ->
        val sheet: Sheet = back.getSheetAt(0)
        val values = ArrayList<String>(rowCount)
        var styled = 0
        for (i in 1..rowCount) {
            val row = sheet.getRow(i) ?: continue
            values.add(row.getCell(0)?.stringCellValue.orEmpty())
            val cell = row.getCell(1)
            if (cell != null && cell.cellStyle.fillPattern == FillPatternType.SOLID_FOREGROUND) styled++
        }
        Shape(
            lastRowNum = sheet.lastRowNum,
            values = values,
            styledCells = styled,
            frozen = sheet.paneInformation != null,
            columnWidth = sheet.getColumnWidth(0)
        )
    }

    private fun shape(streaming: Boolean, revisitRows: Boolean = false): Shape {
        val workbook = ExportWorkbooks.create(streaming)
        val file = try {
            writeSheet(workbook, revisitRows)
        } finally {
            // release는 임시 파일까지 놓는다 — 여기서 부르지 않으면 시험이 임시 파일을 쌓는다.
            ExportWorkbooks.release(workbook)
        }
        return try {
            shapeOf(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `두 구현이 같은 파일을 낸다`() {
        assertEquals(shape(streaming = false), shape(streaming = true))
    }

    @Test
    fun `두 구현 다 값을 하나도 빠뜨리지 않는다`() {
        val expected = (1..rowCount).map { "행$it" }
        assertEquals(expected, shape(streaming = false).values)
        assertEquals(expected, shape(streaming = true).values)
    }

    @Test
    fun `두 구현 다 읽기 전용 칸에 스타일이 붙는다`() {
        assertEquals(rowCount, shape(streaming = false).styledCells)
        assertEquals(rowCount, shape(streaming = true).styledCells)
    }

    @Test
    fun `두 구현 다 시트 서식이 붙는다`() {
        for (streaming in listOf(false, true)) {
            val shape = shape(streaming)
            assertTrue("streaming=$streaming 고정 창", shape.frozen)
            assertEquals("streaming=$streaming 열 너비", 4000, shape.columnWidth)
            assertEquals("streaming=$streaming 마지막 행", rowCount, shape.lastRowNum)
        }
    }

    @Test
    fun `스트리밍은 되돌아갈 수 없다 - 이 판이 쓰기 자리를 옮긴 이유`() {
        // DOM은 되돌아가도 전부 입혀진다 — 그래서 종전 코드가 멀쩡해 보였다.
        assertEquals(rowCount, shape(streaming = false, revisitRows = true).styledCells)
        // 스트리밍은 창에 남은 행에만 입혀진다. **오류가 아니라 회색이 없는 것**이라
        // 되돌아가는 방식으로는 빠뜨렸다는 사실 자체를 알 수 없다.
        val streamed = shape(streaming = true, revisitRows = true).styledCells
        assertTrue("창을 넘긴 행은 닿지 않는다 (실측 $streamed / $rowCount)", streamed < rowCount)
        assertEquals(ExportWorkbooks.ROW_WINDOW, streamed)
        // 값 자체는 온전하다 — 잃는 것이 데이터가 아니라 서식이라는 것이 이 결함의 성질이다.
        assertEquals(rowCount, shape(streaming = true, revisitRows = true).values.size)
        assertNotEquals(
            shape(streaming = false, revisitRows = true),
            shape(streaming = true, revisitRows = true)
        )
    }

    @Test
    fun `임시 파일은 지정한 자리에 생기고 놓으면 사라진다`() {
        // 안드로이드에서 `java.io.tmpdir`이 어디인지는 기기·판올림에 따라 달라지는 값이라,
        // 정하지 않으면 **앱이 지울 수도 용량을 셀 수도 없는 자리**에 백업 크기의 임시 파일이
        // 생긴다. 자리를 못박는 것과 놓는 것이 한 쌍이라 함께 잰다.
        val cacheDir = createTempDirectory()
        try {
            ExportWorkbooks.useTempDirectory(cacheDir)
            val poiTemp = File(cacheDir, "poi-temp")
            assertTrue("자리를 만든다", poiTemp.isDirectory)

            val workbook = ExportWorkbooks.create(streaming = true)
            val sheet = workbook.createSheet("데이터")
            // 창을 넘겨야 실제로 디스크로 흘려보낸다 — 넘기지 않으면 임시 파일이 없을 수 있다.
            for (i in 0..(ExportWorkbooks.ROW_WINDOW * 2)) {
                sheet.createRow(i).createCell(0).setCellValue("행$i")
            }
            val duringWrite = poiTemp.walkTopDown().count { it.isFile }
            assertTrue("흘려보낸 시트가 이 자리에 있다 (실측 $duringWrite)", duringWrite > 0)

            ExportWorkbooks.release(workbook)
            assertEquals("놓으면 남지 않는다", 0, poiTemp.walkTopDown().count { it.isFile })
        } finally {
            // POI의 임시 파일 전략은 **전역**이다 — 되돌리지 않고 이 자리를 지우면 뒤이어 도는
            // 시험이 임시 파일을 만들 자리를 잃는다(실제로 그렇게 깨졌다). 앱에서는 캐시 밑의
            // 자리가 그대로 남아 있어 이 문제가 없지만, 그 사실을 시험이 알아야 한다.
            org.apache.poi.util.TempFile.setTempFileCreationStrategy(
                org.apache.poi.util.DefaultTempFileCreationStrategy()
            )
            cacheDir.deleteRecursively()
        }
    }

    private fun createTempDirectory(): File {
        val dir = File.createTempFile("cache", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    @Test
    fun `대형 시트도 두 구현이 같다`() {
        // 창을 여러 번 넘기는 규모 — 스트리밍이 실제로 흘려보내는 상태에서 계약을 잰다.
        val big = 5_000
        fun bigShape(streaming: Boolean): Triple<Int, Int, String> {
            val workbook = ExportWorkbooks.create(streaming)
            val file: File
            try {
                val sheet = workbook.createSheet("큰 시트")
                val readOnly = readOnlyStyle(workbook)
                sheet.createRow(0).createCell(0).setCellValue("이름")
                for (i in 1..big) {
                    val row = sheet.createRow(i)
                    row.createCell(0).setCellValue("행$i")
                    row.createCell(1).setCellValue("C$i")
                    finishRow(row, readOnly)
                }
                file = File.createTempFile("parity-big", ".xlsx")
                FileOutputStream(file).use { workbook.write(it) }
            } finally {
                ExportWorkbooks.release(workbook)
            }
            return try {
                XSSFWorkbook(file).use { back ->
                    val sheet = back.getSheetAt(0)
                    var styled = 0
                    for (i in 1..big) {
                        val cell = sheet.getRow(i)?.getCell(1) ?: continue
                        if (cell.cellStyle.fillPattern == FillPatternType.SOLID_FOREGROUND) styled++
                    }
                    Triple(sheet.lastRowNum, styled, sheet.getRow(big).getCell(0).stringCellValue)
                }
            } finally {
                file.delete()
            }
        }
        assertEquals(Triple(big, big, "행$big"), bigShape(streaming = true))
        assertEquals(bigShape(streaming = false), bigShape(streaming = true))
    }
}
