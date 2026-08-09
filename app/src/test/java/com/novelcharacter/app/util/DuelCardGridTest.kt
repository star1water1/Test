package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카드를 **어느 자리에 놓는가**(B-115)를 지키는 하네스.
 *
 * 이 판단이 순수 계층에 있는 것은 자동 검증이 화면을 못 보기 때문이다(「세션 착수 규칙」 4번).
 * 아래가 깨지면 화면에서 나는 증상은 각각 *"1:1인데 배치가 달라졌다"* ·
 * *"셋 중 하나만 두 배로 커서 그게 정답처럼 보인다"* · *"안 보이는 카드를 눌러 판이 적힌다"*이다.
 */
class DuelCardGridTest {

    @Test
    fun `나란히 1대1은 윗줄 둘이고 아랫줄이 사라진다`() {
        val plan = DuelCardGrid.plan(count = 2, stacked = false)

        assertEquals(listOf(DuelCardGrid.SLOT_TOP_START, DuelCardGrid.SLOT_TOP_END), plan.slots)
        assertFalse(plan.bottomRow)
        assertTrue(plan.inlineVersus)
        assertFalse(plan.stackVersus)
        assertFalse(plan.compact)
        assertFalse(plan.horizontalCard)
    }

    /**
     * 위아래 1:1이 슬롯 1이 아니라 **2**를 쓰는 것이 요점이다 — 그래야 아랫줄이 실제로
     * 아래이고, 카드 안이 가로로 도는 B-122의 규칙이 그대로 성립한다.
     */
    @Test
    fun `위아래 1대1은 두 줄에 하나씩이고 카드 안이 가로다`() {
        val plan = DuelCardGrid.plan(count = 2, stacked = true)

        assertEquals(listOf(DuelCardGrid.SLOT_TOP_START, DuelCardGrid.SLOT_BOTTOM_START), plan.slots)
        assertTrue(plan.bottomRow)
        assertFalse(plan.inlineVersus)
        assertTrue(plan.stackVersus)
        assertTrue(plan.horizontalCard)
        assertFalse(plan.compact)
    }

    /**
     * **넷째 슬롯이 `spacers`이지 `hidden`이 아니다.** 없애면 셋째 카드가 아랫줄을 통째로
     * 차지해 혼자 두 배로 커지고, 큰 카드는 그 자체로 눈을 끈다 — 영향 필드의 우열 강조조차
     * 금지한 화면에서 그것은 더 노골적인 편들기다.
     */
    @Test
    fun `셋이면 넷째 자리가 비어도 자리를 지킨다`() {
        val plan = DuelCardGrid.plan(count = 3, stacked = false)

        assertEquals(
            listOf(
                DuelCardGrid.SLOT_TOP_START,
                DuelCardGrid.SLOT_TOP_END,
                DuelCardGrid.SLOT_BOTTOM_START
            ),
            plan.slots
        )
        assertEquals(listOf(DuelCardGrid.SLOT_BOTTOM_END), plan.spacers)
        assertEquals(emptyList<Int>(), plan.hidden)
        assertTrue(plan.bottomRow)
    }

    @Test
    fun `넷이면 슬롯을 다 쓰고 남는 자리가 없다`() {
        val plan = DuelCardGrid.plan(count = 4, stacked = false)

        assertEquals(4, plan.cardCount)
        assertEquals(emptyList<Int>(), plan.spacers)
        assertEquals(emptyList<Int>(), plan.hidden)
    }

    @Test
    fun `셋 이상이면 정보 패널이 접히고 vs가 사라진다`() {
        for (count in 3..4) {
            val plan = DuelCardGrid.plan(count, stacked = false)
            assertTrue("count=$count", plan.compact)
            assertFalse("count=$count", plan.inlineVersus)
            assertFalse("count=$count", plan.stackVersus)
        }
    }

    /** 격자는 이미 두 줄이라 '나란히/위아래'가 뜻을 잃는다 — 설정이 무엇이든 같은 계획이다. */
    @Test
    fun `셋 이상에서는 배치 설정이 계획을 바꾸지 않는다`() {
        for (count in 3..4) {
            assertEquals(
                DuelCardGrid.plan(count, stacked = false),
                DuelCardGrid.plan(count, stacked = true)
            )
        }
    }

    @Test
    fun `셋 이상에서는 카드 안이 언제나 세로다`() {
        // 카드가 화면의 4분의 1인데 가로로 또 가르면 그림도 패널도 못 쓸 만큼 좁아진다.
        assertFalse(DuelCardGrid.plan(3, stacked = true).horizontalCard)
        assertFalse(DuelCardGrid.plan(4, stacked = true).horizontalCard)
    }

    @Test
    fun `쓰지 않는 슬롯은 숨김과 자리지킴 둘 중 하나로만 분류된다`() {
        for (count in 0..4) {
            for (stacked in listOf(false, true)) {
                val plan = DuelCardGrid.plan(count, stacked)
                val all = plan.slots + plan.spacers + plan.hidden
                assertEquals("count=$count stacked=$stacked", 4, all.size)
                assertEquals("count=$count stacked=$stacked", 4, all.toSet().size)
            }
        }
    }

    @Test
    fun `짝이 없는 상태에서도 계획은 나온다`() {
        val none = DuelCardGrid.plan(count = 0, stacked = false)

        assertEquals(emptyList<Int>(), none.slots)
        assertEquals(4, none.hidden.size)
        assertFalse(none.bottomRow)
    }
}
