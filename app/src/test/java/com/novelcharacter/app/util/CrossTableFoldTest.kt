package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrossTableFold] — 교차 분석 표의 행·열 상한 (B-212).
 *
 * 이 하네스가 지키는 것은 넷이고, **앞의 둘은 서로 반대 방향의 사고를 막는다.**
 *
 * 1. **총합이 접기 전후로 같다** — 상한을 *버리기*로 구현하면 표의 합이 전체와 달라지고,
 *    그러면 표·차트·요약이 서로 다른 말을 한다(R-19). 건수가 조용히 사라지는 자리다.
 * 2. **남기는 기준이 합계 내림차순이다**(*반대편이다* — 1만 있으면 *"앞에서 12개를 남기고
 *    나머지를 기타로"* 하는 구현도 합은 맞는다. 그런데 종전 열 순서는 **사전순**이라,
 *    그대로 앞을 남기면 **가장 큰 열이 통째로 '기타'에 들어간다** — 합은 맞고 표는 쓸모없다).
 * 3. **접힌 행 × 접힌 열의 칸을 빠뜨리지 않는다** — 양쪽이 함께 접히면 칸이 넷으로 갈리는데,
 *    그 마지막 칸이 이 계산의 유일한 조용한 유실 자리다(합 시험이 그것까지 잰다).
 * 4. **'기타' 라벨이 실제 값과 겹치면 비켜 짓는다** — 겹치면 맵 키가 충돌해 두 묶음이
 *    하나로 합쳐진다. 오류도 고지도 없다(R-20이 구간 라벨에 요구한 것과 같은 부류).
 */
class CrossTableFoldTest {

    private fun others(kinds: Int) = "기타 ${kinds}종"

    private fun fold(
        table: Map<String, Map<String, Int>>,
        rowLimit: Int,
        colLimit: Int
    ) = CrossTableFold.fold(table, rowLimit, colLimit, ::others, ::others)

    private fun CrossTableFold.Folded.sum(): Int = cells.values.sumOf { it.values.sum() }

    // ── 1. 상한 아래에서는 아무것도 달라지지 않는다 ─────────────────────────

    @Test
    fun `상한 이하이면 접지 않는다`() {
        val table = mapOf(
            "남" to mapOf("서울" to 3, "부산" to 1),
            "여" to mapOf("서울" to 2)
        )

        val folded = fold(table, rowLimit = 12, colLimit = 12)

        assertFalse(folded.hasHidden)
        assertEquals(0, folded.hiddenRowKinds)
        assertEquals(0, folded.hiddenColKinds)
        assertEquals(6, folded.grandTotal)
        assertEquals(6, folded.sum())
        assertEquals(3, folded.count("남", "서울"))
        assertEquals(0, folded.count("여", "부산"))
    }

    @Test
    fun `상한 이하라도 행과 열은 합계순으로 선다`() {
        val table = mapOf(
            "a" to mapOf("x" to 1, "y" to 9),
            "b" to mapOf("x" to 5, "y" to 1)
        )

        val folded = fold(table, rowLimit = 0, colLimit = 0)

        // 행: a=10, b=6 → a 먼저. 열: y=10, x=6 → y 먼저.
        assertEquals(listOf("a", "b"), folded.rows)
        assertEquals(listOf("y", "x"), folded.cols)
    }

    @Test
    fun `빈 표는 빈 채로 돌려준다`() {
        val folded = fold(emptyMap(), rowLimit = 12, colLimit = 12)

        assertTrue(folded.rows.isEmpty())
        assertTrue(folded.cols.isEmpty())
        assertEquals(0, folded.grandTotal)
        assertFalse(folded.hasHidden)
    }

    // ── 2. 합 보존 — 이 파일의 계약 ─────────────────────────────────────────

    @Test
    fun `행만 접혀도 총합은 그대로다`() {
        val table = (1..30).associate { "행$it" to mapOf("x" to it) }

        val folded = fold(table, rowLimit = 5, colLimit = 12)

        val expected = (1..30).sum()
        assertEquals(expected, folded.grandTotal)
        assertEquals(expected, folded.sum())
        assertEquals(25, folded.hiddenRowKinds)
        assertEquals(6, folded.rows.size) // 남긴 5 + '기타' 1
        assertEquals("기타 25종", folded.rows.last())
        // 남긴 것은 큰 쪽이다 — 30·29·28·27·26.
        assertEquals(listOf("행30", "행29", "행28", "행27", "행26"), folded.rows.dropLast(1))
        // '기타' 행은 1..25의 합을 그대로 든다.
        assertEquals((1..25).sum(), folded.count("기타 25종", "x"))
    }

    @Test
    fun `열만 접혀도 총합은 그대로다`() {
        val table = mapOf("행" to (1..20).associate { "열$it" to it })

        val folded = fold(table, rowLimit = 12, colLimit = 3)

        assertEquals((1..20).sum(), folded.sum())
        assertEquals(17, folded.hiddenColKinds)
        assertEquals(listOf("열20", "열19", "열18", "기타 17종"), folded.cols)
        assertEquals((1..17).sum(), folded.count("행", "기타 17종"))
    }

    @Test
    fun `행과 열이 함께 접히면 네 칸으로 갈리고 넷의 합이 총합이다`() {
        // A=11 B=6 / X=11 Y=6 — 행도 열도 하나만 남는다.
        val table = mapOf(
            "A" to mapOf("X" to 10, "Y" to 1),
            "B" to mapOf("X" to 1, "Y" to 5)
        )

        val folded = fold(table, rowLimit = 1, colLimit = 1)

        val rowOther = "기타 1종"
        val colOther = "기타 1종"
        assertEquals(listOf("A", rowOther), folded.rows)
        assertEquals(listOf("X", colOther), folded.cols)

        assertEquals(10, folded.count("A", "X"))                 // 남긴 행 × 남긴 열
        assertEquals(1, folded.count("A", colOther))             // 남긴 행 × 기타 열
        assertEquals(1, folded.count(rowOther, "X"))             // 기타 행 × 남긴 열
        // **네 번째 칸** — 여기가 빠져도 표는 그럴듯해 보이고 합만 5 모자란다.
        assertEquals(5, folded.count(rowOther, colOther))

        assertEquals(17, folded.grandTotal)
        assertEquals(17, folded.sum())
    }

    @Test
    fun `값이 없는 칸은 0이고 표에 자리를 만들지 않는다`() {
        val table = mapOf(
            "A" to mapOf("X" to 3),
            "B" to mapOf("Y" to 4)
        )

        val folded = fold(table, rowLimit = 12, colLimit = 12)

        assertEquals(0, folded.count("A", "Y"))
        assertEquals(7, folded.sum())
    }

    // ── 3. 무엇을 남기는가 — 사전순 구현은 여기서 걸린다 ────────────────────

    @Test
    fun `가장 큰 열을 남긴다 — 사전순 앞자리가 아니다`() {
        // 사전순이면 'a'가 남고 실제로 큰 'z'가 '기타'로 들어간다.
        val table = mapOf(
            "행" to mapOf("a" to 1, "m" to 2, "z" to 99)
        )

        val folded = fold(table, rowLimit = 12, colLimit = 1)

        assertEquals("z", folded.cols.first())
        assertEquals(99, folded.count("행", "z"))
        assertEquals(3, folded.count("행", "기타 2종"))
    }

    @Test
    fun `동률이면 라벨 오름차순으로 갈라 같은 표를 두 번 열어도 같다`() {
        val table = mapOf(
            "b" to mapOf("z" to 3),
            "a" to mapOf("z" to 3)
        )

        val folded = fold(table, rowLimit = 1, colLimit = 12)

        assertEquals("a", folded.rows.first())
        assertEquals(3, folded.count("기타 1종", "z"))
    }

    // ── 4. 라벨 충돌 ────────────────────────────────────────────────────────

    @Test
    fun `기타 라벨이 실제 값과 겹치면 비켜 짓는다`() {
        // '기타 1종'이라는 이름의 진짜 값이 남고, 접힌 묶음이 같은 이름을 받으려 한다.
        val table = mapOf(
            "기타 1종" to mapOf("x" to 10),
            "B" to mapOf("x" to 1)
        )

        val folded = fold(table, rowLimit = 1, colLimit = 12)

        assertEquals(listOf("기타 1종", "기타 1종 (2)"), folded.rows)
        assertEquals(10, folded.count("기타 1종", "x"))
        assertEquals(1, folded.count("기타 1종 (2)", "x"))
        // 겹친 채로 두면 이 합이 11이 아니라 10이 된다 — 한쪽이 다른 쪽을 덮어써서다.
        assertEquals(11, folded.sum())
    }

    @Test
    fun `상한 상수로 접어도 합이 보존된다`() {
        // 실제 화면이 쓰는 상수로 한 번 재 둔다 — 상수를 옮길 때 이 시험이 함께 돈다.
        val table = (1..40).associate { r ->
            "행$r" to (1..40).associate { c -> "열$c" to (r + c) }
        }

        val folded = fold(table, DisplayCap.CROSS_AXIS_LIMIT, DisplayCap.CROSS_AXIS_LIMIT)

        assertEquals(folded.grandTotal, folded.sum())
        assertEquals(DisplayCap.CROSS_AXIS_LIMIT + 1, folded.rows.size)
        assertEquals(DisplayCap.CROSS_AXIS_LIMIT + 1, folded.cols.size)
        // 1,600칸이 169칸이 된다 — 이 자리만 비용이 곱셈이라 상한이 반드시 있어야 한다.
        assertEquals(169, folded.rows.size * folded.cols.size)
    }
}
