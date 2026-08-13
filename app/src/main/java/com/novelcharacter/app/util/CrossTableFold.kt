package com.novelcharacter.app.util

/**
 * 교차 분석 표를 **행·열 각각의 상한으로 접는다 — 합을 잃지 않고**(R-14 · R-19).
 *
 * ### 왜 이 자리만 따로 있는가 — 비용이 곱셈이다
 *
 * 통계 탭의 다른 명단들은 상한이 없어도 비용이 항목 수에 **선형**이다. 교차표만 다르다:
 * 셀 수가 `(필드1의 값 종수 × 필드2의 값 종수)`이고, 두 축 모두 **캐릭터가 늘면 함께 는다**
 * (자유 입력 필드 — 거주지·특기 같은 것 — 은 값 종수가 사실상 캐릭터 수를 따라간다).
 * 200종 × 200종이면 40,000칸이고, 그 하나하나가 `TextView`다. 스택 바 차트도 같은 곱이다
 * (`데이터셋 = 열 종수`, `막대 = 행 종수`). **규모가 아니라 차수의 문제**라 상한이 반드시 있어야 한다.
 *
 * ### 자르지 않고 접는다 — 합 보존이 이 파일의 계약이다
 *
 * 상한을 *버리기*로 구현하면 표의 합이 전체와 달라지고, 그러면 **그림과 숫자가 다른 말을 한다**
 * (R-19가 차트에 '기타' 조각을 요구하는 이유가 그것이다). 그래서 넘치는 행·열은 사라지지 않고
 * '기타'로 **합쳐진다.** [Folded.grandTotal]은 언제나 원본의 총합과 같고, 그 불변식을 시험이 든다.
 *
 * 접힌 것이 행·열 양쪽이면 **네 칸이 생긴다**: (남긴 행 × 남긴 열) · (남긴 행 × 기타 열) ·
 * (기타 행 × 남긴 열) · (기타 행 × 기타 열). 마지막 칸을 빠뜨리는 것이 이 계산의 유일한
 * 조용한 유실 자리라, 시험이 그 칸만 따로 잰다.
 *
 * ### 무엇을 남기는가
 *
 * 행도 열도 **합계 내림차순, 동률이면 라벨 오름차순**으로 남긴다([ValueDistributions]와 같은 규칙).
 * 종전 표는 행만 합계순이고 열은 사전순이었는데, 상한을 두는 순간 그 비대칭이 문제가 된다 —
 * 사전순으로 앞 12개를 남기면 **가장 큰 열이 통째로 '기타'에 들어갈 수 있다.**
 */
object CrossTableFold {

    /**
     * 접힌 표.
     *
     * @param rows 표시할 행 라벨 — 접혔으면 마지막 원소가 '기타' 행이다
     * @param cols 표시할 열 라벨 — 접혔으면 마지막 원소가 '기타' 열이다
     * @param cells `행 → (열 → 건수)`. [rows]·[cols]에 있는 라벨만 나온다.
     * @param hiddenRowKinds 접힌 행의 **종수**(건수가 아니다 — 건수는 '기타' 행이 그대로 든다)
     * @param hiddenColKinds 접힌 열의 종수
     * @param grandTotal 접기 전후가 같아야 하는 총합
     */
    data class Folded(
        val rows: List<String>,
        val cols: List<String>,
        val cells: Map<String, Map<String, Int>>,
        val hiddenRowKinds: Int,
        val hiddenColKinds: Int,
        val grandTotal: Int
    ) {
        val hasHiddenRows: Boolean get() = hiddenRowKinds > 0
        val hasHiddenCols: Boolean get() = hiddenColKinds > 0
        val hasHidden: Boolean get() = hasHiddenRows || hasHiddenCols

        fun count(row: String, col: String): Int = cells[row]?.get(col) ?: 0
    }

    /**
     * [table]을 행 [rowLimit]개 · 열 [colLimit]개로 접는다.
     *
     * 상한이 0 이하이면 그 축은 접지 않는다(상한을 끄는 설정이 조용히 빈 표가 되지 않게 —
     * [ValueDistributions.view]와 같은 규칙).
     *
     * '기타' 라벨은 **접힌 종수를 받아 화면 문구로 짓는다**([rowOtherLabel]·[colOtherLabel]) —
     * 종수는 접고 나야 알 수 있어서 문자열이 아니라 함수로 받는다. 그렇게 만든 라벨이
     * 실제 값과 겹치면 맵 키가 충돌해 **두 묶음이 조용히 하나로 합쳐지므로**, 겹칠 때는
     * 라벨을 비켜 짓는다(R-20이 구간 라벨에 요구한 것과 같은 이유다).
     */
    fun fold(
        table: Map<String, Map<String, Int>>,
        rowLimit: Int,
        colLimit: Int,
        rowOtherLabel: (hiddenKinds: Int) -> String,
        colOtherLabel: (hiddenKinds: Int) -> String
    ): Folded {
        val grandTotal = table.values.sumOf { row -> row.values.sum() }
        if (table.isEmpty()) {
            return Folded(emptyList(), emptyList(), emptyMap(), 0, 0, 0)
        }

        val rowTotals = table.mapValues { (_, row) -> row.values.sum() }
        val colTotals = LinkedHashMap<String, Int>()
        for (row in table.values) {
            for ((col, n) in row) colTotals[col] = (colTotals[col] ?: 0) + n
        }

        val keptRows = rank(rowTotals, rowLimit)
        val keptCols = rank(colTotals, colLimit)
        val hiddenRowKinds = rowTotals.size - keptRows.size
        val hiddenColKinds = colTotals.size - keptCols.size

        val rowOther = if (hiddenRowKinds > 0) {
            uniqueLabel(rowOtherLabel(hiddenRowKinds), keptRows.toSet())
        } else null
        val colOther = if (hiddenColKinds > 0) {
            uniqueLabel(colOtherLabel(hiddenColKinds), keptCols.toSet())
        } else null

        val keptRowSet = keptRows.toHashSet()
        val keptColSet = keptCols.toHashSet()
        val cells = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for ((rowLabel, row) in table) {
            // 남긴 행이 아니면 전부 '기타' 행으로 합친다 — 버리지 않는다.
            val targetRow = if (rowLabel in keptRowSet) rowLabel else rowOther ?: continue
            val bucket = cells.getOrPut(targetRow) { LinkedHashMap() }
            for ((colLabel, n) in row) {
                val targetCol = if (colLabel in keptColSet) colLabel else colOther ?: continue
                bucket[targetCol] = (bucket[targetCol] ?: 0) + n
            }
        }

        return Folded(
            rows = if (rowOther != null) keptRows + rowOther else keptRows,
            cols = if (colOther != null) keptCols + colOther else keptCols,
            cells = cells,
            hiddenRowKinds = hiddenRowKinds,
            hiddenColKinds = hiddenColKinds,
            grandTotal = grandTotal
        )
    }

    /** 합계 내림차순 · 동률이면 라벨 오름차순으로 상위 [limit]개. */
    private fun rank(totals: Map<String, Int>, limit: Int): List<String> {
        val ordered = totals.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        return if (limit <= 0 || ordered.size <= limit) ordered else ordered.take(limit)
    }

    /**
     * '기타' 라벨이 실제 값과 겹치면 비켜 짓는다.
     *
     * 겹친 채로 두면 `기타 3종`이라는 이름의 진짜 값이 접힌 묶음과 **같은 맵 키가 되어**
     * 한쪽이 다른 쪽에 흡수된다 — 오류도 고지도 없이 건수만 틀린다.
     */
    private fun uniqueLabel(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var suffix = 2
        while ("$base ($suffix)" in taken) suffix++
        return "$base ($suffix)"
    }
}
