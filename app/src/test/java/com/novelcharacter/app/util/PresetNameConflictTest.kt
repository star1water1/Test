package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 프리셋 이름 겹침 판정의 계약을 고정한다 (B-191, 확정 15장 1번).
 *
 * **왜 순수 계층에 있는가:** 판정은 화면 둘(캐릭터 목록·전역 검색)이 공유하는데, 그 두 화면은
 * Fragment라 이 저장소의 순수 시험이 원리적으로 닿지 않는다. 판정만 내려놓으면 **양쪽이 같은
 * 답을 낸다**를 여기서 잴 수 있다 — 갈리면 사용자는 *"한쪽은 물어보고 한쪽은 안 물어본다"*를 겪는다.
 */
class PresetNameConflictTest {

    @Test
    fun `없는 이름은 그대로 쓴다`() {
        assertEquals(
            PresetNameConflict.Verdict.FREE,
            PresetNameConflict.verdict(existingId = null, existingIsDefault = false)
        )
    }

    @Test
    fun `남의 이름이면 덮어쓸지 묻는다`() {
        assertEquals(
            PresetNameConflict.Verdict.TAKEN,
            PresetNameConflict.verdict(existingId = 7L, existingIsDefault = false)
        )
    }

    @Test
    fun `기본 프리셋의 이름은 덮어쓰기를 제안하지 않는다`() {
        assertEquals(
            PresetNameConflict.Verdict.TAKEN_BY_DEFAULT,
            PresetNameConflict.verdict(existingId = 3L, existingIsDefault = true)
        )
    }

    @Test
    fun `개명 창에서 자기 이름을 그대로 두면 충돌이 아니다`() {
        assertEquals(
            PresetNameConflict.Verdict.SELF,
            PresetNameConflict.verdict(existingId = 12L, existingIsDefault = false, selfId = 12L)
        )
    }

    @Test
    fun `자기 자신 판정이 기본 프리셋보다 앞선다 — 기본 프리셋의 개명은 막히지 않는다`() {
        assertEquals(
            PresetNameConflict.Verdict.SELF,
            PresetNameConflict.verdict(existingId = 5L, existingIsDefault = true, selfId = 5L)
        )
    }

    /**
     * 다듬기는 **DB가 보는 값과 같아야 한다** — 앞뒤 공백만 떼고 대소문자·가운데 공백은 건드리지
     * 않는다. 여기서 더 다듬으면 유니크 색인이 겹치지 않는다고 보는 이름에 창을 띄운다.
     */
    @Test
    fun `다듬기는 앞뒤 공백만 뗀다`() {
        assertEquals("주요 인물", PresetNameConflict.normalize("  주요 인물  "))
        assertEquals("주요  인물", PresetNameConflict.normalize("주요  인물"))
        assertEquals("Main", PresetNameConflict.normalize("Main"))
    }

    @Test
    fun `제안 이름은 비어 있는 첫 번호를 고른다`() {
        assertEquals("주요 인물 (2)", PresetNameConflict.suggestAlternative("주요 인물", listOf("주요 인물")))
        assertEquals(
            "주요 인물 (3)",
            PresetNameConflict.suggestAlternative("주요 인물", listOf("주요 인물", "주요 인물 (2)"))
        )
    }

    /** 제안받은 이름을 다시 제안받아도 `이름 (2) (2)`로 늘어나지 않는다. */
    @Test
    fun `이미 번호가 붙은 이름은 꼬리를 떼고 센다`() {
        assertEquals(
            "주요 인물 (3)",
            PresetNameConflict.suggestAlternative("주요 인물 (2)", listOf("주요 인물", "주요 인물 (2)"))
        )
    }

    /** 이름이 숫자 꼬리 하나뿐이면 뗄 것이 없다 — 떼면 빈 이름이 된다. */
    @Test
    fun `꼬리만 있는 이름은 떼지 않는다`() {
        assertEquals("(2) (2)", PresetNameConflict.suggestAlternative("(2)", listOf("(2)")))
    }

    @Test
    fun `제안은 다듬은 이름 위에서 짓는다`() {
        assertEquals("주요 인물 (2)", PresetNameConflict.suggestAlternative("  주요 인물 ", listOf("주요 인물")))
    }
}
