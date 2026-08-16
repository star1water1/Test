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

    /**
     * 병합 범위를 **구간 그대로** 들고 찾는다 — 행 단위로 펼치지 않는다 (B-219 ②).
     *
     * > **왜 펼치면 안 되는가:** 엑셀에서 열 머리를 눌러 병합하면 `A1:A1048576` 한 범위가
     * > 생기는데, 그것을 행마다 펼치면 **엔트리 백만 개 + 리스트 백만 개**가 된다.
     * > 파일은 몇 KB인데 힙이 수백 MB로 뛰어 가져오기가 통째로 죽는다 —
     * > 목표 규모(`docs/scalability_performance_2026-07.md`) 밖의 상한을
     * > **입력이 정하게 두는** 자리라 값이 아니라 **자료 구조**를 고쳤다.
     * > 이제 메모리는 병합 **개수**에만 비례한다(행 수와 무관).
     *
     * 찾는 법은 시작 행으로 정렬한 배열 + **누적 최대 끝 행**이다.
     * `row`를 덮는 범위는 `firstRow <= row`인 것들 중에 있으므로 이진 탐색으로 그 끝을 잡고,
     * 거기서부터 내려오며 `maxLastRow[j] < row`가 되는 순간 멈춘다 —
     * 그 아래에는 `row`에 닿는 범위가 원리적으로 없다.
     */
    /**
     * 유효한 범위를 `firstRow` 오름차순으로. **입력 순서는 [order]가 따로 들고 간다** —
     * 한 칸을 여러 범위가 덮는 비정상 파일에서 *어느 것을 좌상단으로 볼 것인가*가
     * 정렬로 바뀌면 안 되기 때문이다(종전 구현은 입력 순서로 답했다).
     */
    private val sorted: List<Region>
    private val order: IntArray

    /** `maxLastRow[i]` = `sorted[0..i]`의 `lastRow` 최대값 — 아래로 훑다 멈추는 근거. */
    private val maxLastRow: IntArray

    init {
        val kept = ArrayList<Region>(regions.size)
        val keptOrder = ArrayList<Int>(regions.size)
        regions.forEachIndexed { i, r ->
            if (r.lastRow >= r.firstRow && r.lastColumn >= r.firstColumn) {
                kept.add(r); keptOrder.add(i)
            }
        }
        val idx = kept.indices.sortedBy { kept[it].firstRow }  // 안정 정렬 — 같은 시작 행은 입력 순서
        sorted = idx.map { kept[it] }
        order = IntArray(idx.size) { keptOrder[idx[it]] }
        maxLastRow = IntArray(sorted.size)
        var best = Int.MIN_VALUE
        for (i in sorted.indices) {
            if (sorted[i].lastRow > best) best = sorted[i].lastRow
            maxLastRow[i] = best
        }
    }

    /**
     * 한 행의 후보 목록 캐시 — 호출측은 **행마다 열을 훑으므로**(`getCellString` 경로)
     * 같은 행이 열 수만큼 연달아 들어온다. 행이 바뀔 때만 다시 찾는다.
     *
     * 불변 객체를 참조 하나로 갈아 끼우므로 경합이 나도 찢어지지 않는다(필드가 전부 `val`).
     * 어긋난 행이 읽히면 `row` 비교에서 걸러져 다시 찾을 뿐이라 답이 갈리지 않는다.
     */
    private class RowCandidates(val row: Int, val regions: List<Region>)

    private var rowCache: RowCandidates? = null

    val isEmpty: Boolean get() = sorted.isEmpty()

    private fun candidatesFor(row: Int): List<Region> {
        val cached = rowCache
        if (cached != null && cached.row == row) return cached.regions
        val found = scanRow(row)
        rowCache = RowCandidates(row, found)
        return found
    }

    private fun scanRow(row: Int): List<Region> {
        if (sorted.isEmpty()) return emptyList()
        // firstRow <= row인 마지막 자리 (없으면 -1)
        var lo = 0
        var hi = sorted.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].firstRow <= row) { last = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (last < 0) return emptyList()
        val hits = ArrayList<Int>(2)
        for (i in last downTo 0) {
            if (maxLastRow[i] < row) break
            if (sorted[i].lastRow >= row) hits.add(i)
        }
        if (hits.isEmpty()) return emptyList()
        // 입력 순서로 되돌린다 — 겹치는 범위에서 종전과 같은 답을 내기 위해서다.
        hits.sortBy { order[it] }
        return hits.map { sorted[it] }
    }

    /** (row, col)을 덮는 병합 범위의 좌상단. 어떤 범위에도 안 속하면 null. */
    fun topLeftOf(row: Int, column: Int): CellRef? {
        for (r in candidatesFor(row)) {
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
