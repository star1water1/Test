package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DisplayCap] — 통계 탭의 표시 상한 (B-199 · B-194).
 *
 * 이 하네스가 지키는 것은 셋이고, **앞의 둘은 서로 반대 방향의 사고를 막는다.**
 *
 * 1. **축마다 정원이 따로 있고 접은 수를 축별로 정확히 말한다** — R-14는 *잘라냈으면 개수로
 *    존재를 알린다*이고, 축을 뭉뚱그리면 *"내 사건 카드가 아예 없는 것"*과 *"사건 카드가 더
 *    있는 것"*을 화면에서 가릴 수 없다.
 * 2. **그 상한이 원래 순서를 건드리지 않는다**(**반대편이다** — 1만 있으면 `groupBy` 뒤에
 *    축마다 `take`하는 구현이 통과하는데, 그러면 결과가 **축 순서로 재정렬되어**
 *    심각도 내림차순이 깨진다. 높음 카드가 낮음 카드 아래로 내려가도 **개수는 맞으므로**
 *    1번 시험은 아무것도 못 본다).
 * 3. **한 번의 '더 보기'가 늘리는 수는 정확히 한 묶음이다** — 이 상한의 존재 이유가 그것이다.
 *    *"나머지 전부"*로 구현하면 한 번의 탭이 6,420개를 만들어 상한이 무의미해진다.
 *
 * **넷째는 성격이 다르다 — 상한을 끄거나 아주 크게 잡아도 죽지 않는다.** 0 이하를 "아무것도
 * 안 보임"으로 읽으면 설정 하나로 화면이 비고, 용량을 곱으로 어림하면 `Int`를 넘겨 던진다.
 * 둘 다 지금 상수로는 안 나지만 **상한을 옮기는 순간** 나는 부류다.
 */
class DisplayCapTest {

    // ── 1. 축별 상한 — 정원은 축마다, 순서는 원래대로 ────────────────────────

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
    fun `축별 상한이 아주 커도 죽지 않는다`() {
        // 용량을 `limit × 축 수`로 어림하면 그 곱이 Int를 넘어 음수가 되고
        // `ArrayList(음수)`가 던진다 — 상한을 옮기는 순간 화면이 통째로 죽는 부류다.
        val cards = listOf(Card("캐릭터", "캐1"), Card("사건", "사1"))

        val capped = DisplayCap.capPerGroup(cards, Int.MAX_VALUE) { it.axis }

        assertEquals(2, capped.shown.size)
        assertFalse(capped.hasHidden)
    }

    @Test
    fun `축별 상한이 0 이하이면 전량이다`() {
        val cards = (1..30).map { Card("캐릭터", "캐$it") }

        val capped = DisplayCap.capPerGroup(cards, limit = 0) { it.axis }

        assertEquals(30, capped.shown.size)
        assertFalse(capped.hasHidden)
    }

    // ── 2. 묶음 단위 펼치기 ─────────────────────────────────────────────────

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

    // ── 3. 순위 상한 (관계도 노드) ───────────────────────────────────────────
    //
    // **앞의 둘이 서로 반대 방향의 사고를 막는다** — ① 남길 것은 중요도로 고르고
    // ② 그린 순서는 입력 순서다. ①만 있으면 `sortedByDescending{}.take()`가 통과하는데,
    // 그러면 관계도의 **초기 배치가 노드 순서로 원을 그리므로 남은 노드의 좌표까지 바뀐다**
    // (상한에 걸린 화면과 안 걸린 화면의 배치 규칙이 달라진다).

    private data class Person(val id: Long, val links: Int)

    private fun cap(people: List<Person>, limit: Int) =
        DisplayCap.rankedCap(people, limit, scoreOf = { it.links }, tieBreakOf = { it.id })

    @Test
    fun `상한 아래에서는 입력 목록 그대로다`() {
        // 상한 아래의 크기 — 이 판이 상한 아래의 화면을 바꾸면 그것이 결함이다.
        // (표본의 관여 인물 수는 실측된 적이 없다. 158은 3-12 모델의 값이고 참값은 214 이하
        //  어디든 될 수 있어 **상한에 닿을 수도 있다** — 그 답은 실기기 확인 3-120 ㄱ이 낸다.)
        val people = (1..158).map { Person(it.toLong(), it % 4) }
        val capped = cap(people, DisplayCap.GRAPH_NODE_LIMIT)
        assertEquals(people, capped.shown)
        assertEquals(0, capped.hiddenCount)
        assertFalse(capped.hasHidden)
    }

    @Test
    fun `넘치면 연결이 많은 쪽이 남고 접은 수를 정확히 말한다`() {
        // 연결 수를 일부러 뒤섞어 둔다 — 앞에서 자르는 구현이면 큰 연결이 통째로 접힌다.
        val people = (1..10).map { Person(it.toLong(), it) }
        val capped = cap(people, limit = 3)
        assertEquals(listOf(8L, 9L, 10L), capped.shown.map { it.id })
        assertEquals(7, capped.hiddenCount)
        assertEquals(10, capped.totalCount)
    }

    @Test
    fun `남은 것의 순서는 고른 순서가 아니라 입력 순서다`() {
        // 연결 수 내림차순이면 30 → 20 → 10 순으로 나올 자리다. 입력 순서를 지켜야 한다.
        val people = listOf(Person(1L, 10), Person(2L, 30), Person(3L, 1), Person(4L, 20))
        assertEquals(listOf(1L, 2L, 4L), cap(people, limit = 3).shown.map { it.id })
    }

    @Test
    fun `연결 수가 같으면 id 순으로 갈라 같은 그림을 낸다`() {
        // 동점은 관계도에서 흔하다(연결 1개인 인물이 대부분이다). 갈라 두지 않으면
        // 같은 데이터가 화면을 열 때마다 다른 인물을 그린다.
        val people = listOf(Person(5L, 1), Person(2L, 1), Person(9L, 1), Person(7L, 1))
        assertEquals(listOf(5L, 2L), cap(people, limit = 2).shown.map { it.id })
    }

    @Test
    fun `상한이 0 이하이면 아무것도 그리지 않고 전량을 접은 것으로 센다`() {
        val people = (1..5).map { Person(it.toLong(), 1) }
        val capped = cap(people, limit = 0)
        assertTrue(capped.shown.isEmpty())
        assertEquals(5, capped.hiddenCount)
        assertEquals(5, capped.totalCount)
    }

    @Test
    fun `관계도 상한은 종전 전환 문턱과 같은 값이다`() {
        // 값이 달라지면 실사용 표본에서 화면이 바뀐다 — 이 판이 일부러 맞춰 둔 자리다.
        assertEquals(200, DisplayCap.GRAPH_NODE_LIMIT)
    }
}
