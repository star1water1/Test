package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ListNameFilterTest {
    private data class Row(val name: String, val description: String = "")

    private val rows = listOf(Row("Knight Order", "왕국의 기사단"), Row("도적 길드"), Row("ＮＰＣ 모임", "knight 지망생"))

    private fun find(query: String?) = ListNameFilter.filter(rows, query) { listOf(it.name, it.description) }

    @Test fun blankQueryReturnsTheSameListSoReorderingSeesEverything() {
        assertSame(rows, find(""))
        assertSame(rows, find("   "))
        assertSame(rows, find(null))
        assertFalse(ListNameFilter.isActive(" \t"))
    }

    @Test fun caseAndSpacingDoNotMatter() {
        assertEquals(listOf(rows[0], rows[2]), find("KNIGHT"))
        assertEquals(listOf(rows[0]), find("knightorder"))
        assertEquals(listOf(rows[1]), find("도적길드"))
        assertEquals(listOf(rows[1]), find(" 적 길 "))
    }

    @Test fun descriptionIsSearchedAndOrderIsKept() {
        assertEquals(listOf(rows[0]), find("기사단"))
        assertEquals(listOf(rows[0], rows[2]), find("knight"))
    }

    @Test fun noMatchIsEmptyAndNullTextsAreIgnored() {
        assertTrue(find("없는 이름").isEmpty())
        assertFalse(ListNameFilter.matches("a", null, ""))
        assertTrue(ListNameFilter.matches("", null))
    }
}
