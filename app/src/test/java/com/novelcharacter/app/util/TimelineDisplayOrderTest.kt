package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 연표 표시 순서 (B-47).
 *
 * 여기서 잠그는 것은 뒤집기 자체가 아니라 **뒤집었을 때 저장된 것이 상하지 않는가**다 —
 * 드래그 재정렬의 번호 매기기가 그 자리이고, 틀리면 보기 토글 하나가 사용자가 짜 둔
 * 순서를 조용히 뒤집는다(무엇을 잃었는지 알 수 없는 부류).
 */
class TimelineDisplayOrderTest {

    private fun event(id: Long, year: Int, order: Int = 0) =
        TimelineEvent(id = id, year = year, description = "e$id", displayOrder = order, code = "E$id")

    // ── arrange ──

    @Test fun arrange_ascendingKeepsQueryOrder() {
        val events = listOf(event(1, 1000), event(2, 1010), event(3, 1020))
        assertEquals(events, TimelineDisplayOrder.arrange(events, descending = false))
    }

    @Test fun arrange_descendingReversesWholeList() {
        val events = listOf(event(1, 1000), event(2, 1010), event(3, 1020))
        assertEquals(
            listOf(3L, 2L, 1L),
            TimelineDisplayOrder.arrange(events, descending = true).map { it.id }
        )
    }

    @Test fun arrange_descendingAlsoReversesSameDateTies() {
        // 같은 해의 두 사건은 displayOrder로 갈린다 — 역순은 그 안쪽도 함께 뒤집는다.
        // 한 축만 뒤집으면 화면 안에서 읽는 방향이 갈린다.
        val events = listOf(event(1, 1000, order = 0), event(2, 1000, order = 1), event(3, 1010))
        assertEquals(
            listOf(3L, 2L, 1L),
            TimelineDisplayOrder.arrange(events, descending = true).map { it.id }
        )
    }

    @Test fun arrange_doesNotMutateInput() {
        val events = listOf(event(1, 1000), event(2, 1010))
        TimelineDisplayOrder.arrange(events, descending = true)
        assertEquals(listOf(1L, 2L), events.map { it.id })
    }

    @Test fun arrange_emptyIsSafeBothWays() {
        assertEquals(emptyList<TimelineEvent>(), TimelineDisplayOrder.arrange(emptyList(), false))
        assertEquals(emptyList<TimelineEvent>(), TimelineDisplayOrder.arrange(emptyList(), true))
    }

    // ── canonicalReorder ──

    @Test fun reorder_ascendingNumbersFromTop() {
        val visual = listOf(event(7, 1000), event(8, 1000), event(9, 1000))
        val saved = TimelineDisplayOrder.canonicalReorder(visual, descending = false)
        assertEquals(listOf(0, 1, 2), saved.map { it.displayOrder })
        assertEquals(listOf(7L, 8L, 9L), saved.map { it.id })
    }

    @Test fun reorder_descendingNumbersFromBottom() {
        // 역순 화면에서 보이던 차례 [7, 8, 9]는 시간순으로는 [9, 8, 7]이다.
        val visual = listOf(event(7, 1000), event(8, 1000), event(9, 1000))
        val saved = TimelineDisplayOrder.canonicalReorder(visual, descending = true)
        assertEquals(listOf(2, 1, 0), saved.map { it.displayOrder })
    }

    /**
     * 이 시험이 이 파일의 본론이다 — **역순에서 끌어 만든 배치가 그대로 보존되는가.**
     * 번호를 눈에 보이는 차례대로 매기면(즉 `descending`을 무시하면) 이 단언이 깨진다.
     */
    @Test fun reorder_descendingArrangementSurvivesRoundTrip() {
        val visual = listOf(event(7, 1000), event(8, 1000), event(9, 1000))
        val saved = TimelineDisplayOrder.canonicalReorder(visual, descending = true)
        // 저장 뒤 DB는 displayOrder 오름차순으로 돌려준다
        val fromDb = saved.sortedBy { it.displayOrder }
        // 그것을 다시 역순으로 그리면 사용자가 만든 그 차례여야 한다
        val redrawn = TimelineDisplayOrder.arrange(fromDb, descending = true)
        assertEquals(visual.map { it.id }, redrawn.map { it.id })
    }

    @Test fun reorder_ascendingArrangementSurvivesRoundTrip() {
        val visual = listOf(event(7, 1000), event(9, 1000), event(8, 1000))
        val saved = TimelineDisplayOrder.canonicalReorder(visual, descending = false)
        val redrawn = TimelineDisplayOrder.arrange(saved.sortedBy { it.displayOrder }, descending = false)
        assertEquals(visual.map { it.id }, redrawn.map { it.id })
    }

    @Test fun reorder_singleItemAndEmpty() {
        assertEquals(listOf(0), TimelineDisplayOrder.canonicalReorder(listOf(event(1, 1000)), true).map { it.displayOrder })
        assertEquals(emptyList<TimelineEvent>(), TimelineDisplayOrder.canonicalReorder(emptyList(), true))
    }

    // ── isEarlierInDisplay ──

    @Test fun earlierInDisplay_followsDirection() {
        assertTrue(TimelineDisplayOrder.isEarlierInDisplay(900, 1000, descending = false))
        assertFalse(TimelineDisplayOrder.isEarlierInDisplay(1100, 1000, descending = false))
        assertTrue(TimelineDisplayOrder.isEarlierInDisplay(1100, 1000, descending = true))
        assertFalse(TimelineDisplayOrder.isEarlierInDisplay(900, 1000, descending = true))
    }

    @Test fun earlierInDisplay_sameYearIsNeitherWay() {
        assertFalse(TimelineDisplayOrder.isEarlierInDisplay(1000, 1000, descending = false))
        assertFalse(TimelineDisplayOrder.isEarlierInDisplay(1000, 1000, descending = true))
    }

    // ── displayIndexOf ──

    @Test fun displayIndex_mirrorsWhenDescending() {
        assertEquals(0, TimelineDisplayOrder.displayIndexOf(0, size = 5, descending = false))
        assertEquals(4, TimelineDisplayOrder.displayIndexOf(0, size = 5, descending = true))
        assertEquals(0, TimelineDisplayOrder.displayIndexOf(4, size = 5, descending = true))
    }

    @Test fun displayIndex_keepsUnknownPosition() {
        // -1은 '어느 사건에도 걸치지 않음'이다 — 뒤집어서 자리를 만들어 내면 안 된다
        assertEquals(-1, TimelineDisplayOrder.displayIndexOf(-1, size = 5, descending = true))
        assertEquals(-1, TimelineDisplayOrder.displayIndexOf(-1, size = 5, descending = false))
    }

    // ── 기원전(음수 연도) ──

    @Test fun displayOrder_handlesNegativeYears() {
        // 기원전을 음수로 쓰는 작품 — 뒤집기는 값이 아니라 차례를 다루므로 부호를 타지 않는다
        val events = listOf(event(1, -500), event(2, -100), event(3, 200))
        assertEquals(
            listOf(3L, 2L, 1L),
            TimelineDisplayOrder.arrange(events, descending = true).map { it.id }
        )
        assertTrue(TimelineDisplayOrder.isEarlierInDisplay(-100, -500, descending = true))
    }
}
