package com.novelcharacter.app.excel

import java.io.File
import java.io.FileOutputStream
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 셀 텍스트 한도 절단이 **실제 xlsx 왕복에서** 살아남는가 (B-230 ⓓ — EC-3 하류).
 *
 * ## 왜 순수 시험만으로는 모자랐는가
 *
 * EC-3은 `truncateForCell`을 신설해 32,767자 절단이 서러게이트 쌍을 반쪽 내지 않게 했고,
 * `SheetValueConventionsTest`가 그 **함수**를 잰다. 그런데 감사의 비평가가 짚은 것은 그 아래다 —
 * *"자른 값을 진짜로 파일에 써서 다시 열어도 같은가"*. 그 축은 순수 시험이 원리적으로 못 본다:
 *
 * - POI가 셀에 실을 때 **자기 한도 검사**를 따로 돈다(넘으면 예외). 우리 절단이 그 한도와
 *   한 글자라도 어긋나면 내보내기가 통째로 죽는다.
 * - xlsx는 XML이라 **짝 잃은 서러게이트를 담을 수 없다.** 반쪽이 남은 채 저장되면 파일이
 *   깨지거나 되읽기가 다른 글자를 준다 — 즉 *쓰는 순간*이 아니라 *여는 순간* 드러난다.
 *
 * 그래서 이 시험은 판정(순수)이 아니라 **산출물**을 잰다. 하네스가 POI를 들고 있어 가능한
 * 자리이며, 같은 처방을 `ExportWorkbookParityTest`가 먼저 냈다.
 */
class CellTextLimitRoundtripTest {

    private fun writeAndReopen(value: String): String {
        val file = File.createTempFile("cell-limit", ".xlsx").also { it.deleteOnExit() }
        XSSFWorkbook().use { wb ->
            val row = wb.createSheet("s").createRow(0)
            row.createCell(0).setCellValue(truncateForCell(value))
            FileOutputStream(file).use { wb.write(it) }
        }
        return XSSFWorkbook(file).use { it.getSheetAt(0).getRow(0).getCell(0).stringCellValue }
    }

    @Test
    fun atLimit_survivesRealFileRoundtrip() {
        val exact = "가".repeat(EXCEL_CELL_TEXT_LIMIT)
        val read = writeAndReopen(exact)
        assertEquals("한도 딱 맞는 값이 왕복에서 길이가 달라졌다", EXCEL_CELL_TEXT_LIMIT, read.length)
        assertEquals(exact, read)
    }

    @Test
    fun overLimit_isAcceptedByPoiAndReadsBack() {
        // POI는 한도를 넘기면 예외를 던진다 — 우리 절단이 그 한도와 같은 값이어야 통과한다.
        val over = "a".repeat(EXCEL_CELL_TEXT_LIMIT + 500)
        val read = writeAndReopen(over)
        assertEquals(EXCEL_CELL_TEXT_LIMIT, read.length)
    }

    /**
     * **이 판이 세우려던 그 자리다.** 잘림 지점 한가운데에 이모지를 놓는다 — 절단이 순진하면
     * 짝 잃은 상위 서러게이트가 마지막 글자로 남고, 그 글자는 xlsx XML에 담기지 못한다.
     */
    @Test
    fun surrogateAtBoundary_survivesRealFileRoundtrip() {
        val value = "a".repeat(EXCEL_CELL_TEXT_LIMIT - 1) + "😀" + "뒤"
        val cut = truncateForCell(value)
        assertEquals("반쪽을 떨궈 한 글자 짧아야 한다", EXCEL_CELL_TEXT_LIMIT - 1, cut.length)

        val read = writeAndReopen(value)
        assertEquals("실파일 왕복에서 길이가 달라졌다", cut.length, read.length)
        assertEquals(cut, read)
        assertFalse(
            "짝 잃은 상위 서러게이트가 파일을 지나 살아 나왔다",
            read.isNotEmpty() && read.last().isHighSurrogate()
        )
    }

    /** 경계 **바로 앞뒤**로도 밀어 본다 — 쌍이 한도에 걸치는 자리는 한 칸 차이로 갈린다. */
    @Test
    fun surrogateNearBoundary_eitherWholePairOrNeither() {
        for (offset in -2..2) {
            val head = EXCEL_CELL_TEXT_LIMIT - 1 + offset
            if (head < 0) continue
            val value = "a".repeat(head) + "😀" + "꼬리"
            val read = writeAndReopen(value)
            assertFalse(
                "offset=$offset 에서 반쪽 서러게이트가 남았다",
                read.isNotEmpty() && read.last().isHighSurrogate()
            )
            assertTrue("offset=$offset 에서 한도를 넘었다", read.length <= EXCEL_CELL_TEXT_LIMIT)
            // 쌍은 통째로 들어가거나 통째로 빠진다 — 반쪽만 들어가는 일이 없어야 한다.
            val pairs = Regex("😀").findAll(read).count()
            val highs = read.count { it.isHighSurrogate() }
            assertEquals("offset=$offset 에서 쌍이 쪼개졌다", pairs, highs)
        }
    }
}
