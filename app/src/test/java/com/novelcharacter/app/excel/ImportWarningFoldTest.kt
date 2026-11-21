package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 고지 줄 접기의 계약 (2026.11.21에 단일 소스로 올림).
 *
 * 접는 규칙이 자리마다 따로 적히면 **같은 파일을 들인 사용자가 범주마다 다른 상한을 겪는다.**
 * 이 시험은 그 셈을 못박는다 — 특히 **수는 깎지 않는다**는 쪽이다(줄만 묶는다).
 */
class ImportWarningFoldTest {

    private fun tail(remaining: Int, total: Int) = "나머지 $remaining / 전체 $total"

    @Test
    fun `상한 안이면 그대로 전부 싣는다`() {
        val lines = (1..ImportWarningFold.LIST_CAP).map { "줄$it" }
        assertEquals(lines, ImportWarningFold.fold(lines, lines.size, tail = ::tail))
    }

    @Test
    fun `상한을 한 건 넘으면 앞머리만 싣고 꼬리로 센다`() {
        // 부르는 쪽은 상한까지만 줄을 쥔다 — 그것이 이 함수의 전제다.
        val held = (1..ImportWarningFold.LIST_CAP).map { "줄$it" }
        val folded = ImportWarningFold.fold(held, total = ImportWarningFold.LIST_CAP + 1, tail = ::tail)
        assertEquals(ImportWarningFold.HEAD + 1, folded.size)
        assertEquals(held.take(ImportWarningFold.HEAD), folded.dropLast(1))
        assertEquals("나머지 ${ImportWarningFold.LIST_CAP + 1 - ImportWarningFold.HEAD} / 전체 ${ImportWarningFold.LIST_CAP + 1}", folded.last())
    }

    /**
     * **수는 끝까지 센다** — S25의 3836건이 이 함수를 지나도 *3836*이라 말해야 한다.
     * 줄을 버리는 것과 사실을 깎는 것은 다른 일이다(개발 의도 2번).
     */
    @Test
    fun `쥔 줄보다 전체가 훨씬 많아도 전체 수를 그대로 말한다`() {
        val held = (1..ImportWarningFold.LIST_CAP).map { "줄$it" }
        val folded = ImportWarningFold.fold(held, total = 3836, tail = ::tail)
        assertTrue(folded.last(), folded.last().contains("전체 3836"))
        assertTrue(folded.last(), folded.last().contains("나머지 ${3836 - ImportWarningFold.HEAD}"))
    }

    @Test
    fun `아무 건도 없으면 아무 줄도 내지 않는다`() {
        assertEquals(emptyList<String>(), ImportWarningFold.fold(emptyList(), total = 0, tail = ::tail))
    }

    /** 상한·앞머리는 부르는 쪽이 좁힐 수 있다 — 기본값에 묶이면 다른 셈을 가진 자리가 못 쓴다. */
    @Test
    fun `상한과 앞머리를 부르는 쪽이 정할 수 있다`() {
        val held = listOf("a", "b", "c")
        val folded = ImportWarningFold.fold(held, total = 9, cap = 2, head = 1, tail = ::tail)
        assertEquals(listOf("a", "나머지 8 / 전체 9"), folded)
    }

    @Test
    fun `앞머리가 쥔 줄보다 많아도 쥔 것만 싣는다`() {
        val held = listOf("a", "b")
        val folded = ImportWarningFold.fold(held, total = 50, cap = 1, head = 10, tail = ::tail)
        assertEquals(listOf("a", "b", "나머지 40 / 전체 50"), folded)
    }
}
