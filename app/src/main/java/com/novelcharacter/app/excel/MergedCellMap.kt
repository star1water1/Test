package com.novelcharacter.app.excel

/**
 * 시트 하나의 병합 셀 범위 조회 (B-7).
 *
 * 외부 편집기에서 병합한 셀은 좌상단에만 값이 있고 나머지 칸은 빈 셀(또는 셀 없음)로 읽힌다.
 * 감지 없이 읽으면 '빈칸=삭제' 규약(덮어쓰기)에 걸려 사용자가 화면에서 값이 걸쳐 보이던
 * 데이터가 무통보로 유실된다. 이 클래스는 (행, 열)이 병합 범위에 속하는지와 그 범위의
 * 좌상단 좌표를 답한다 — 값 해석·집계·고지는 호출측([ExcelImportService]) 책임이다.
 *
 * 순수 로직 — POI 타입에 의존하지 않아 순수 JVM 하네스가 실행 검증하고,
 * 향후 SAX 스트리밍 경로(mergeCells 요소)도 같은 클래스를 쓸 수 있다(로직 비분기).
 */
class MergedCellMap(regions: List<Region>) {

    /** 병합 범위 (POI CellRangeAddress와 동형, 전 좌표 포함 범위) */
    data class Region(
        val firstRow: Int,
        val lastRow: Int,
        val firstColumn: Int,
        val lastColumn: Int
    )

    data class CellRef(val row: Int, val column: Int)

    // 시트당 병합 범위는 보통 수 개~수십 개 — 선형 탐색이면 충분하고,
    // 행 기준 필터를 한 번 거쳐 큰 시트에서도 호출당 비용을 낮춘다.
    private val byRow: Map<Int, List<Region>> = buildMap<Int, MutableList<Region>> {
        for (r in regions) {
            if (r.lastRow < r.firstRow || r.lastColumn < r.firstColumn) continue
            for (row in r.firstRow..r.lastRow) {
                getOrPut(row) { mutableListOf() }.add(r)
            }
        }
    }

    val isEmpty: Boolean get() = byRow.isEmpty()

    /** (row, col)을 덮는 병합 범위의 좌상단. 어떤 범위에도 안 속하면 null. */
    fun topLeftOf(row: Int, column: Int): CellRef? {
        val candidates = byRow[row] ?: return null
        for (r in candidates) {
            if (column in r.firstColumn..r.lastColumn) {
                return CellRef(r.firstRow, r.firstColumn)
            }
        }
        return null
    }

    /** (row, col)이 병합 범위의 좌상단이 아닌 피복 칸인가 — 좌상단 값으로 해석할 대상인가. */
    fun isCoveredCell(row: Int, column: Int): Boolean {
        val tl = topLeftOf(row, column) ?: return false
        return tl.row != row || tl.column != column
    }
}
