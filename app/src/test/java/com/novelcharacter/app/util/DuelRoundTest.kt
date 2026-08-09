package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * k지선다 한 화면이 **누구를 올리고 무엇을 남기는가**(B-115)를 지키는 하네스.
 *
 * **여기가 두꺼운 것은 화면이 얇기 때문이다** — 「세션 착수 규칙」 4번대로 이 저장소의 자동
 * 검증은 화면을 못 본다. 아래가 깨지면 화면에서 나는 증상은 각각
 * *"안 물어본 승부가 순위에 적힌다"* · *"1:1이 갑자기 다르게 기록된다"* ·
 * *"방금 고른 얼굴이 곧바로 또 뜬다"*이다.
 */
class DuelRoundTest {

    private fun candidate(a: Long, b: Long, reason: DuelPairing.Reason = DuelPairing.Reason.CLOSE) =
        DuelPairing.Candidate(
            pair = DuelRating.PairKey.of(a, b),
            priority = 1.0,
            winProbability = 0.5,
            confidence = 0.5,
            played = 0,
            reason = reason
        )

    private fun round(
        members: List<Long>,
        lead: DuelPairing.Candidate = candidate(members[0], members[1]),
        requestedSize: Int = members.size
    ) = DuelRound.Round(members = members, lead = lead, requestedSize = requestedSize)

    // ──────────────────────────────────────────────────────────────────────
    // 화면 짜기
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `둘이면 머리 짝 그대로다`() {
        val built = DuelRound.build(candidate(1, 2), listOf(candidate(3, 4)), 2)

        assertEquals(listOf(1L, 2L), built.members)
        assertEquals(DuelRating.PairKey.of(1, 2), built.lead.pair)
    }

    @Test
    fun `이어진 후보를 먼저 붙인다`() {
        // (3,4)는 머리와 안 이어지고 (2,5)는 2로 이어진다 — 뒤엣것이 먼저다.
        val built = DuelRound.build(
            candidate(1, 2),
            listOf(candidate(3, 4), candidate(2, 5)),
            3
        )

        assertEquals(listOf(1L, 2L, 5L), built.members)
    }

    @Test
    fun `이어진 것이 모자라면 남은 후보에서 채운다`() {
        val built = DuelRound.build(candidate(1, 2), listOf(candidate(7, 8)), 4)

        assertEquals(listOf(1L, 2L, 7L, 8L), built.members)
    }

    @Test
    fun `대기열이 마르면 그만큼만 올린다 — 오류가 아니라 축 끝물이다`() {
        val built = DuelRound.build(candidate(1, 2), emptyList(), 4)

        assertEquals(listOf(1L, 2L), built.members)
    }

    @Test
    fun `같은 참가자를 두 번 올리지 않는다`() {
        // 대기열이 머리와 겹치는 짝만 잔뜩 내는 상황 — 중복이 들어가면 카드 둘이 같은
        // 얼굴이 되고, 그것을 고르면 자기 자신을 이긴 판이 적힌다.
        val built = DuelRound.build(
            candidate(1, 2),
            listOf(candidate(1, 2), candidate(2, 1), candidate(1, 3)),
            4
        )

        assertEquals(listOf(1L, 2L, 3L), built.members)
        assertEquals(built.members.size, built.members.toSet().size)
    }

    /**
     * 머리 짝 자체가 같은 참가자를 가리키는 것은 정상 경로가 아니지만(짝 고르기가 `i<j`로만
     * 낸다), 들어오면 **카드 둘이 같은 얼굴**이 되고 그것을 고르면 자기 자신을 이긴 판이
     * 만들어진다 — 저장소가 거절하므로 그 누름은 화면이 멈춘 것처럼 보인다.
     */
    @Test
    fun `머리 짝이 같은 참가자여도 한 번만 오른다`() {
        val built = DuelRound.build(candidate(1, 1), listOf(candidate(2, 3)), 3)

        assertEquals(built.members.size, built.members.toSet().size)
        assertTrue(1L in built.members)
    }

    @Test
    fun `요청 수는 위아래로 잘린다`() {
        val queue = listOf(candidate(2, 3), candidate(3, 4), candidate(4, 5), candidate(5, 6))

        assertEquals(2, DuelRound.build(candidate(1, 2), queue, 0).size)
        assertEquals(DuelRound.MAX_SIZE, DuelRound.build(candidate(1, 2), queue, 99).size)
    }

    @Test
    fun `이유는 머리 후보의 것이다`() {
        val built = DuelRound.build(
            candidate(1, 2, DuelPairing.Reason.RECHECK),
            listOf(candidate(2, 3, DuelPairing.Reason.NEW)),
            3
        )

        assertEquals(DuelPairing.Reason.RECHECK, built.reason)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 무엇이 판으로 남는가
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `고르면 k 빼기 1판이다 — 진 것들끼리는 적지 않는다`() {
        val outcomes = DuelRound.outcomes(round(listOf(1L, 2L, 3L, 4L)), winnerId = 3L)

        assertEquals(3, outcomes.size)
        assertTrue(outcomes.all { it.winnerId == 3L && it.aId == 3L })
        assertEquals(listOf(1L, 2L, 4L), outcomes.map { it.bId })
        // 진 것들 사이의 짝은 **하나도** 없어야 한다 — 안 물었으므로 답이 없다.
        val losers = setOf(1L, 2L, 4L)
        assertTrue(outcomes.none { it.aId in losers && it.bId in losers })
    }

    @Test
    fun `비슷함은 전 조합이다 — 기준이 되는 하나가 없다`() {
        val outcomes = DuelRound.outcomes(round(listOf(1L, 2L, 3L)), winnerId = null)

        assertEquals(3, outcomes.size)
        assertTrue(outcomes.all { it.winnerId == null })
        assertEquals(
            setOf(
                DuelRating.PairKey.of(1, 2),
                DuelRating.PairKey.of(1, 3),
                DuelRating.PairKey.of(2, 3)
            ),
            outcomes.mapTo(HashSet()) { it.pair }
        )
    }

    /**
     * **이 슬라이스의 뼈대가 되는 동치다.**
     *
     * 1:1을 따로 두지 않고 `k=2`로 흡수했으므로, 그 값이 종전과 다르면 **k지선다를 켠 적
     * 없는 사용자의 기록이 이 판에서 달라진다.** 화면에서는 아무 표시도 나지 않고 순위표만
     * 조용히 어긋나는 부류라 시험이 유일한 검출이다.
     */
    @Test
    fun `k가 2면 종전 1대1과 글자 그대로 같다`() {
        val two = round(listOf(1L, 2L))

        val win = DuelRound.outcomes(two, winnerId = 1L)
        assertEquals(1, win.size)
        assertEquals(1L, win[0].aId)
        assertEquals(2L, win[0].bId)
        assertEquals(1L, win[0].winnerId)

        val draw = DuelRound.outcomes(two, winnerId = null)
        assertEquals(1, draw.size)
        assertEquals(null, draw[0].winnerId)
        assertEquals(DuelRating.PairKey.of(1, 2), draw[0].pair)
    }

    @Test
    fun `화면에 없는 것을 고르면 아무것도 안 적는다`() {
        assertEquals(emptyList<DuelRound.Outcome>(), DuelRound.outcomes(round(listOf(1L, 2L, 3L)), 9L))
    }

    @Test
    fun `참가자가 둘 미만이면 낼 판이 없다`() {
        val lone = DuelRound.Round(members = listOf(1L), lead = candidate(1, 2), requestedSize = 2)

        assertEquals(emptyList<DuelRound.Outcome>(), DuelRound.outcomes(lone, 1L))
        assertEquals(emptyList<DuelRound.Outcome>(), DuelRound.outcomes(lone, null))
    }

    // ──────────────────────────────────────────────────────────────────────
    // 떠 있는 판은 답하기 전에 바뀌지 않는다
    //
    // [DuelSession] 규칙 1을 화면 전체로 넓힌 것이다. 이것이 깨지면 배경에서 새 계획이
    // 도착할 때마다 셋째·넷째 카드가 조용히 갈려, **고르려던 얼굴이 손가락 아래에서 바뀐다.**
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `머리 짝이 같고 설정이 같으면 그대로 쓴다`() {
        val existing = round(listOf(1L, 2L, 3L), requestedSize = 3)

        assertTrue(DuelRound.canReuse(existing, candidate(1, 2), 3) { true })
    }

    @Test
    fun `머리 짝이 바뀌면 다시 짠다 — 답했거나 되돌렸다는 뜻이다`() {
        val existing = round(listOf(1L, 2L, 3L), requestedSize = 3)

        assertFalse(DuelRound.canReuse(existing, candidate(4, 5), 3) { true })
    }

    @Test
    fun `설정이 바뀌면 다시 짠다 — 사용자가 스스로 한 일이다`() {
        val existing = round(listOf(1L, 2L, 3L), requestedSize = 3)

        assertFalse(DuelRound.canReuse(existing, candidate(1, 2), 4) { true })
    }

    /**
     * 대기열이 말라 셋을 청했는데 둘만 올라온 판은 **올라온 수가 아니라 청한 수**로 가른다.
     * 올라온 수로 가르면 그 판이 매번 다시 짜여, 마른 축에서 카드가 계속 흔들린다.
     */
    @Test
    fun `덜 올라온 판도 설정이 그대로면 다시 짜지 않는다`() {
        val short = round(listOf(1L, 2L), requestedSize = 4)

        assertTrue(DuelRound.canReuse(short, candidate(1, 2), 4) { true })
    }

    @Test
    fun `못 그리게 된 참가자가 있으면 다시 짠다`() {
        val existing = round(listOf(1L, 2L, 3L), requestedSize = 3)

        assertFalse(DuelRound.canReuse(existing, candidate(1, 2), 3) { it != 3L })
    }

    @Test
    fun `떠 있는 판이 없으면 짠다`() {
        assertFalse(DuelRound.canReuse(null, candidate(1, 2), 2) { true })
    }

    // ──────────────────────────────────────────────────────────────────────
    // 대기열에서 걷어 낼 것
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `답한 짝이 곧 걷어 낼 짝이다`() {
        val shown = round(listOf(1L, 2L, 3L))
        val handled = DuelRound.handledPairs(shown, winnerId = 2L)

        assertEquals(
            setOf(DuelRating.PairKey.of(1, 2), DuelRating.PairKey.of(2, 3)),
            handled
        )
        // 안 물은 (1,3)은 걷어 내지 않는다 — 모델이 그것을 다시 낼 수 있어야 한다.
        assertTrue(DuelRating.PairKey.of(1, 3) !in handled)
    }

    @Test
    fun `비슷함은 전 조합을 걷어 낸다`() {
        val handled = DuelRound.handledPairs(round(listOf(1L, 2L, 3L)), winnerId = null)

        assertEquals(3, handled.size)
    }
}
