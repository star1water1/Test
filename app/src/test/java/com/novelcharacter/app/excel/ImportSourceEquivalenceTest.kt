package com.novelcharacter.app.excel

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType
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
import java.util.Locale

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
            dateCell.setCellValue(Calendar.getInstance(Locale.US).apply { clear(); set(2023, Calendar.JUNE, 15) }.time)
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
                    val dc = dr.getCell(c)
                    val sc = sr.getCell(c)
                    val dv = dc?.let { ExcelCellValue.normalize(it.primitives(), dateHint) } ?: ""
                    val sv = sc?.let { ExcelCellValue.normalize(it.primitives(), dateHint) } ?: ""
                    assertEquals("[$name] ($r,$c) dateHint=$dateHint 값 불일치", dv, sv)
                    // cellType은 '값의 타입' 계약(B-218) — 수식 셀에서 DOM이 FORMULA를 그대로
                    // 내면 코드 열 숫자 경고(F4) 같은 타입 판정이 경로마다 갈린다.
                    assertEquals("[$name] ($r,$c) cellType 불일치", dc?.cellType, sc?.cellType)
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
     * **B-218 ① — 헤더가 0행에 없는 파일.** 위에 행을 끼워 넣어 헤더가 2행으로 밀리면
     * **0행은 두 경로 모두에서 null이어야 한다.** 종전 스트리밍은 **첫 물리 행을 0행인 척**
     * 돌려줘 같은 파일이 경로에 따라 다른 결과가 됐다.
     *
     * **⚠️ 이 시험이 잠그는 것은 *행 번호의 동치*이지 *그 파일이 거부된다*가 아니다**
     * (B-231 ⓑ에서 갈렸다). 그런 파일은 이제 [SheetResolver.locateHeader]가 **찾아서
     * 받아들인다** — 다만 그 판정도 *0행이 무엇인가*가 두 경로에서 같다는 위에 서므로,
     * 이 단언은 그대로 유효하고 오히려 더 중요해졌다.
     */
    @Test fun 헤더가_0행에_없으면_두_경로_모두_0행이_null이다() {
        val wb = XSSFWorkbook()
        val s = wb.createSheet("밀린헤더")
        s.createRow(2).apply { createCell(0).setCellValue("이름"); createCell(1).setCellValue("나이") }
        s.createRow(3).apply { createCell(0).setCellValue("홍길동"); createCell(1).setCellValue(20.0) }

        val (dom, streaming) = writeAndOpen(wb)
        wb.close()
        streaming.use { st ->
            // 적재 전 헤더 전용 경로부터 — 종전 결함은 정확히 이 첫 호출에서 첫 물리 행(2행)을 내줬다.
            assertNull("DOM: 0행이 물리적으로 없다", dom.getSheet("밀린헤더")!!.getRow(0))
            assertNull("스트리밍: 첫 물리 행을 0행인 척 주면 안 된다", st.getSheet("밀린헤더")!!.getRow(0))
            // 실제 헤더 행(2행)은 두 경로 모두 그대로 읽힌다.
            val dv = dom.getSheet("밀린헤더")!!.getRow(2)!!.getCell(0)!!
                .let { ExcelCellValue.normalize(it.primitives(), false) }
            val sv = st.getSheet("밀린헤더")!!.getRow(2)!!.getCell(0)!!
                .let { ExcelCellValue.normalize(it.primitives(), false) }
            assertEquals("이름", dv)
            assertEquals("이름", sv)
            assertEquivalent(dom, st, dateHint = false)
        }
    }

    /**
     * **B-218 ④ — 수식 셀의 캐시 결과.** 종전 DOM 경로는 불리언·오류·빈 캐시를 전부 숫자
     * 폴백으로 보내 **"0"을 날조**했다(#DIV/0!가 숫자 0으로). 스트리밍은 Y/N·""를 내므로
     * 흔한 경로(DOM) 쪽이 왜곡이었다. **절대값까지 단언한다** — 두 경로가 같이 틀리면
     * 동치 대조만으로는 초록이 된다.
     */
    @Test fun 수식_캐시_값이_두_경로에서_같고_0으로_날조되지_않는다() {
        val wb = XSSFWorkbook()
        val s = wb.createSheet("수식")
        s.createRow(0).apply { createCell(0).setCellValue("머리") }
        // 캐시(<c t>·<v>)를 직접 싣는다 — 엑셀·외부 생성기가 저장하는 파일의 형태 그대로이고,
        // POI 수식 평가기는 하네스에 없는 commons-math3를 끌어 여기서 못 쓴다.
        val row = s.createRow(1)
        fun formulaCell(col: Int, formula: String, t: STCellType.Enum?, v: String?) {
            val c = row.createCell(col) as org.apache.poi.xssf.usermodel.XSSFCell
            c.cellFormula = formula
            if (t != null) c.ctCell.t = t
            if (v != null) c.ctCell.v = v
        }
        formulaCell(0, "TRUE()", STCellType.B, "1")
        formulaCell(1, "FALSE()", STCellType.B, "0")
        formulaCell(2, "1/0", STCellType.E, "#DIV/0!")                 // 오류 캐시
        formulaCell(3, "1+1", null, "2")                               // 숫자 캐시(t 기본값 n)
        formulaCell(4, "CONCATENATE(\" 합\",\"침 \")", STCellType.STR, " 합침 ") // 문자열 캐시(트림까지)
        // 캐시(<v>)가 안 실린 수식 — 외부 생성기가 평가 없이 쓴 파일의 꼴.
        // XSSF는 이것도 cachedFormulaResultType=NUMERIC이라 타입만 믿으면 "0"이 재현된다.
        formulaCell(5, "7*6", null, null)

        val (dom, streaming) = writeAndOpen(wb)
        wb.close()
        streaming.use { st ->
            val dr = dom.getSheet("수식")!!.getRow(1)!!
            val sr = st.getSheet("수식")!!.getRow(1)!!
            val expected = listOf("Y", "N", "", "2", "합침", "")
            for (c in expected.indices) {
                val dv = dr.getCell(c)?.let { ExcelCellValue.normalize(it.primitives(), false) } ?: ""
                val sv = sr.getCell(c)?.let { ExcelCellValue.normalize(it.primitives(), false) } ?: ""
                assertEquals("DOM ($c)", expected[c], dv)
                assertEquals("스트리밍 ($c)", expected[c], sv)
            }
            // 값 타입 계약 — FORMULA가 아니라 캐시 결과의 타입이 나와야 F4류 타입 판정이 안 갈린다.
            val types = listOf(
                org.apache.poi.ss.usermodel.CellType.BOOLEAN,
                org.apache.poi.ss.usermodel.CellType.BOOLEAN,
                org.apache.poi.ss.usermodel.CellType.ERROR,
                org.apache.poi.ss.usermodel.CellType.NUMERIC,
                org.apache.poi.ss.usermodel.CellType.STRING,
                org.apache.poi.ss.usermodel.CellType.BLANK
            )
            for (c in types.indices) {
                assertEquals("DOM cellType ($c)", types[c], dr.getCell(c)!!.cellType)
                assertEquals("스트리밍 cellType ($c)", types[c], sr.getCell(c)!!.cellType)
            }
            assertEquivalent(dom, st, dateHint = false)
        }
    }

    /**
     * **B-218 ② — 공유 문자열의 후리가나(phonetic run).** DOM(XSSFRichTextString.getString)은
     * `<rPh>`를 본문에 붙이지 않는데, 스트리밍의 종전 `ReadOnlySharedStringsTable(pkg)`는
     * 기본값이 포함이라 일본어 엑셀에서 편집된 파일의 값 뒤에 읽음이 이어붙었다.
     */
    @Test fun 공유_문자열의_후리가나가_두_경로_모두_값에_붙지_않는다() {
        val wb = XSSFWorkbook()
        val s = wb.createSheet("후리가나")
        s.createRow(0).apply { createCell(0).setCellValue("漢字") }
        FileOutputStream(file).use { wb.write(it) }
        wb.close()
        // POI는 phonetic run을 만들지 못하므로 일본어 엑셀이 저장한 형태를 수술로 재현한다.
        rewriteZipEntry("xl/sharedStrings.xml") {
            it.replace(
                "<t>漢字</t>",
                "<t>漢字</t><rPh sb=\"0\" eb=\"2\"><t>かんじ</t></rPh><phoneticPr fontId=\"0\"/>"
            )
        }
        val dom = DomImportWorkbook(XSSFWorkbook(file))
        val streaming = StreamingImportWorkbook(file)
        streaming.use { st ->
            val dv = dom.getSheet("후리가나")!!.getRow(0)!!.getCell(0)!!
                .let { ExcelCellValue.normalize(it.primitives(), false) }
            val sv = st.getSheet("후리가나")!!.getRow(0)!!.getCell(0)!!
                .let { ExcelCellValue.normalize(it.primitives(), false) }
            assertEquals("DOM은 본문만 읽는다", "漢字", dv)
            assertEquals("스트리밍도 본문만 읽어야 한다", "漢字", sv)
            assertEquivalent(dom, st, dateHint = false)
        }
    }

    /**
     * **B-218 ③ — `r` 속성 없는 `<c>` 셀.** 셀 참조는 선택 속성이라 생략하는 생성기가 있다.
     * DOM(XSSFCell)은 "지금까지의 최대 열 + 1"에 배정하는데(POI 5.3.0 실측 — 명시 열 뒤에
     * 오면 그 다음 열), 종전 스트리밍 SAX는 그 셀을 통째로 버려 행 내용이 경로마다 갈렸다.
     */
    @Test fun r_속성_없는_셀이_두_경로에서_같은_열에_배정된다() {
        val wb = XSSFWorkbook()
        val s = wb.createSheet("R없음")
        s.createRow(0).apply {
            createCell(0).setCellValue("하나"); createCell(1).setCellValue("둘"); createCell(2).setCellValue("셋")
        }
        s.createRow(1).apply {
            createCell(0).setCellValue("넷"); createCell(1).setCellValue("다섯"); createCell(2).setCellValue("여섯")
        }
        s.createRow(2).apply {
            createCell(2).setCellValue("바깥"); createCell(3).setCellValue("추가")
        }
        FileOutputStream(file).use { wb.write(it) }
        wb.close()
        // 0행은 B1·C1만, 1행은 전부, 2행은 명시 열(C3) 뒤의 D3만 r을 벗긴다.
        rewriteZipEntry("xl/worksheets/sheet1.xml") {
            it.replace(" r=\"B1\"", "").replace(" r=\"C1\"", "")
                .replace(" r=\"A2\"", "").replace(" r=\"B2\"", "").replace(" r=\"C2\"", "")
                .replace(" r=\"D3\"", "")
        }
        val dom = DomImportWorkbook(XSSFWorkbook(file))
        val streaming = StreamingImportWorkbook(file)
        streaming.use { st ->
            val ss = st.getSheet("R없음")!!
            // 절대값 — 버려지면 값이 ""로 접혀 동치 대조가 "둘 다 없음"으로 초록이 될 수 있다.
            assertEquals("둘", ss.getRow(0)!!.getCell(1)!!.let { ExcelCellValue.normalize(it.primitives(), false) })
            assertEquals("여섯", ss.getRow(1)!!.getCell(2)!!.let { ExcelCellValue.normalize(it.primitives(), false) })
            assertEquals("명시 열 다음에 배정", "추가", ss.getRow(2)!!.getCell(3)!!.let { ExcelCellValue.normalize(it.primitives(), false) })
            assertEquivalent(dom, st, dateHint = false)
        }
    }

    /** [file]의 zip 항목 하나를 문자열 변환으로 고쳐 쓴다 — 외부 생성기·편집기가 만든 형태의 재현용. */
    private fun rewriteZipEntry(entryName: String, transform: (String) -> String) {
        val out = java.io.ByteArrayOutputStream()
        var rewritten = false
        java.util.zip.ZipInputStream(file.inputStream()).use { zin ->
            java.util.zip.ZipOutputStream(out).use { zout ->
                var e = zin.nextEntry
                while (e != null) {
                    zout.putNextEntry(java.util.zip.ZipEntry(e.name))
                    val content = zin.readBytes()
                    if (e.name == entryName) {
                        val changed = transform(String(content, Charsets.UTF_8))
                        assertTrue("수술이 대상을 실제로 바꿔야 한다: $entryName", changed != String(content, Charsets.UTF_8))
                        zout.write(changed.toByteArray(Charsets.UTF_8))
                        rewritten = true
                    } else {
                        zout.write(content)
                    }
                    zout.closeEntry()
                    e = zin.nextEntry
                }
            }
        }
        assertTrue("zip 항목이 존재해야 한다: $entryName", rewritten)
        file.writeBytes(out.toByteArray())
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
