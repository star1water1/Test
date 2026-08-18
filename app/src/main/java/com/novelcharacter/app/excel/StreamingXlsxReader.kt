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
            // includePhoneticRuns=false — 기본 생성자는 후리가나(<rPh>)를 본문에 이어붙이는데,
            // DOM(XSSFRichTextString.getString)은 붙이지 않는다. 일본어 엑셀에서 편집된 파일의
            // 공유 문자열이 스트리밍에서만 다른 값이 되는 갈림이다(B-218 ②).
            sharedStrings = ReadOnlySharedStringsTable(pkg, false)
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
     *
     * `dateHint = false` 고정 정규화이므로 **가져오기 배선은 이 API를 쓰지 않는다** —
     * 필드 의미에 따라 dateHint가 달라지기 때문이다([readSheetRaw] 참조).
     */
    fun readSheet(sheetName: String, onRow: (rowIndex: Int, cells: Map<Int, String>) -> Unit) {
        readSheetRaw(sheetName) { rowIndex, cells ->
            val normalized = HashMap<Int, String>(cells.size)
            for ((col, p) in cells) {
                val v = ExcelCellValue.normalize(p, dateHint = false)
                if (v.isNotEmpty()) normalized[col] = v
            }
            onRow(rowIndex, normalized)
        }
    }

    /**
     * [readSheet]와 같되 셀을 **정규화 이전의 [ExcelCellValue.Primitives]로** 방출하고,
     * 시트의 병합 범위 목록을 돌려준다.
     *
     * **왜 정규화를 미루는가:** `dateHint`는 리더가 아니라 **호출부가 필드 의미로** 정한다
     * (생일 같은 날짜 필드에서만 true). 리더가 미리 문자열로 굳히면 그 자리에서 dateHint를
     * 알 수 없어, 서식 없는 날짜 시리얼이 DOM 경로에서는 `2020-06-15`, 스트리밍 경로에서는
     * `44000`이 된다 — 두 경로가 같은 파일에서 다른 값을 내는 것은 왕복 무결성 붕괴다.
     *
     * **병합 범위를 함께 돌려주는 이유(B-7):** 병합 셀의 피복 칸은 파일에서 빈 셀로 읽히는데,
     * 그대로 두면 '빈칸=삭제' 규약에 걸려 사용자가 화면에서 걸쳐 보던 값이 무통보 유실된다.
     * DOM 경로가 `numMergedRegions`로 하는 일을 스트리밍도 해야 대칭이 성립한다.
     * `mergeCells` 요소는 시트 XML에서 `sheetData` **뒤**에 오므로 파싱이 끝나야 알 수 있다 —
     * 그래서 콜백이 아니라 반환값이다(행 소비는 이미 끝나 있고, 병합 조회는 그 뒤에 일어난다).
     *
     * @return 병합 범위 목록(없으면 빈 목록)
     */
    fun readSheetRaw(
        sheetName: String,
        onRow: (rowIndex: Int, cells: Map<Int, ExcelCellValue.Primitives>) -> Unit
    ): List<MergedCellMap.Region> {
        val it = reader.sheetsData as XSSFReader.SheetIterator
        while (it.hasNext()) {
            val stream = it.next()
            if (it.sheetName == sheetName) {
                val handler = SheetHandler(onRow)
                stream.use { s -> newSafeSaxFactory().newSAXParser().parse(InputSource(s), handler) }
                return handler.mergedRegions
            } else {
                stream.close()
            }
        }
        return emptyList()
    }

    /** [readLeadingRows]가 창만큼 얻은 뒤 SAX 파싱을 중단시키는 제어 신호(오류가 아니다). */
    private class StopParsing : org.xml.sax.SAXException()

    /**
     * [sheetName]의 **0행만** 읽는다. 시트가 없거나 **0행이 없으면** null.
     *
     * **첫 물리 행이 아니라 0행이다(B-218 ①):** 위에 행을 끼워 넣어 헤더가 3행으로 밀린
     * 파일에서 첫 물리 행을 그대로 돌려주면 **DOM과 스트리밍의 행 번호가 어긋난다** —
     * 두 경로가 같은 파일에서 다른 행을 0행이라 부르게 된다.
     *
     * **헤더 행을 찾는 것은 이제 이 함수가 아니다**(B-231 ⓑ) — 헤더는 0행이 아닐 수 있고,
     * 그 판정은 [SheetResolver.locateHeader]가 [readLeadingRows] 위에서 한다. 이 함수는
     * *0행이 무엇인가*만 답한다.
     */
    fun readHeaderRow(sheetName: String): Map<Int, ExcelCellValue.Primitives>? =
        readLeadingRows(sheetName, 1)[0]

    /**
     * [sheetName]의 **앞 [limit]행**만 읽고 파싱을 중단한다.
     *
     * **왜 0행 하나가 아닌가 (B-231 ⓑ):** 헤더 행이 0행이 아닐 수 있게 되면서
     * [SheetResolver.locateHeader]가 앞쪽 몇 행을 훑는데, 그 조회가 시트 정체 판정(R-7 —
     * 워크북의 **모든 시트**)에 곱해진다. 여기서 앞 몇 행을 값싸게 주지 않으면 호출부가
     * `getRow(1)`에서 시트를 **통째로 적재**하게 되고, 그러면 스트리밍이 DOM보다 무거워진다
     * — 스트리밍을 도입한 이유 자체가 무너지는 자리다.
     *
     * 시트 XML의 행은 오름차순이므로(ECMA-376) [limit]에 닿는 순간 멈추면 된다.
     */
    fun readLeadingRows(sheetName: String, limit: Int): Map<Int, Map<Int, ExcelCellValue.Primitives>> {
        val head = HashMap<Int, Map<Int, ExcelCellValue.Primitives>>()
        val it = reader.sheetsData as XSSFReader.SheetIterator
        while (it.hasNext()) {
            val stream = it.next()
            if (it.sheetName == sheetName) {
                val handler = SheetHandler { rowIndex, cells ->
                    if (rowIndex < limit) head[rowIndex] = HashMap(cells)
                    // 오름차순이라 창을 벗어난 첫 행이 곧 끝이다.
                    if (rowIndex >= limit - 1) throw StopParsing()
                }
                try {
                    stream.use { s -> newSafeSaxFactory().newSAXParser().parse(InputSource(s), handler) }
                } catch (_: StopParsing) {
                    // 정상 종료 — 창만큼 얻었다
                } finally {
                    try { stream.close() } catch (_: Exception) { /* use{}가 이미 닫았을 수 있다 */ }
                }
                return head
            } else {
                stream.close()
            }
        }
        return emptyMap()
    }

    /**
     * [sheetName]의 마지막 행 0-기반 인덱스(POI `lastRowNum`과 같은 의미). 시트가 없거나
     * 행이 없으면 0.
     *
     * 셀 값을 전혀 만들지 않고 `<row r>`만 훑는 **값싼 색인 패스**다. 가져오기의 모든 데이터
     * 루프가 `헤더 다음 행..lastRowNum`이라 이 값은 **정확해야 한다** — `<dimension>` 요소는 외부 편집기가
     * 갱신하지 않을 수 있어 근거로 쓰지 않는다(틀리면 뒤쪽 행이 조용히 통째로 누락된다).
     */
    fun lastRowNumOf(sheetName: String): Int {
        val it = reader.sheetsData as XSSFReader.SheetIterator
        while (it.hasNext()) {
            val stream = it.next()
            if (it.sheetName == sheetName) {
                var last = 0
                var seq = -1
                val handler = object : DefaultHandler() {
                    override fun startElement(uri: String?, local: String?, qName: String, a: Attributes?) {
                        if (qName == "row") {
                            val r = a?.getValue("r")?.toIntOrNull()
                            seq = if (r != null) r - 1 else seq + 1
                            if (seq > last) last = seq
                        }
                    }
                }
                stream.use { s -> newSafeSaxFactory().newSAXParser().parse(InputSource(s), handler) }
                return last
            } else {
                stream.close()
            }
        }
        return 0
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
        private val onRow: (Int, Map<Int, ExcelCellValue.Primitives>) -> Unit
    ) : DefaultHandler() {

        /** 파싱 중 수집한 병합 범위 (`mergeCells`는 `sheetData` 뒤에 온다). */
        val mergedRegions = ArrayList<MergedCellMap.Region>()

        private var rowIndex = -1
        private var cells = HashMap<Int, ExcelCellValue.Primitives>()
        // 행 안에서 지금까지 본 최대 열 — `r` 없는 `<c>`의 열 배정 기준(아래).
        private var maxColInRow = -1

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
                    maxColInRow = -1
                }
                "mergeCell" -> parseMergeRef(a?.getValue("ref"))?.let { mergedRegions.add(it) }
                "c" -> {
                    // `r` 없는 `<c>`는 버리지 않는다(B-218 ③) — 제3자 생성기가 참조를 생략한
                    // 파일에서 DOM(XSSFCell)은 "지금까지의 최대 열 + 1"에 배정한다. 여기서 버리면
                    // 그 파일의 행 내용이 경로마다 갈린다. 같은 규칙으로 배정한다.
                    val parsed = colOf(a?.getValue("r"))
                    colIndex = if (parsed >= 0) parsed else maxColInRow + 1
                    if (colIndex > maxColInRow) maxColInRow = colIndex
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
                "c" -> if (colIndex >= 0) cells[colIndex] = cellPrimitives()
                "row" -> if (rowIndex >= 0) onRow(rowIndex, cells)
            }
        }

        /**
         * 현재 셀의 원시 데이터를 [ExcelCellValue.Primitives]로 만든다. **정규화하지 않는다** —
         * dateHint는 호출부의 것이다.
         *
         * **값이 없어도 null이 아니라 [BLANK_CELL]을 돌려준다.** `<c>` 요소가 파일에 있으면
         * DOM은 (빈 값이어도) 셀 객체를 만들고, 그래서 `lastCellNum`이 그 열까지 센다.
         * 여기서 null을 주면 스트리밍 쪽 행 폭이 DOM보다 좁아져 **두 경로의 행 모양이 갈린다**
         * (실제로 자기 재공격에서 DOM=4 / 스트리밍=3으로 어긋나는 것을 확인했다).
         * 값 자체는 어느 쪽이든 ""로 정규화되지만, 모양이 갈리면 그 위에 쌓는 판정이 갈린다.
         */
        private fun cellPrimitives(): ExcelCellValue.Primitives {
            return when (cellType) {
                "s" -> {
                    // 공유 문자열 — buf는 SST 인덱스
                    val idx = buf.toString().trim().toIntOrNull() ?: return BLANK_CELL
                    val s = try { sharedStrings.getItemAt(idx).string } catch (_: Exception) { return BLANK_CELL }
                    ExcelCellValue.Primitives(CellType.STRING, stringValue = s)
                }
                "inlineStr" -> ExcelCellValue.Primitives(CellType.STRING, stringValue = inlineBuf.toString())
                "str" -> // 수식의 문자열 결과 — 캐시된 문자열. getCellString FORMULA(문자열결과)와 동일 값.
                    ExcelCellValue.Primitives(CellType.STRING, stringValue = buf.toString())
                "b" -> ExcelCellValue.Primitives(CellType.BOOLEAN, booleanValue = buf.toString().trim() == "1")
                "e" -> ExcelCellValue.Primitives(CellType.ERROR)
                else -> { // 숫자(t 없음 또는 "n")
                    val num = buf.toString().trim().toDoubleOrNull() ?: return BLANK_CELL
                    numericPrimitives(num)
                }
            }
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
        /**
         * 값 없는 `<c>` 요소를 나타내는 공용 인스턴스. DOM의 BLANK 셀과 같은 자리이며
         * [ExcelCellValue.normalize]는 이것을 ""로 낸다. 불변이라 공유해도 안전하고,
         * 서식만 있고 값이 없는 셀이 수만 개인 시트에서 할당이 늘지 않는다.
         */
        val BLANK_CELL = ExcelCellValue.Primitives(CellType.BLANK)

        /** "A1"/"BC12" 등 셀 참조에서 0-기반 행 인덱스 추출. 숫자부가 없으면 -1. */
        fun rowOf(ref: String?): Int {
            if (ref.isNullOrEmpty()) return -1
            val digits = ref.dropWhile { !it.isDigit() }
            val n = digits.takeWhile { it.isDigit() }.toIntOrNull() ?: return -1
            return n - 1
        }

        /**
         * `mergeCell@ref`("A1:C3")를 [MergedCellMap.Region]으로. 형태가 아니면 null.
         * 한 칸짜리("A1")도 엑셀이 쓸 수 있으므로 콜론이 없으면 양끝을 같은 칸으로 본다.
         */
        fun parseMergeRef(ref: String?): MergedCellMap.Region? {
            if (ref.isNullOrBlank()) return null
            val parts = ref.split(':')
            val a = parts[0]
            val b = if (parts.size > 1) parts[1] else parts[0]
            val r1 = rowOf(a); val c1 = colOf(a)
            val r2 = rowOf(b); val c2 = colOf(b)
            if (r1 < 0 || c1 < 0 || r2 < 0 || c2 < 0) return null
            return MergedCellMap.Region(
                firstRow = minOf(r1, r2), lastRow = maxOf(r1, r2),
                firstColumn = minOf(c1, c2), lastColumn = maxOf(c1, c2)
            )
        }

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
