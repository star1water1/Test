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
        // **돌려주는 것은 시간순 차례**이므로 id도 뒤집혀 나온다.
        val visual = listOf(event(7, 1000), event(8, 1000), event(9, 1000))
        val saved = TimelineDisplayOrder.canonicalReorder(visual, descending = true)
        assertEquals(listOf(9L, 8L, 7L), saved.map { it.id })
        assertEquals(listOf(0, 1, 2), saved.map { it.displayOrder })
    }

    /**
     * **번호는 날짜 묶음마다 0부터다.** `displayOrder`는 DAO 술어의 *같은 날짜 안
     * 타이브레이크*일 뿐이라, 목록 전체에 전역 인덱스를 매기면 다른 날짜의 번호끼리 섞인다.
     */
    @Test fun reorder_numbersRestartPerDate() {
        val visual = listOf(
            event(1, 1000), event(2, 1000),
            event(3, 1010), event(4, 1010), event(5, 1010)
        )
        val saved = TimelineDisplayOrder.canonicalReorder(visual, descending = false)
        assertEquals(listOf(0, 1, 0, 1, 2), saved.map { it.displayOrder })
    }

    // ── mergeDateGroup — 화면 밖 형제 ──

    /**
     * 화면 목록은 창·필터로 잘린 **부분집합**이다. 그 부분집합에만 번호를 적으면 같은 날짜의
     * 나머지가 옛 번호를 든 채 남아 번호가 겹치고 순서가 부정이 된다.
     *
     * **화면 밖 형제는 제자리(슬롯)에 남는다** — 뒤로 밀지 않는다(콜드 검토 2026.08.21).
     * 종전에는 이어 붙였고, 그것은 *"보지 못한 것의 자리를 이 조작이 정하지 않는다"*는
     * 이 함수의 약속을 스스로 깨는 것이었다(맨 앞 형제가 맨 뒤로 갔다).
     */
    @Test fun merge_hiddenSiblingsKeepTheirSlot() {
        // 사용자가 만든 차례: 2 → 1. `visible`은 canonicalReorder가 새 번호를 얹어 온 사본이다.
        val visible = listOf(event(2, 1000, order = 0), event(1, 1000, order = 1))
        // DB의 저장값: 1=0, 9=1, 2=2 (9는 창·필터에 가려 화면에 없던 형제 — **가운데**다)
        val all = listOf(
            event(1, 1000, order = 0), event(9, 1000, order = 1), event(2, 1000, order = 2)
        )
        val updates = TimelineDisplayOrder.mergeDateGroup(visible, all).associate { it.id to it.displayOrder }
        // 보이는 둘이 있던 슬롯(0·2)만 사용자가 만든 차례로 갈아 끼운다.
        assertEquals(0, updates[2L])
        assertEquals(2, updates[1L])
        // 9는 자기 자리 그대로라 저장할 것이 없다.
        assertEquals(null, updates[9L])
    }

    /**
     * **끌지 않고 재정렬 모드만 껐다 켜도 저장이 돈다**(`TimelineFragment`의 모드 종료 갈래는
     * 끌었는지를 묻지 않는다). 그때 한 칸도 움직이지 않아야 한다 — 종전에는 필터에 가린
     * 형제가 뒤로 밀려, 사용자가 손대지도 않은 사건의 앞뒤가 조용히 바뀌었다.
     */
    @Test fun merge_noDragMovesNothing() {
        // 필터가 1·2만 보여 준다. 사용자는 아무것도 끌지 않았다(보이던 차례 그대로).
        val visible = listOf(event(1, 1000, order = 0), event(2, 1000, order = 1))
        val all = listOf(
            event(9, 1000, order = 0), event(1, 1000, order = 1),
            event(8, 1000, order = 2), event(2, 1000, order = 3)
        )
        assertEquals(
            "끌지 않았는데 저장할 것이 생기면 그것이 곧 조용한 왜곡이다",
            emptyList<TimelineEvent>(),
            TimelineDisplayOrder.mergeDateGroup(visible, all)
        )
    }

    /**
     * **'안 바뀌었다'의 기준은 저장값이다.** `visible`이 든 번호와 견주면 보이는 사건은
     * 언제나 '그대로'가 되어 한 행도 저장되지 않는다(수리 중 실제로 그 함정에 한 번 빠졌다).
     */
    @Test fun merge_onlyChangedRowsAreReturned() {
        val visible = listOf(event(1, 1000, order = 0), event(2, 1000, order = 1))
        val all = listOf(event(1, 1000, order = 0), event(2, 1000, order = 1))
        assertEquals(emptyList<TimelineEvent>(), TimelineDisplayOrder.mergeDateGroup(visible, all))
    }

    /** 자리가 실제로 뒤집힌 경우는 둘 다 저장된다. */
    @Test fun merge_swappedVisiblePairIsWritten() {
        val visible = listOf(event(2, 1000, order = 0), event(1, 1000, order = 1))
        val all = listOf(event(1, 1000, order = 0), event(2, 1000, order = 1))
        val updates = TimelineDisplayOrder.mergeDateGroup(visible, all).associate { it.id to it.displayOrder }
        assertEquals(mapOf(2L to 0, 1L to 1), updates)
    }

    /**
     * 이 시험이 이 파일의 본론이다 — **역순에서 끌어 만든 배치가 그대로 보존되는가.**
     * 번호를 눈에 보이는 차례대로 매기면(즉 `descending`을 무시하면) 이 단언이 깨진다.
     */
    @Test fun reorder_descendingArrangementSurvivesRoundTrip() {
        val visual = listOf(event(7, 1000), event(8, 1000), event(9, 1000))
        val saved = TimelineDisplayOrder.canonicalReorder(visual, descending = true)
        // (돌려받은 목록은 시간순이지만 아래는 displayOrder로 다시 세우므로 차례에 기대지 않는다)
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
