package com.novelcharacter.app.excel

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook

/**
 * 가져오기가 실제로 필요로 하는 **워크북 접근면**만 추린 추상화 (B-8 / 색출 로드맵 6).
 *
 * ## 왜 있는가
 *
 * [ExcelImportService]는 5,700줄이 전부 POI `ss.usermodel`(Workbook/Sheet/Row/Cell)에 맞춰져
 * 있었다. 스트리밍 리더([StreamingXlsxReader])를 배선하려면 그 5,700줄이 두 경로를 모두
 * 태울 수 있어야 하는데, 통째로 SAX 소비로 재작성하면 **가져오기 무결성의 회귀 반경이
 * 저장소 전체에서 가장 넓은 자리**에서 검증 불가능한 대형 diff가 난다
 * (`docs/excel_streaming_import_2026-07.md` — "전면 SAX 재작성은 채택하지 않는다").
 *
 * 그래서 접근면을 먼저 **실측**했다. 5,700줄이 쓰는 POI 멤버는 아래가 전부다:
 *
 * | 타입 | 쓰는 멤버 |
 * |------|-----------|
 * | Workbook | `numberOfSheets` · `getSheetName` · `getSheetAt` · `getSheet` |
 * | Sheet | `lastRowNum` · `getRow` · `sheetName` · `numMergedRegions` · `getMergedRegion` |
 * | Row | `rowNum` · `lastCellNum` · `sheet` · `getCell` |
 * | Cell | `cellType` · (값 정규화는 전부 [ExcelCellValue]에 위임) |
 *
 * **멤버 이름을 POI와 같게 둔 것은 의도다.** 그래야 호출부 5,700줄의 본문이 한 글자도
 * 바뀌지 않고(= 두 경로가 같은 per-row 로직을 탄다는 것이 diff로 증명되고), 바뀌는 것은
 * 타입 선언뿐이 된다. 로컬에서 Android 계층을 컴파일할 수 없는 이 저장소에서는
 * **diff를 작게 두는 것 자체가 안전 장치**다.
 *
 * ## 두 구현
 *
 * - [DomImportWorkbook] — 기존 POI DOM. **동작 무변경**(기본·폴백 경로).
 * - [StreamingImportWorkbook] — SAX 스트리밍. 시트 하나씩만 들고 있어 메모리가
 *   **파일 크기가 아니라 가장 큰 시트 하나**에 비례한다.
 *
 * ## 값 왜곡을 구조적으로 막는 지점
 *
 * 셀은 **문자열이 아니라 [ExcelCellValue.Primitives]로** 건넨다. 정규화를 리더가 미리
 * 해버리면 `dateHint`(호출부가 필드 의미로 정하는 값 — 생일 등)를 반영할 수 없어
 * 같은 셀이 경로에 따라 다른 값이 된다. 실제로 이 배선 착수 전 [StreamingXlsxReader]는
 * `dateHint = false`를 못 박고 있었고, 그대로 배선했다면 서식 없는 날짜 시리얼이
 * DOM에서는 `2020-06-15`, 스트리밍에서는 `44000`이 될 참이었다.
 * **정규화는 두 경로 모두 호출부가 dateHint를 아는 그 자리에서 일어난다.**
 */
interface ImportWorkbook {
    val numberOfSheets: Int
    fun getSheetName(index: Int): String
    fun getSheetAt(index: Int): ImportSheet
    fun getSheet(name: String): ImportSheet?
}

/** 시트 하나. [getRow]는 0-기반이며 빈 행은 null(POI와 동일). */
interface ImportSheet {
    val sheetName: String

    /** 마지막 행의 0-기반 인덱스. 행이 없으면 0 (POI `lastRowNum`과 같은 의미). */
    val lastRowNum: Int

    val numMergedRegions: Int
    fun getMergedRegion(index: Int): MergedCellMap.Region
    fun getRow(index: Int): ImportRow?
}

/** 행 하나. */
interface ImportRow {
    val rowNum: Int

    /** 마지막 셀 인덱스 + 1. 셀이 없으면 -1 (POI `lastCellNum`과 같은 의미·타입). */
    val lastCellNum: Short

    val sheet: ImportSheet?
    fun getCell(index: Int): ImportCell?
}

/** 셀 하나. 값 해석은 [primitives]를 통해서만 — 정규화 규칙은 [ExcelCellValue]가 단일 소스다. */
interface ImportCell {
    /**
     * **값의 타입**(수식 셀은 캐시 결과의 타입 — FORMULA는 나오지 않는다).
     *
     * 스트리밍 SAX는 `<f>`를 읽지 않아 수식 여부를 원리적으로 모르므로, DOM이 POI의
     * FORMULA를 그대로 내보내면 같은 셀의 판정이 경로마다 갈린다 — 실제로 코드 열의
     * 숫자 형식 경고(F4)가 숫자 캐시 수식 셀에서 스트리밍에만 떴다(B-218). 소비처가
     * 물을 수 있는 것은 "값이 무슨 타입인가"뿐이고, 두 구현이 같은 답을 내는 것이 계약이다.
     */
    val cellType: CellType

    /** 정규화 이전의 원시 값. 호출부가 자기 `dateHint`로 [ExcelCellValue.normalize]를 부른다. */
    fun primitives(): ExcelCellValue.Primitives
}

// ─────────────────────────────── DOM 경로 (기존 동작) ───────────────────────────────

/**
 * POI DOM 워크북 어댑터 — **기존 동작을 한 치도 바꾸지 않는다.**
 * 각 멤버가 같은 이름의 POI 멤버로 그대로 내려간다.
 */
class DomImportWorkbook(private val wb: Workbook) : ImportWorkbook {
    override val numberOfSheets: Int get() = wb.numberOfSheets
    override fun getSheetName(index: Int): String = wb.getSheetName(index)
    override fun getSheetAt(index: Int): ImportSheet = DomImportSheet(wb.getSheetAt(index))
    override fun getSheet(name: String): ImportSheet? = wb.getSheet(name)?.let { DomImportSheet(it) }
}

private class DomImportSheet(private val sheet: Sheet) : ImportSheet {
    override val sheetName: String get() = sheet.sheetName
    override val lastRowNum: Int get() = sheet.lastRowNum
    override val numMergedRegions: Int get() = sheet.numMergedRegions

    override fun getMergedRegion(index: Int): MergedCellMap.Region {
        val r = sheet.getMergedRegion(index)
        return MergedCellMap.Region(r.firstRow, r.lastRow, r.firstColumn, r.lastColumn)
    }

    override fun getRow(index: Int): ImportRow? = sheet.getRow(index)?.let { DomImportRow(it, this) }

    // 같은 시트를 여러 번 감싸도 mergedCellMaps(HashMap<ImportSheet, …>)의 키가 갈라지지 않도록
    // POI 시트 인스턴스를 정체성으로 삼는다. DOM에서는 시트 인스턴스가 워크북 수명 동안 유일하다.
    override fun equals(other: Any?): Boolean = other is DomImportSheet && other.sheet === sheet
    override fun hashCode(): Int = System.identityHashCode(sheet)
}

private class DomImportRow(private val row: Row, private val owner: ImportSheet) : ImportRow {
    override val rowNum: Int get() = row.rowNum
    override val lastCellNum: Short get() = row.lastCellNum
    override val sheet: ImportSheet get() = owner
    override fun getCell(index: Int): ImportCell? = row.getCell(index)?.let { DomImportCell(it) }
}

private class DomImportCell(private val cell: org.apache.poi.ss.usermodel.Cell) : ImportCell {
    // 값의 타입 계약([ImportCell.cellType]) — 수식 셀은 캐시 결과의 값 타입으로 편다.
    // 판정은 [ExcelCellValue.cachedValueType] 한 벌이다(fromCell과 갈리면 타입과 값이 어긋난다).
    override val cellType: CellType
        get() {
            val t = cell.cellType
            return if (t == CellType.FORMULA) ExcelCellValue.cachedValueType(cell) else t
        }

    override fun primitives(): ExcelCellValue.Primitives = ExcelCellValue.fromCell(cell)
}

// ─────────────────────────────── 스트리밍 경로 ───────────────────────────────

/**
 * SAX 스트리밍 워크북 어댑터 — 파일 전체를 DOM으로 적재하지 않는다 (B-8).
 *
 * ## 메모리 성질
 *
 * `XSSFWorkbook`은 **모든 시트의** XMLBeans 객체 그래프를 동시에 들고 있어 메모리가
 * 파일 크기에 비례한다(그래서 128MB 상한이 있었고, 앱이 만든 백업을 앱이 못 읽었다).
 * 이 구현은 **시트 하나 분량의 정규화 전 셀만** 들고 있다가 다음 시트를 열 때 버린다.
 * 즉 상한이 "파일 크기"에서 "가장 큰 시트 하나"로 내려간다.
 *
 * ## 왜 시트를 적재하는가 (완전 전방향이 아닌 이유)
 *
 * 호출부는 `for (i in 1..sheet.lastRowNum) { sheet.getRow(i) }`로 **임의 접근**을 하고,
 * 병합 피복 칸을 만나면 `sheet.getRow(좌상단행)`으로 되돌아간다. 이 5,700줄을 전방향
 * 소비로 재작성하는 것이 바로 설계 문서가 반려한 "전면 SAX 재작성"이다. 시트 단위
 * 적재는 그 재작성 없이 파일 크기 비례를 없앤다.
 *
 * ## 값 왜곡 방지
 *
 * 셀은 정규화 전 [ExcelCellValue.Primitives]로 보관한다 — 정규화는 호출부가 `dateHint`를
 * 아는 자리에서 [DomImportWorkbook]과 **같은 함수**로 일어난다.
 *
 * 이 클래스는 [close]로 파일 핸들을 닫아야 한다.
 */
class StreamingImportWorkbook(file: java.io.File) : ImportWorkbook, java.io.Closeable {

    private val reader = StreamingXlsxReader(file)
    private val names: List<String> = reader.sheetNames()

    // 적재된 시트 하나 (이름 · 행 · 병합 범위). 다른 시트를 적재하면 통째로 교체된다.
    private var loadedName: String? = null
    private var loadedRows: Map<Int, Map<Int, ExcelCellValue.Primitives>> = emptyMap()
    private var loadedRegions: List<MergedCellMap.Region> = emptyList()

    private val lastRowCache = HashMap<String, Int>()
    private val headerCache = HashMap<String, Map<Int, ExcelCellValue.Primitives>?>()

    override val numberOfSheets: Int get() = names.size
    override fun getSheetName(index: Int): String = names[index]
    override fun getSheetAt(index: Int): ImportSheet = StreamingImportSheet(this, names[index])
    override fun getSheet(name: String): ImportSheet? =
        if (names.contains(name)) StreamingImportSheet(this, name) else null

    override fun close() = reader.close()

    internal fun lastRowNumOf(sheet: String): Int = lastRowCache.getOrPut(sheet) {
        // 이미 적재돼 있으면 그 값이 곧 정답이다 — 파일을 다시 훑지 않는다.
        if (loadedName == sheet) loadedRows.keys.maxOrNull() ?: 0 else reader.lastRowNumOf(sheet)
    }

    internal fun headerRowOf(sheet: String): Map<Int, ExcelCellValue.Primitives>? {
        if (loadedName == sheet) return loadedRows[0]
        // getOrPut은 null을 저장하지 못한다 — 0행이 없는 시트(B-218 ①)가 조회마다 재파싱되지
        // 않도록 containsKey로 "계산했음"을 따로 판정한다.
        if (!headerCache.containsKey(sheet)) headerCache[sheet] = reader.readHeaderRow(sheet)
        return headerCache[sheet]
    }

    /** [sheet]를 적재한다(이미 적재돼 있으면 무동작). 직전 시트는 버려진다. */
    private fun ensureLoaded(sheet: String) {
        if (loadedName == sheet) return
        // 먼저 비워 직전 시트가 새 적재와 동시에 살아 있지 않게 한다(최대 사용량 = 큰 쪽 하나).
        loadedName = null
        loadedRows = emptyMap()
        loadedRegions = emptyList()

        val rows = HashMap<Int, Map<Int, ExcelCellValue.Primitives>>()
        val regions = reader.readSheetRaw(sheet) { rowIndex, cells ->
            // 셀이 하나도 없는 행도 담는다 — 파일에 `<row>`가 있으면 DOM은 (빈 행이어도)
            // 행 객체를 주므로 `getRow(i) != null`이 성립한다. 여기서 걸러 내면 그 행부터
            // 두 경로의 행 존재 여부가 갈리고, 호출부의 `?: continue`가 경로마다 다르게 돈다.
            rows[rowIndex] = if (cells.isEmpty()) emptyMap() else HashMap(cells)
        }
        loadedRows = rows
        loadedRegions = regions
        loadedName = sheet
        lastRowCache[sheet] = rows.keys.maxOrNull() ?: 0
    }

    internal fun rowOf(sheet: String, index: Int, owner: ImportSheet): ImportRow? {
        // 0행만 필요한 경로(시트 정체 판정 R-7)는 시트를 통째로 적재하지 않는다.
        if (index == 0 && loadedName != sheet) {
            val header = headerRowOf(sheet) ?: return null
            return StreamingImportRow(0, header, owner)
        }
        ensureLoaded(sheet)
        val cells = loadedRows[index] ?: return null
        return StreamingImportRow(index, cells, owner)
    }

    internal fun regionsOf(sheet: String): List<MergedCellMap.Region> {
        ensureLoaded(sheet)
        return loadedRegions
    }
}

/**
 * 스트리밍 시트 **핸들** — 행을 들고 있지 않다(무게는 워크북이 진다).
 * 그래서 호출부가 `HashMap<ImportSheet, …>`에 오래 담아 두어도 시트 내용이 붙들리지 않는다.
 */
private class StreamingImportSheet(
    private val wb: StreamingImportWorkbook,
    override val sheetName: String
) : ImportSheet {
    override val lastRowNum: Int get() = wb.lastRowNumOf(sheetName)
    override val numMergedRegions: Int get() = wb.regionsOf(sheetName).size
    override fun getMergedRegion(index: Int): MergedCellMap.Region = wb.regionsOf(sheetName)[index]
    override fun getRow(index: Int): ImportRow? = wb.rowOf(sheetName, index, this)

    // 같은 시트를 여러 번 열어도 호출부의 시트 키가 갈라지지 않도록 이름이 정체성이다
    // (워크북 안에서 시트 이름은 유일하다).
    override fun equals(other: Any?): Boolean = other is StreamingImportSheet && other.sheetName == sheetName
    override fun hashCode(): Int = sheetName.hashCode()
}

private class StreamingImportRow(
    override val rowNum: Int,
    private val cells: Map<Int, ExcelCellValue.Primitives>,
    override val sheet: ImportSheet
) : ImportRow {
    // POI와 같은 의미: 마지막 셀 인덱스 + 1, 셀이 없으면 -1.
    override val lastCellNum: Short
        get() = (cells.keys.maxOrNull()?.plus(1) ?: -1).toShort()

    override fun getCell(index: Int): ImportCell? = cells[index]?.let { StreamingImportCell(it) }
}

private class StreamingImportCell(private val p: ExcelCellValue.Primitives) : ImportCell {
    override val cellType: CellType get() = p.type
    override fun primitives(): ExcelCellValue.Primitives = p
}
