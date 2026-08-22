package com.novelcharacter.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiCheckState] — AI 검토 창의 회차 체크 (R-38).
 *
 * **회전은 순수 계층이 볼 수 없다.** 그래서 여기서 재는 것은 *회전이 일어났을 때 화면이
 * 부르는 순서*를 그대로 흉내 낸 것이다: 창이 다시 조립되면 [AiCheckState.seedDefaults]가
 * 다시 불리고, 그때 사용자의 판단이 기본 규칙에 덮이면 안 된다.
 */
class AiCheckStateTest {

    @Test
    fun `첫 조립에서 기본 선택이 심긴다`() {
        val state = AiCheckState<Long>()
        state.seedDefaults(listOf(1L, 2L, 3L))
        assertTrue(state.isChecked(1L))
        assertTrue(state.isChecked(3L))
        assertFalse(state.isChecked(9L))
    }

    /** 회전 = 같은 회차의 두 번째 조립. 껐던 것이 다시 켜지면 그 초안이 그대로 깔린다. */
    @Test
    fun `다시 조립돼도 껐던 것은 꺼진 채로 남는다`() {
        val state = AiCheckState<Long>()
        state.seedDefaults(listOf(1L, 2L, 3L))
        state.setChecked(2L, false)

        state.seedDefaults(listOf(1L, 2L, 3L))   // 회전 뒤 재조립

        assertTrue(state.isChecked(1L))
        assertFalse("껐던 것이 되살아났다", state.isChecked(2L))
        assertTrue(state.isChecked(3L))
    }

    /**
     * **`touched`였을 때 실제로 났던 결함**(콜드 검토 2026.08.21)의 반대 방향 —
     * 하나를 *켜고* 회전하면 기본으로 켜져 있던 나머지가 통째로 꺼지던 자리다.
     */
    @Test
    fun `하나를 켠 뒤 재조립해도 기본으로 켜진 것이 안 꺼진다`() {
        val state = AiCheckState<String>()
        state.seedDefaults(listOf("a", "b"))
        state.setChecked("c", true)

        state.seedDefaults(listOf("a", "b"))

        assertTrue(state.isChecked("a"))
        assertTrue(state.isChecked("b"))
        assertTrue(state.isChecked("c"))
    }

    /** 회차가 끝나면 다음 회차는 다시 기본 규칙에서 시작한다. */
    @Test
    fun `비우면 다음 회차의 기본이 다시 심긴다`() {
        val state = AiCheckState<Long>()
        state.seedDefaults(listOf(1L))
        state.setChecked(1L, false)
        state.clear()

        assertFalse(state.isChecked(1L))
        state.seedDefaults(listOf(1L, 2L))
        assertTrue(state.isChecked(1L))
        assertTrue(state.isChecked(2L))
    }

    @Test
    fun `기본이 비어 있어도 심은 것으로 친다`() {
        val state = AiCheckState<Long>()
        state.seedDefaults(emptyList())
        state.setChecked(5L, true)
        state.seedDefaults(listOf(1L, 2L))   // 두 번째 심기는 무시된다
        assertTrue(state.isChecked(5L))
        assertFalse(state.isChecked(1L))
    }
}
