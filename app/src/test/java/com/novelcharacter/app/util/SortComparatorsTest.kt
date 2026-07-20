package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** nullsLast — 값 없음 최후순(방향 무관) + 동값 타이브레이크(이름 오름). 필드 정렬 정확성 가드. */
class SortComparatorsTest {

    private data class Row(val name: String, val key: Int?)

    private fun sort(rows: List<Row>, ascending: Boolean): List<String> =
        rows.sortedWith(SortComparators.nullsLast(ascending, { it.name }, { it.key })).map { it.name }

    @Test
    fun ascending_ordersByKey_nullsLast() {
        val rows = listOf(Row("b", 2), Row("noval", null), Row("a", 1))
        assertEquals(listOf("a", "b", "noval"), sort(rows, true))
    }

    @Test
    fun descending_ordersByKey_nullsStillLast() {
        val rows = listOf(Row("b", 2), Row("noval", null), Row("a", 1))
        assertEquals(listOf("b", "a", "noval"), sort(rows, false))
    }

    @Test
    fun equalKeys_tiebreakByNameAscending_regardlessOfDirection() {
        val rows = listOf(Row("charlie", 5), Row("alpha", 5), Row("bravo", 5))
        assertEquals(listOf("alpha", "bravo", "charlie"), sort(rows, true))
        assertEquals(listOf("alpha", "bravo", "charlie"), sort(rows, false))
    }

    @Test
    fun allNull_tiebreakByNameAscending() {
        val rows = listOf(Row("z", null), Row("a", null))
        assertEquals(listOf("a", "z"), sort(rows, true))
        assertEquals(listOf("a", "z"), sort(rows, false))
    }
}
