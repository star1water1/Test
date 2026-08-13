package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DisplayCap] — 통계 탭의 표시 상한 (B-199 · B-194).
 *
 * 이 하네스가 지키는 것은 넷이고, **앞의 둘은 서로 반대 방향의 사고를 막는다.**
 *
 * 1. **접은 수를 정확히 말한다** — R-14는 *잘라냈으면 개수로 존재를 알린다*이므로,
 *    `hiddenCount`가 틀리면 상한이 "접기"가 아니라 **조용한 감추기**가 된다.
 * 2. **축별 상한이 원래 순서를 건드리지 않는다**(*반대편이다* — 1만 있으면 `groupBy` 뒤에
 *    축마다 `take`하는 구현이 통과하는데, 그러면 결과가 **축 순서로 재정렬되어**
 *    심각도 내림차순이 깨진다. 높음 카드가 낮음 카드 아래로 내려가도 개수는 맞으므로
 *    1번 시험은 아무것도 못 본다).
 * 3. **한 번의 '더 보기'가 늘리는 수는 정확히 한 묶음이다** — 이 상한의 존재 이유가 그것이다.
 *    *"나머지 전부"*로 구현하면 한 번의 탭이 6,420개를 만들어 상한이 무의미해진다.
 * 4. **상한을 끄면 전량이다** — 0 이하를 "아무것도 안 보임"으로 읽으면 설정 하나로 화면이 빈다.
 */
class DisplayCapTest {

    // ── 1. 앞에서부터 자르고, 접은 수를 그대로 말한다 ────────────────────────

    @Test
    fun `상한 이하면 그대로 두고 접은 것이 없다고 말한다`() {
        val items = listOf("가", "나", "다")
        val capped = DisplayCap.cap(items, 5)

        assertEquals(items, capped.shown)
        assertEquals(0, capped.hiddenCount)
        assertEquals(3, capped.totalCount)
        assertFalse(capped.hasHidden)
    }

    @Test
    fun `상한을 넘으면 앞에서부터 남기고 나머지 수를 말한다`() {
        val items = (1..120).map { "캐릭터$it" }
        val capped = DisplayCap.cap(items, DisplayCap.NAME_LIST_CHUNK)

        assertEquals(DisplayCap.NAME_LIST_CHUNK, capped.shown.size)
        assertEquals("캐릭터1", capped.shown.first())
        assertEquals("캐릭터50", capped.shown.last())
        // 접은 수 = 전체 - 보인 수. 이 값이 그대로 "N개 더" 문구가 된다(R-14).
        assertEquals(70, capped.hiddenCount)
        assertEquals(120, capped.totalCount)
        assertTrue(capped.hasHidden)
    }

    @Test
    fun `상한이 0 이하이면 상한이 없는 것으로 본다`() {
        val items = (1..300).map { "값$it" }

        assertEquals(300, DisplayCap.cap(items, 0).shown.size)
        assertEquals(0, DisplayCap.cap(items, 0).hiddenCount)
        assertEquals(300, DisplayCap.cap(items, -1).shown.size)
    }

    @Test
    fun `빈 목록은 접을 것도 셀 것도 없다`() {
        val capped = DisplayCap.cap(emptyList<String>(), 10)

        assertTrue(capped.shown.isEmpty())
        assertEquals(0, capped.hiddenCount)
        assertEquals(0, capped.totalCount)
    }

    // ── 2. 축별 상한 — 정원은 축마다, 순서는 원래대로 ────────────────────────

    private data class Card(val axis: String, val name: String)

    @Test
    fun `축마다 따로 세되 표시 순서는 입력 순서 그대로다`() {
        // 심각도 순으로 이미 서 있는 목록을 흉내 낸다 — 축이 섞여 있는 것이 정상이다.
        val cards = listOf(
            Card("캐릭터", "높음1"),
            Card("사건", "높음2"),
            Card("캐릭터", "높음3"),
            Card("캐릭터", "보통1"),
            Card("작품", "보통2"),
            Card("캐릭터", "정보1")
        )

        val capped = DisplayCap.capPerGroup(cards, limit = 2) { it.axis }

        // 캐릭터 축은 정원 2를 넘으므로 뒤엣것 둘이 접힌다.
        assertEquals(
            listOf("높음1", "높음2", "높음3", "보통2"),
            capped.shown.map { it.name }
        )
        // **여기가 반대편이다** — groupBy로 지으면 결과가 (캐릭터…, 사건…, 작품…) 순이 되어
        // 위 기대와 갈린다. 개수만 재는 시험은 그것을 통과시킨다.
        assertEquals(2, capped.hiddenByGroup["캐릭터"])
        assertEquals(2, capped.hiddenCount)
        assertEquals(6, capped.totalCount)
    }

    @Test
    fun `한 축이 넘쳐도 다른 축의 자리를 빼앗지 않는다`() {
        // 캐릭터 카드가 100장이어도 사건 카드는 자기 정원만큼 반드시 뜬다 —
        // 통째로 상한 하나를 걸면 이 성질이 사라지고, 그것이 축별로 나눈 이유다.
        val cards = (1..100).map { Card("캐릭터", "캐$it") } + listOf(
            Card("사건", "사1"),
            Card("사건", "사2")
        )

        val capped = DisplayCap.capPerGroup(cards, DisplayCap.PATTERN_CARDS_PER_AXIS) { it.axis }

        assertEquals(DisplayCap.PATTERN_CARDS_PER_AXIS, capped.shown.count { it.axis == "캐릭터" })
        assertEquals(2, capped.shown.count { it.axis == "사건" })
        assertEquals(100 - DisplayCap.PATTERN_CARDS_PER_AXIS, capped.hiddenByGroup["캐릭터"])
        // 넘치지 않은 축은 아예 등장하지 않는다 — "0개 접혔다"고 말할 자리가 없어야 한다.
        assertFalse(capped.hiddenByGroup.containsKey("사건"))
    }

    @Test
    fun `접힌 축은 첫 등장 순서로 보고한다`() {
        val cards = listOf(
            Card("사건", "사1"), Card("사건", "사2"),
            Card("캐릭터", "캐1"), Card("캐릭터", "캐2")
        )

        val capped = DisplayCap.capPerGroup(cards, limit = 1) { it.axis }

        assertEquals(listOf("사건", "캐릭터"), capped.hiddenByGroup.keys.toList())
        assertEquals(2, capped.hiddenCount)
    }

    @Test
    fun `축별 상한이 0 이하이면 전량이다`() {
        val cards = (1..30).map { Card("캐릭터", "캐$it") }

        val capped = DisplayCap.capPerGroup(cards, limit = 0) { it.axis }

        assertEquals(30, capped.shown.size)
        assertFalse(capped.hasHidden)
    }

    // ── 3. 묶음 단위 펼치기 ─────────────────────────────────────────────────

    @Test
    fun `펼치기 한 번이 늘리는 수는 정확히 한 묶음이다`() {
        val total = 6_420

        assertEquals(50, DisplayCap.shownCount(total, chunk = 50, steps = 0))
        assertEquals(100, DisplayCap.shownCount(total, chunk = 50, steps = 1))
        assertEquals(150, DisplayCap.shownCount(total, chunk = 50, steps = 2))
    }

    @Test
    fun `마지막 묶음은 전체에서 멈춘다`() {
        // 120개를 50씩 펼치면 세 번째 걸음이 150이 아니라 120이어야 한다 —
        // 여기가 틀리면 화면이 "0개 더"를 띄우거나 목록 밖을 읽는다.
        assertEquals(120, DisplayCap.shownCount(120, chunk = 50, steps = 2))
        assertEquals(120, DisplayCap.shownCount(120, chunk = 50, steps = 99))
        assertEquals(7, DisplayCap.shownCount(7, chunk = 50, steps = 0))
    }

    @Test
    fun `걸음 수가 아무리 커도 넘치지 않는다`() {
        // 곱이 Int를 넘기면 음수가 되어 목록이 통째로 사라진다 — Long으로 곱하는 이유다.
        assertEquals(500, DisplayCap.shownCount(500, chunk = 50, steps = Int.MAX_VALUE))
        assertEquals(500, DisplayCap.shownCount(500, chunk = Int.MAX_VALUE, steps = 3))
    }

    @Test
    fun `묶음이 0 이하이면 전량을 그린다`() {
        assertEquals(300, DisplayCap.shownCount(300, chunk = 0, steps = 0))
        assertEquals(300, DisplayCap.shownCount(300, chunk = -5, steps = 0))
    }

    @Test
    fun `음수 걸음은 첫 표시분으로 본다`() {
        assertEquals(50, DisplayCap.shownCount(6_420, chunk = 50, steps = -3))
    }
}
