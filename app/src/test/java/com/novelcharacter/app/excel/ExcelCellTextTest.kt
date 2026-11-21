package com.novelcharacter.app.excel

import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFRichTextString
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * 셀 글자의 쓰기·읽기 한 쌍(R-72)과 그 이웃 — 2026.11.21 실측 결함 셋.
 *
 * ① XML에 실을 수 없는 글자가 `?`가 되던 것 ② `_xHHHH_` 꼴 글자가 다른 글자가 되고 두 읽기 경로가
 * 갈리던 것 ③ 15자리를 넘는 수가 외부 편집기에서 잘리던 것. **실제 POI로 파일을 쓰고 두 경로로
 * 읽어** 잠근다 — 순수 함수의 성질만 보면 "POI가 실제로 그렇게 읽는가"가 빠진다.
 */
class ExcelCellTextTest {

    private val files = mutableListOf<File>()
    private fun tmp(): File = File.createTempFile("cell-text", ".xlsx").also { files.add(it) }

    @Before fun setUp() { files.clear() }
    @After fun tearDown() { files.forEach { it.delete() } }

    /** 결함을 낸 실제 모양들 — 반례(`_x0041` + 실을 수 없는 글자)를 포함한다. */
    private val samples = listOf(
        "평범한 글", "", "x\u0001y", "Word 줄바꿈\u000B다음 줄", "a\u0000b", "x\u001Fy", "x\uFFFEy", "x\uFFFFy",
        "_x0041_", "user_xcafe_", "C:\\_x005F_\\a", "_x005F_x0041_", "__x0041__", "_x0041\u0001", "_x0041_x0042_",
        "_X0041_", "_x12_", "a\tb", "a\rb", "a\r\nb", "x\u007Fy", "😀 이모지", "lone\uD83Dhigh", "lone\uDE00low",
        "end high\uD83D", "_xD83D_", "_x0041__x0042_", "_", "_x", "_x004", "_x0041"
    )

    // ── 순수 성질 ─────────────────────────────────────────────────────────

    @Test
    fun `쓰고 읽으면 원래 값이다`() {
        for (s in samples) assertEquals(show(s), show(ExcelCellText.decode(ExcelCellText.encode(s))))
    }

    @Test
    fun `바꿔 쓸 것이 없으면 원문 그대로다`() {
        for (s in listOf("평범한 글", "a\tb", "a\r\nb", "_x", "_X0041_", "😀", "x_y")) {
            assertFalse(s, ExcelCellText.needsEncoding(s))
            assertEquals(s, ExcelCellText.encode(s))
        }
    }

    @Test
    fun `반례 — 날 밑줄 뒤 표기가 이어져도 다른 글자로 읽히지 않는다`() {
        // `_x0041` 뒤의 \u0001은 `_x0001_`로 적힌다. 앞 밑줄을 이스케이프하지 않으면 `_x0041_`이 생겨 'A'로 읽힌다.
        val enc = ExcelCellText.encode("_x0041\u0001")
        assertEquals("_x005F_x0041_x0001_", enc)
        assertEquals("_x0041\u0001", ExcelCellText.decode(enc))
    }

    @Test
    fun `무작위 글자도 쓰고 읽으면 원래 값이다`() {
        val alphabet = "_xX0123456789aAfF_g\u0001\u000B\u0000\uFFFE\uD83D\uDE00 가".toCharArray()
        val rnd = Random(20261121)
        repeat(20_000) {
            val s = String(CharArray(rnd.nextInt(0, 16)) { alphabet[rnd.nextInt(alphabet.size)] })
            assertEquals(show(s), show(ExcelCellText.decode(ExcelCellText.encode(s))))
        }
    }

    @Test
    fun `읽기는 POI의 해석과 글자 단위로 같다`() {
        // DOM 경로는 POI가, 스트리밍 경로는 이 함수가 푼다 — 둘이 갈리면 같은 파일이 크기에 따라 다른 값이 된다.
        val poi = XSSFRichTextString::class.java.getDeclaredMethod("utfDecode", String::class.java)
            .apply { isAccessible = true }
        val alphabet = "_xX0123456789aAfFgG5_".toCharArray()
        val rnd = Random(7)
        val inputs = samples.map { ExcelCellText.encode(it) } + samples + List(20_000) {
            String(CharArray(rnd.nextInt(0, 20)) { alphabet[rnd.nextInt(alphabet.size)] })
        }
        for (s in inputs) assertEquals(s, show(poi.invoke(null, s) as String), show(ExcelCellText.decode(s)))
    }

    @Test
    fun `한도는 적은 뒤의 길이이고 표기와 짝 글자를 쪼개지 않는다`() {
        val s = "ab\u0001😀_x0041_c"   // a b [_x0001_] 😀 [_x005F_]x0041_ c
        for (limit in 0..40) {
            val e = ExcelCellText.encodeWithin(s, limit)
            assertTrue("limit=$limit", e.text.length <= limit)
            val back = ExcelCellText.decode(e.text)
            assertTrue("limit=$limit → ${show(back)}", s.startsWith(back))
            assertFalse("짝 잃은 앞짝이 끝에 남지 않는다", back.isNotEmpty() && Character.isHighSurrogate(back.last()))
            assertEquals(e.text.length < ExcelCellText.encode(s).length, e.truncated)
        }
    }

    @Test
    fun `바꿔 쓸 것이 없으면 절단은 종전과 같다`() {
        val long = "가".repeat(40_000)
        val e = ExcelCellText.encodeWithin(long, EXCEL_CELL_TEXT_LIMIT)
        assertTrue(e.truncated)
        assertEquals(truncateForCell(long, EXCEL_CELL_TEXT_LIMIT), e.text)
    }

    // ── 실제 파일 — 두 쓰기 방식 × 두 읽기 경로 ─────────────────────────────

    private fun writeSamples(wb: Workbook, f: File) {
        val sh = wb.createSheet("s")
        samples.forEachIndexed { i, v -> sh.createRow(i).createCell(0).setCellValue(ExcelCellText.encode(v)) }
        FileOutputStream(f).use { wb.write(it) }
    }

    private fun readAll(wb: ImportWorkbook): List<String> {
        val s = wb.getSheetAt(0)
        return samples.indices.map { s.getRow(it)?.getCell(0)?.primitives()?.stringValue ?: "" }
    }

    @Test
    fun `일반·스트리밍 내보내기 모두 두 읽기 경로에서 원래 값이다`() {
        val xs = tmp().also { f -> XSSFWorkbook().use { writeSamples(it, f) } }
        val sx = tmp().also { f -> SXSSFWorkbook(null, 100, false, false).let { writeSamples(it, f); it.dispose() } }
        for ((name, f) in listOf("XSSF" to xs, "SXSSF" to sx)) {
            val dom = XSSFWorkbook(f).use { readAll(DomImportWorkbook(it)) }
            val stream = StreamingImportWorkbook(f).use { readAll(it) }
            samples.forEachIndexed { i, v ->
                assertEquals("$name DOM #$i", show(v), show(dom[i]))
                assertEquals("$name 스트리밍 #$i", show(v), show(stream[i]))
            }
        }
    }

    @Test
    fun `종전처럼 적은 파일은 이제 두 경로가 같게 읽는다`() {
        // 쓰기 규약 없이 적힌 옛 파일(스트리밍 내보내기 = 인라인 문자열)과 수식 결과·여러 런.
        // 종전 스트리밍은 셋 다 글자 그대로 읽어 DOM과 갈렸다.
        val base = tmp().also { f -> SXSSFWorkbook(null, 100, false, false).let { w ->
            w.createSheet("s").createRow(0).createCell(0).setCellValue("seed"); FileOutputStream(f).use { w.write(it) }; w.dispose()
        } }
        val crafted = tmp()
        ZipFile(base).use { zin ->
            ZipOutputStream(FileOutputStream(crafted)).use { zout ->
                for (e in zin.entries()) {
                    zout.putNextEntry(java.util.zip.ZipEntry(e.name))
                    var bytes = zin.getInputStream(e).readBytes()
                    if (e.name == "xl/worksheets/sheet1.xml") {
                        val xml = String(bytes, Charsets.UTF_8)
                        val row = """<row r="1">""" +
                            """<c r="A1" t="inlineStr"><is><t>_x0041_</t></is></c>""" +
                            """<c r="B1" t="inlineStr"><is><r><t>_x0042_</t></r><r><t>z_x00</t></r><r><t>43_</t></r></is></c>""" +
                            """<c r="C1" t="str"><f>"q"</f><v>_x004a_</v></c>""" +
                            """<c r="D1" t="inlineStr"><is><t>_x005F_x0041_</t></is></c></row>"""
                        // 스트리밍 쓰기는 행마다 줄을 바꾼다 — 줄을 넘어 맞춘다. 바꿔 끼우지 못하면 시험이 아니므로 확인한다.
                        val replaced = xml.replace(Regex("(?s)<row r=\"1\".*?</row>"), row)
                        assertTrue("1행을 바꿔 끼웠다", replaced.contains("_x0042_"))
                        bytes = replaced.toByteArray(Charsets.UTF_8)
                    }
                    zout.write(bytes); zout.closeEntry()
                }
            }
        }
        fun cells(wb: ImportWorkbook) = (0..3).map { wb.getSheetAt(0).getRow(0)!!.getCell(it)!!.primitives().stringValue }
        val dom = XSSFWorkbook(crafted).use { cells(DomImportWorkbook(it)) }
        val stream = StreamingImportWorkbook(crafted).use { cells(it) }
        assertEquals(listOf("A", "BzC", "J", "_x0041_"), dom)
        assertEquals(dom, stream)
    }

    // ── 이웃: 시트 이름 · 드롭다운 ───────────────────────────────────────────

    @Test
    fun `시트 이름은 실을 수 없는 글자를 뺀다`() {
        assertEquals("세계관A", sanitizeSheetNameBase("세계관\u0001A"))
        assertEquals("짝잃은", sanitizeSheetNameBase("짝\uD83D잃은"))
        assertEquals("😀세계", sanitizeSheetNameBase("😀세계"))
        assertEquals("Sheet", sanitizeSheetNameBase("\u0001\u0002"))
        // 표기 규약이 없는 자리라 `_x` 꼴은 그대로 둔다(뺄 이유가 없다).
        assertEquals("a_x0041_", sanitizeSheetNameBase("a_x0041_"))
    }

    @Test
    fun `바꿔 써야 하는 값이 든 목록은 명시 목록에 싣지 않는다`() {
        assertTrue(DropdownListLimits.fitsExplicitList(listOf("가", "나")))
        assertFalse(DropdownListLimits.fitsExplicitList(listOf("가", "x\u0001y")))
        assertFalse(DropdownListLimits.fitsExplicitList(listOf("user_xcafe_")))
    }

    @Test
    fun `드롭다운 목록 시트도 같은 규약으로 적고 다른 목록은 다른 구간이다`() {
        XSSFWorkbook().use { wb ->
            val lists = DropdownListSheet("목록")
            val r1 = lists.referenceFor(wb, listOf("a\u0000b"))
            val r2 = lists.referenceFor(wb, listOf("a", "b"))
            assertNotEquals("NUL이 든 값과 두 값 목록이 한 구간이 되면 안 된다", r1, r2)
            val f = tmp(); FileOutputStream(f).use { wb.write(it) }
            val read = XSSFWorkbook(f).use { DomImportWorkbook(it).getSheet("목록")!!.getRow(0)!!.getCell(0)!!.primitives().stringValue }
            assertEquals(show("a\u0000b"), show(read ?: ""))
        }
    }

    // ── 숫자: 15자리 ───────────────────────────────────────────────────────

    @Test
    fun `15자리를 넘는 수는 글자 칸으로 싣는다`() {
        assertEquals(123456789012345.0, numericExportValueOrNull("123456789012345"))
        assertNull(numericExportValueOrNull("1234567890123456"))
        assertNull(numericExportValueOrNull("0.30000000000000004"))
        assertEquals(0.3, numericExportValueOrNull("0.3"))
        assertEquals(1.0E20, numericExportValueOrNull("100000000000000000000"))   // 유효 숫자는 1자리
        assertTrue(fitsSpreadsheetPrecision(1721834567890.0))
        assertFalse(fitsSpreadsheetPrecision(0.1 + 0.2))
    }

    private fun show(s: String) = s.map { c ->
        if (c.code < 0x20 || c.code == 0x7F || c.code >= 0xFFF0 || Character.isSurrogate(c)) "\\u%04X".format(c.code) else c.toString()
    }.joinToString("")
}
