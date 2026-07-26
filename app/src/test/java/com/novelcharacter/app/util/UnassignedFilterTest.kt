package com.novelcharacter.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 미소속/미배정 sentinel 매칭 진리표 — sentinel 유무 × null/매칭/비매칭 */
class UnassignedFilterTest {

    // ===== matchesNovel =====

    @Test
    fun novel_realIdSelected() {
        val selected = setOf(3L)
        assertTrue(UnassignedFilter.matchesNovel(3L, selected))
        assertFalse(UnassignedFilter.matchesNovel(4L, selected))
        assertFalse(UnassignedFilter.matchesNovel(null, selected))
    }

    @Test
    fun novel_sentinelSelected_matchesOnlyUnassigned() {
        val selected = setOf(UnassignedFilter.NO_NOVEL_ID)
        assertTrue(UnassignedFilter.matchesNovel(null, selected))
        assertFalse(UnassignedFilter.matchesNovel(3L, selected))
    }

    @Test
    fun novel_sentinelPlusRealId_orSemantics() {
        val selected = setOf(UnassignedFilter.NO_NOVEL_ID, 3L)
        assertTrue(UnassignedFilter.matchesNovel(null, selected))
        assertTrue(UnassignedFilter.matchesNovel(3L, selected))
        assertFalse(UnassignedFilter.matchesNovel(4L, selected))
    }

    // ===== matchesFaction =====

    @Test
    fun faction_realIdSelected() {
        val selected = setOf(7L)
        assertTrue(UnassignedFilter.matchesFaction(listOf(7L, 9L), selected))
        assertFalse(UnassignedFilter.matchesFaction(listOf(9L), selected))
        assertFalse(UnassignedFilter.matchesFaction(null, selected))
        assertFalse(UnassignedFilter.matchesFaction(emptyList(), selected))
    }

    @Test
    fun faction_sentinelSelected_matchesOnlyFactionless() {
        val selected = setOf(UnassignedFilter.NO_FACTION_ID)
        assertTrue(UnassignedFilter.matchesFaction(null, selected))
        assertTrue(UnassignedFilter.matchesFaction(emptyList(), selected))
        assertFalse(UnassignedFilter.matchesFaction(listOf(7L), selected))
    }

    @Test
    fun faction_sentinelPlusRealId_orSemantics() {
        val selected = setOf(UnassignedFilter.NO_FACTION_ID, 7L)
        assertTrue(UnassignedFilter.matchesFaction(null, selected))
        assertTrue(UnassignedFilter.matchesFaction(listOf(7L), selected))
        assertFalse(UnassignedFilter.matchesFaction(listOf(9L), selected))
    }
}
