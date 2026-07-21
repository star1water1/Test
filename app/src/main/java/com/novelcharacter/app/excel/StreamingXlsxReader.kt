package com.novelcharacter.app.excel

import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.model.StylesTable
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.Closeable
import java.io.File
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * .xlsx를 **전방향 SAX 스트리밍**으로 읽는 리더 — 파일 전체를 DOM(XSSFWorkbook)으로 적재하지 않아
 * 메모리가 파일 크기에 비례해 늘지 않는다(받쳐주는 확장성; OOM 방지).
 *
 * 셀 값은 원시 데이터(형식·원값·스타일서식)를 뽑아 **[ExcelCellValue]** 로 정규화한다 —
 * DOM 경로(getCellString)와 **완전히 같은 로직**을 태우므로 값 왜곡(변수 제어 위반)이 발생하지 않는다.
 * 이 동치는 [StreamingXlsxReader]와 DOM을 행 단위로 대조하는 통합 테스트로 강제된다.
 *
 * 사용: `StreamingXlsxReader(file).use { r -> r.readSheet(name) { rowIndex, cells -> ... } }`.
 * 각 행 콜백의 cells는 열 인덱스→정규화 문자열 맵이며, **빈 셀은 포함되지 않는다**
 * (DOM의 `getCell(idx) ?: return ""`과 동일 — 소비측은 `cells[idx] ?: ""`로 읽는다).
 */
class StreamingXlsxReader(file: File) : Closeable {

    private val pkg: OPCPackage
    private val reader: XSSFReader
    private val styles: StylesTable
    private val sharedStrings: ReadOnlySharedStringsTable
    private val date1904: Boolean

    init {
        // 생성 도중(손상 파일 등) 예외가 나면 이미 연 패키지가 파일 핸들을 누수하지 않도록 revert 후 재던짐.
        pkg = OPCPackage.open(file, PackageAccess.READ)
        try {
            reader = XSSFReader(pkg)
            styles = reader.stylesTable
            sharedStrings = ReadOnlySharedStringsTable(pkg)
            date1904 = readDate1904(reader)
        } catch (e: Exception) {
            try { pkg.revert() } catch (_: Exception) { /* best-effort */ }
            throw e
        }
    }

    /** 워크북에 존재하는 시트 이름 목록(순서 보존). */
    fun sheetNames(): List<String> {
        val names = ArrayList<String>()
        val it = reader.sheetsData as XSSFReader.SheetIterator
        while (it.hasNext()) {
            it.next().close()        // 스트림은 즉시 닫고 이름만 수집
            names.add(it.sheetName)
        }
        return names
    }

    /**
     * 이름이 [sheetName]인 시트를 전방향으로 읽어 행마다 [onRow]를 호출한다.
     * 해당 시트가 없으면 아무 것도 하지 않는다(호출측이 존재 여부를 별도 판단).
     * @param onRow (0-기반 행 인덱스, 열 인덱스→정규화 값 맵)
     */
    fun readSheet(sheetName: String, onRow: (rowIndex: Int, cells: Map<Int, String>) -> Unit) {
        val it = reader.sheetsData as XSSFReader.SheetIterator
        while (it.hasNext()) {
            val stream = it.next()
            if (it.sheetName == sheetName) {
                stream.use { parseSheet(it, onRow) }
                return
            } else {
                stream.close()
            }
        }
    }

    private fun parseSheet(stream: InputStream, onRow: (Int, Map<Int, String>) -> Unit) {
        newSafeSaxFactory().newSAXParser().parse(InputSource(stream), SheetHandler(onRow))
    }

    override fun close() {
        pkg.revert() // 읽기 전용 — 변경 없이 닫는다
    }

    /**
     * DTD/외부 엔티티를 끈 SAX 파서 팩토리. `disallow-doctype-decl`은 Android 기본 파서가
     * 인식하지 못해 예외를 던질 수 있으므로 best-effort로 시도한다(시트 XML엔 DTD가 없어 미적용도 안전).
     */
    private fun newSafeSaxFactory(): SAXParserFactory {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = false
        for (feature in arrayOf(
            "http://apache.org/xml/features/disallow-doctype-decl",
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities"
        )) {
            try {
                f.setFeature(feature, feature.endsWith("disallow-doctype-decl"))
            } catch (_: Exception) { /* 미지원 파서 — 무시 */ }
        }
        return f
    }

    /** xl/workbook.xml의 workbookPr@date1904를 읽어 1904 날짜계를 판정(기본 false=1900계). */
    private fun readDate1904(reader: XSSFReader): Boolean {
        return try {
            reader.workbookData.use { input ->
                var result = false
                newSafeSaxFactory().newSAXParser().parse(InputSource(input), object : DefaultHandler() {
                    override fun startElement(uri: String?, local: String?, qName: String, a: Attributes?) {
                        if (qName == "workbookPr" || qName.endsWith(":workbookPr")) {
                            val v = a?.getValue("date1904")
                            result = v == "1" || v.equals("true", ignoreCase = true)
                        }
                    }
                })
                result
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 시트 XML SAX 핸들러. `<row>`/`<c>`/`<v>`/`<is>…<t>`를 읽어 원시 셀 데이터를 [ExcelCellValue]로
     * 정규화한다. 빈 셀·열 공백은 자연히 생략된다(맵에 미포함).
     */
    private inner class SheetHandler(
        private val onRow: (Int, Map<Int, String>) -> Unit
    ) : DefaultHandler() {

        private var rowIndex = -1
        private var cells = HashMap<Int, String>()

        // 현재 셀 상태
        private var colIndex = -1
        private var cellType = ""       // t 속성: "s","str","b","e","inlineStr", 또는 "" (숫자)
        private var styleIndex = -1     // s 속성
        private var inValue = false     // <v> 안
        private var inText = false      // <is> 내부 <t> 안(인라인 문자열)
        private val buf = StringBuilder()        // 현재 <v>/<t> 텍스트 노드
        private val inlineBuf = StringBuilder()  // 인라인 문자열의 모든 <t> 런 누적

        override fun startElement(uri: String?, local: String?, qName: String, a: Attributes?) {
            when (qName) {
                "row" -> {
                    rowIndex = (a?.getValue("r")?.toIntOrNull() ?: (rowIndex + 2)) - 1 // r은 1-기반
                    cells = HashMap()
                }
                "c" -> {
                    colIndex = colOf(a?.getValue("r"))
                    cellType = a?.getValue("t") ?: ""
                    styleIndex = a?.getValue("s")?.toIntOrNull() ?: -1
                    buf.setLength(0)
                    inlineBuf.setLength(0)
                }
                "v" -> { inValue = true; buf.setLength(0) }
                "t" -> { inText = true; buf.setLength(0) }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inValue || inText) buf.append(ch, start, length)
        }

        override fun endElement(uri: String?, local: String?, qName: String) {
            when (qName) {
                "v" -> inValue = false
                "t" -> { inText = false; inlineBuf.append(buf) } // 런 누적(rich text 다중 <t> 대응)
                "c" -> {
                    if (colIndex >= 0) {
                        val value = normalizeCell()
                        if (value.isNotEmpty()) cells[colIndex] = value
                    }
                }
                "row" -> if (rowIndex >= 0) onRow(rowIndex, cells)
            }
        }

        /** 현재 셀의 원시 데이터를 ExcelCellValue.Primitives로 만들어 정규화. */
        private fun normalizeCell(): String {
            val p = when (cellType) {
                "s" -> {
                    // 공유 문자열 — buf는 SST 인덱스
                    val idx = buf.toString().trim().toIntOrNull() ?: return ""
                    val s = try { sharedStrings.getItemAt(idx).string } catch (_: Exception) { return "" }
                    ExcelCellValue.Primitives(CellType.STRING, stringValue = s)
                }
                "inlineStr" -> ExcelCellValue.Primitives(CellType.STRING, stringValue = inlineBuf.toString())
                "str" -> // 수식의 문자열 결과 — 캐시된 문자열. getCellString FORMULA(문자열결과)와 동일 값.
                    ExcelCellValue.Primitives(CellType.STRING, stringValue = buf.toString())
                "b" -> ExcelCellValue.Primitives(CellType.BOOLEAN, booleanValue = buf.toString().trim() == "1")
                "e" -> ExcelCellValue.Primitives(CellType.ERROR)
                else -> { // 숫자(t 없음 또는 "n")
                    val num = buf.toString().trim().toDoubleOrNull() ?: return ""
                    numericPrimitives(num)
                }
            }
            return ExcelCellValue.normalize(p, dateHint = false)
        }

        private fun numericPrimitives(num: Double): ExcelCellValue.Primitives {
            // 스타일 서식 해소: DOM의 cell.cellStyle.dataFormatString / isCellDateFormatted와 동치.
            val style = try {
                styles.getStyleAt(if (styleIndex >= 0) styleIndex else 0)
            } catch (_: Exception) { null }
            val fmtIndex = style?.dataFormat?.toInt() ?: 0
            val fmtString = style?.dataFormatString ?: "General"
            val isDate = try { DateUtil.isADateFormat(fmtIndex, fmtString) } catch (_: Exception) { false }
            val javaDate = try { DateUtil.getJavaDate(num, date1904) } catch (_: Exception) { null }
            return ExcelCellValue.Primitives(
                type = CellType.NUMERIC,
                numericValue = num,
                javaDate = javaDate,
                isDateFormatted = isDate,
                dataFormatString = fmtString
            )
        }
    }

    private companion object {
        /** "A1"/"BC12" 등 셀 참조에서 0-기반 열 인덱스 추출. 참조 없으면 -1. */
        fun colOf(ref: String?): Int {
            if (ref.isNullOrEmpty()) return -1
            var col = 0
            var seen = false
            for (c in ref) {
                when (c) {
                    in 'A'..'Z' -> { col = col * 26 + (c - 'A' + 1); seen = true }
                    in 'a'..'z' -> { col = col * 26 + (c - 'a' + 1); seen = true }
                    else -> break
                }
            }
            return if (seen) col - 1 else -1
        }
    }
}
