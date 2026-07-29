package com.novelcharacter.app.excel

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/**
 * **DOM 경로 ↔ 스트리밍 경로 동치 테스트 (B-8 / 색출 로드맵 6).**
 *
 * `StreamingXlsxReaderTest`가 "SAX 원시 추출 == POI 셀 접근"을 봤다면, 이 테스트는 그보다
 * 위 — 가져오기가 **실제로 쓰는 접근면**([ImportWorkbook]/[ImportSheet]/[ImportRow]) 전체가
 * 두 구현에서 같은 답을 내는지를 본다. [ExcelImportService] 5,700줄은 이 접근면만 쓰므로,
 * 여기서 동치가 성립하면 **가져오기 결과가 경로에 무관하다는 것이 실행으로 증명된다.**
 *
 * 로컬에서 Android 계층을 컴파일조차 할 수 없는 이 저장소에서, 이 테스트가 배선의 유일한
 * 실증 근거다(설계 문서 제약 1번: "스트리밍 리더는 순수 JVM 단위 테스트로 실검증 가능한
 * 형태여야 한다").
 */
class ImportSourceEquivalenceTest {

    private lateinit var file: File

    @Before fun setUp() { file = File.createTempFile("import-src", ".xlsx") }
    @After fun tearDown() { file.delete() }

    /** 가져오기가 실제로 만나는 값 종류를 두루 담은 워크북. 시트가 여럿인 것도 의도다. */
    private fun buildWorkbook(): XSSFWorkbook {
        val wb = XSSFWorkbook()

        val data = wb.createSheet("데이터")
        data.createRow(0).apply {
            createCell(0).setCellValue("이름")
            createCell(1).setCellValue("생일")
            createCell(2).setCellValue("이름")        // 중복 → 공유문자열
            createCell(3).setCellValue("  공백트림  ")
            createCell(4).setCellValue("코드")
        }
        data.createRow(1).apply {
            createCell(0).setCellValue("홍길동")
            createCell(1).setCellValue(44000.0)       // 서식 없는 날짜 시리얼 → dateHint가 갈리는 자리
            createCell(2).setCellValue(3.14)
            createCell(3).setCellValue(-7.0)
            createCell(4).setCellValue(true)
        }
        data.createRow(2).apply {
            val style = wb.createCellStyle()
            style.dataFormat = wb.createDataFormat().getFormat("yyyy-mm-dd")
            val dateCell = createCell(0)
            dateCell.cellStyle = style
            dateCell.setCellValue(Calendar.getInstance().apply { clear(); set(2023, Calendar.JUNE, 15) }.time)
            // 1·2열 미생성(열 공백)
            createCell(3).setCellValue("")            // 빈 문자열
            createCell(5).setCellValue("끝")
        }
        // 선행 0 보존 서식("000") — B-7이 세운 원문 보존 규칙이 두 경로에서 같아야 한다
        data.createRow(3).apply {
            val zero = wb.createCellStyle()
            zero.dataFormat = wb.createDataFormat().getFormat("000")
            val c = createCell(0)
            c.cellStyle = zero
            c.setCellValue(7.0)
            createCell(1).setCellValue(1234567.0)
            createCell(2).setCellValue(1.0E20)        // 과학표기 금지 대상
        }

        val second = wb.createSheet("두번째")
        second.createRow(0).apply { createCell(0).setCellValue("머리글") }
        second.createRow(1).apply { createCell(0).setCellValue("값") }

        return wb
    }

    private fun writeAndOpen(wb: XSSFWorkbook): Pair<DomImportWorkbook, StreamingImportWorkbook> {
        FileOutputStream(file).use { wb.write(it) }
        val dom = DomImportWorkbook(XSSFWorkbook(file))
        val streaming = StreamingImportWorkbook(file)
        return dom to streaming
    }

    /** 두 원본을 접근면 전체로 훑어 대조한다. [dateHint]는 호출부가 정하는 값이라 양쪽 다 본다. */
    private fun assertEquivalent(dom: ImportWorkbook, streaming: ImportWorkbook, dateHint: Boolean) {
        assertEquals("시트 수", dom.numberOfSheets, streaming.numberOfSheets)
        for (i in 0 until dom.numberOfSheets) {
            assertEquals("시트 이름[$i]", dom.getSheetName(i), streaming.getSheetName(i))
            val name = dom.getSheetName(i)

            val ds = dom.getSheet(name)!!
            val ss = streaming.getSheet(name)!!
            assertEquals("[$name] sheetName", ds.sheetName, ss.sheetName)
            assertEquals("[$name] lastRowNum", ds.lastRowNum, ss.lastRowNum)
            assertEquals("[$name] 병합 범위 수", ds.numMergedRegions, ss.numMergedRegions)
            val dRegions = (0 until ds.numMergedRegions).map { ds.getMergedRegion(it) }.toSet()
            val sRegions = (0 until ss.numMergedRegions).map { ss.getMergedRegion(it) }.toSet()
            assertEquals("[$name] 병합 범위", dRegions, sRegions)

            for (r in 0..ds.lastRowNum) {
                val dr = ds.getRow(r)
                val sr = ss.getRow(r)
                if (dr == null) {
                    assertNull("[$name] 행 $r: DOM은 없는데 스트리밍에 있다", sr)
                    continue
                }
                assertNotNull("[$name] 행 $r: DOM엔 있는데 스트리밍에 없다", sr)
                assertEquals("[$name] 행 $r rowNum", dr.rowNum, sr!!.rowNum)
                assertEquals("[$name] 행 $r lastCellNum", dr.lastCellNum, sr.lastCellNum)

                // 열은 DOM 기준 폭 + 여유까지 훑어, 한쪽에만 있는 셀도 잡는다.
                for (c in 0 until (dr.lastCellNum.toInt().coerceAtLeast(0) + 2)) {
                    val dv = dr.getCell(c)?.let { ExcelCellValue.normalize(it.primitives(), dateHint) } ?: ""
                    val sv = sr.getCell(c)?.let { ExcelCellValue.normalize(it.primitives(), dateHint) } ?: ""
                    assertEquals("[$name] ($r,$c) dateHint=$dateHint 값 불일치", dv, sv)
                }
            }
        }
    }

    @Test fun 두_경로가_셀_단위로_같다_dateHint_false() {
        val wb = buildWorkbook()
        val (dom, streaming) = writeAndOpen(wb)
        wb.close()
        streaming.use { assertEquivalent(dom, it, dateHint = false) }
    }

    /**
     * **회귀 잠금 — 배선 착수 전 실제로 갈라지던 자리.**
     *
     * 종전 `StreamingXlsxReader`는 `dateHint = false`를 못 박고 값을 문자열로 굳혔다.
     * 그대로 배선했다면 서식 없는 날짜 시리얼(44000)이 든 생일 열이 DOM에서는 날짜로,
     * 스트리밍에서는 `44000`으로 들어와 **같은 파일이 경로에 따라 다른 데이터**가 됐다.
     * 이 테스트가 그 자리를 잠근다.
     */
    @Test fun 두_경로가_셀_단위로_같다_dateHint_true() {
        val wb = buildWorkbook()
        val (dom, streaming) = writeAndOpen(wb)
        wb.close()
        streaming.use { assertEquivalent(dom, it, dateHint = true) }
    }

    /** dateHint가 실제로 값을 바꾸는 셀이 표본에 들어 있어야 위 테스트가 의미를 갖는다. */
    @Test fun dateHint가_값을_바꾸는_셀이_표본에_있다() {
        val wb = buildWorkbook()
        val (_, streaming) = writeAndOpen(wb)
        wb.close()
        streaming.use { s ->
            val cell = s.getSheet("데이터")!!.getRow(1)!!.getCell(1)!!
            val off = ExcelCellValue.normalize(cell.primitives(), dateHint = false)
            val on = ExcelCellValue.normalize(cell.primitives(), dateHint = true)
            assertEquals("44000", off)
            assertTrue("dateHint=true면 날짜로 해석돼야 한다 (실제: $on)", on.contains("-"))
        }
    }

    /**
     * 병합 셀은 스트리밍이 **아예 못 보던** 것이다(`mergeCells` 미파싱). 못 보면 피복 칸이
     * 빈칸으로 읽혀 '빈칸=삭제' 규약에 걸리고, 화면에서 걸쳐 보이던 값이 무통보 유실된다(B-7).
     */
    @Test fun 병합_범위가_두_경로에서_같다() {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("병합")
        sheet.createRow(0).apply { createCell(0).setCellValue("머리글"); createCell(1).setCellValue("둘") }
        sheet.createRow(1).apply { createCell(0).setCellValue("좌상단") }
        sheet.createRow(2).apply { createCell(2).setCellValue("바깥") }
        sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress(1, 2, 0, 1))

        val (dom, streaming) = writeAndOpen(wb)
        wb.close()
        streaming.use { s ->
            val ds = dom.getSheet("병합")!!
            val ss = s.getSheet("병합")!!
            assertEquals(1, ds.numMergedRegions)
            assertEquals("스트리밍이 mergeCells를 읽어야 한다", ds.numMergedRegions, ss.numMergedRegions)
            assertEquals(ds.getMergedRegion(0), ss.getMergedRegion(0))
            assertEquals(MergedCellMap.Region(1, 2, 0, 1), ss.getMergedRegion(0))
            assertEquivalent(dom, s, dateHint = false)
        }
    }

    /**
     * **회귀 잠금 — 자기 재공격이 잡은 두 번째 결함.**
     *
     * 빈 셀(`<c/>`)과 빈 행(`<row/>`)은 값이 없으니 버려도 된다고 보기 쉽다. 실제로 첫 구현이
     * 그렇게 했고, 그 결과 같은 파일에서 `lastCellNum`이 DOM=4 / 스트리밍=3이 되고 빈 행은
     * 한쪽에만 존재했다. **값은 양쪽 다 ""라 값 대조로는 잡히지 않는다** — 갈라지는 것은
     * 행의 '모양'이고, 호출부의 `getRow(i) ?: continue`와 `0 until lastCellNum`이 그 모양 위에
     * 서 있다. 경로에 따라 도는 횟수가 달라지면 그 위의 모든 판정이 달라진다.
     */
    @Test fun 빈_셀과_빈_행의_모양이_두_경로에서_같다() {
        val wb = XSSFWorkbook()
        val s = wb.createSheet("S")
        s.createRow(0).apply {
            createCell(0).setCellValue("이름")
            createCell(1)                    // 값 없는 셀
            createCell(2).setCellValue("나이")
            createCell(3)                    // 뒤쪽 빈 셀 — lastCellNum이 여기까지 세야 한다
        }
        s.createRow(1).apply { createCell(0); createCell(1) }   // 빈 셀만 있는 행
        s.createRow(2)                                          // 셀이 아예 없는 행
        s.createRow(3).apply { createCell(0).setCellValue("홍길동") }

        val (dom, streaming) = writeAndOpen(wb)
        wb.close()
        streaming.use { st ->
            val ds = dom.getSheet("S")!!
            val ss = st.getSheet("S")!!
            assertEquals("lastRowNum", ds.lastRowNum, ss.lastRowNum)
            for (r in 0..ds.lastRowNum) {
                val dr = ds.getRow(r)
                val sr = ss.getRow(r)
                assertEquals("행 $r 존재 여부", dr != null, sr != null)
                assertEquals("행 $r lastCellNum", dr?.lastCellNum, sr?.lastCellNum)
            }
            assertEquals("뒤쪽 빈 셀까지 세어야 한다", 4.toShort(), ss.getRow(0)!!.lastCellNum)
            assertEquals("셀 없는 행은 -1", (-1).toShort(), ss.getRow(2)!!.lastCellNum)
            assertEquivalent(dom, st, dateHint = false)
        }
    }

    @Test fun 없는_시트는_두_경로_모두_null() {
        val wb = buildWorkbook()
        val (dom, streaming) = writeAndOpen(wb)
        wb.close()
        streaming.use { s ->
            assertNull(dom.getSheet("없는시트"))
            assertNull(s.getSheet("없는시트"))
        }
    }

    /**
     * 시트를 번갈아 읽어도 값이 유지되는가 — 스트리밍은 **시트 하나만** 들고 있다가 교체하므로,
     * 되돌아온 시트를 다시 적재하지 못하면 조용히 빈 시트가 된다(가장 그럴듯한 회귀 경로).
     */
    @Test fun 시트를_오가며_읽어도_값이_같다() {
        val wb = buildWorkbook()
        val (dom, streaming) = writeAndOpen(wb)
        wb.close()
        streaming.use { s ->
            val order = listOf("데이터", "두번째", "데이터", "두번째", "데이터")
            for (name in order) {
                val d = dom.getSheet(name)!!
                val t = s.getSheet(name)!!
                assertEquals("[$name] lastRowNum", d.lastRowNum, t.lastRowNum)
                for (r in 0..d.lastRowNum) {
                    val drow = d.getRow(r)
                    val trow = t.getRow(r)
                    val dv = drow?.getCell(0)?.let { ExcelCellValue.normalize(it.primitives(), false) } ?: ""
                    val tv = trow?.getCell(0)?.let { ExcelCellValue.normalize(it.primitives(), false) } ?: ""
                    assertEquals("[$name] ($r,0) 재적재 후 값 불일치", dv, tv)
                }
            }
        }
    }

    /**
     * 헤더만 읽는 경로(시트 정체 판정 R-7)가 **시트 전체 적재와 같은 0행**을 주는가.
     * 두 경로가 갈리면 시트 판별이 경로에 따라 달라져 R-7이 무너진다.
     */
    @Test fun 헤더만_읽어도_전체_적재와_같은_0행이다() {
        val wb = buildWorkbook()
        val (dom, streaming) = writeAndOpen(wb)
        wb.close()
        streaming.use { s ->
            // 아직 아무 시트도 적재하지 않은 상태에서 0행부터 읽는다(헤더 전용 경로).
            val headerOnly = (0..4).map {
                s.getSheet("데이터")!!.getRow(0)!!.getCell(it)
                    ?.let { c -> ExcelCellValue.normalize(c.primitives(), false) } ?: ""
            }
            // 이제 같은 시트를 통째로 적재시킨 뒤 다시 0행을 읽는다.
            s.getSheet("데이터")!!.getRow(1)
            val afterLoad = (0..4).map {
                s.getSheet("데이터")!!.getRow(0)!!.getCell(it)
                    ?.let { c -> ExcelCellValue.normalize(c.primitives(), false) } ?: ""
            }
            val domHeader = (0..4).map {
                dom.getSheet("데이터")!!.getRow(0)!!.getCell(it)
                    ?.let { c -> ExcelCellValue.normalize(c.primitives(), false) } ?: ""
            }
            assertEquals("헤더 전용 경로가 DOM과 달랐다", domHeader, headerOnly)
            assertEquals("적재 후 0행이 헤더 전용 경로와 달랐다", headerOnly, afterLoad)
        }
    }
}
