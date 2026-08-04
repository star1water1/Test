package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 홈의 대결 진입점이 어디를 여는가(B-104 화면 계층)를 지키는 하네스.
 *
 * **이 판단이 틀려도 앱은 죽지 않는다** — 엉뚱한 축이 열리거나 빈 화면이 뜰 뿐이고, 컴파일도
 * 정적 검사도 통과한다. 그래서 판단을 pure로 내려 여기서 잰다(「세션 착수 규칙」 4번).
 */
class DuelEntryTest {

    @Test
    fun `이어할 축이 살아 있으면 곧바로 그 대결로`() {
        val target = DuelEntry.targetOf(
            lastAxisId = 7L,
            lastAxisUniverseId = 3L,
            universeIds = listOf(1L, 2L, 3L)
        )

        assertEquals(DuelEntry.Target.Resume(7L, 3L), target)
    }

    @Test
    fun `기억해 둔 축이 지워졌으면 재개하지 않는다`() {
        // 휴지통으로 갔거나 세계관째 사라진 경우 — 그 id로 열면 빈 화면이 뜬다.
        val target = DuelEntry.targetOf(
            lastAxisId = 7L,
            lastAxisUniverseId = null,
            universeIds = listOf(1L, 2L)
        )

        assertTrue(target is DuelEntry.Target.PickUniverse)
    }

    @Test
    fun `기억이 없고 세계관이 하나면 고르게 하지 않는다`() {
        val target = DuelEntry.targetOf(0L, null, listOf(5L))

        assertEquals(DuelEntry.Target.AxisList(5L), target)
    }

    @Test
    fun `세계관이 여럿이면 묻는다 — 순서는 받은 그대로`() {
        val target = DuelEntry.targetOf(0L, null, listOf(5L, 1L, 9L))

        assertEquals(DuelEntry.Target.PickUniverse(listOf(5L, 1L, 9L)), target)
    }

    @Test
    fun `세계관이 없으면 대결 이전에 만들 것이 있다`() {
        assertEquals(DuelEntry.Target.NeedUniverse, DuelEntry.targetOf(0L, null, emptyList()))
    }

    @Test
    fun `축 id가 0이나 음수면 기억이 없는 것으로 본다`() {
        // prefs 기본값이 0이다. 살아 있는 세계관 정보가 함께 와도 재개하지 않는다.
        assertEquals(DuelEntry.Target.AxisList(4L), DuelEntry.targetOf(0L, 4L, listOf(4L)))
        assertEquals(DuelEntry.Target.AxisList(4L), DuelEntry.targetOf(-1L, 4L, listOf(4L)))
    }
}
