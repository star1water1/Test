package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 점수 계층(B-104)의 하네스.
 *
 * **이 파일이 지키는 것은 "Elo가 아니라 Bradley–Terry를 쓴 이유" 그 자체다** —
 * 순서 무관 · 정확한 되돌리기 · 발산 차단 · 상성 판을 뺐을 때 제3자 순위가 돌아오는 것.
 * 이 넷이 깨지면 조사·검토가 내린 결론이 무너진 것이므로 설계 문서부터 다시 봐야 한다.
 */
class DuelRatingTest {

    private fun match(a: Long, b: Long, winner: Long?) = DuelRating.Match(a, b, winner)

    private fun wins(winner: Long, loser: Long, times: Int): List<DuelRating.Match> =
        (1..times).map { match(winner, loser, winner) }

    /**
     * 숨은 실력 + 잡음으로 만든 현실적인 전적. **완전 분리(늘 센 쪽이 이김)를 피하려고 쓴다** —
     * 분리된 데이터는 최대점이 사전분포에만 매여 있어 MM이 상한까지 기어가는 특수한 부류다
     * (그 부류는 아래 `분리된 데이터` 테스트가 따로 다룬다).
     */
    private fun noisyLeague(ids: List<Long>, matchCount: Int, seed: Long): List<DuelRating.Match> {
        val random = kotlin.random.Random(seed)
        val skill = ids.associateWith { random.nextDouble() * 4.0 }
        return (1..matchCount).map {
            val a = ids[random.nextInt(ids.size)]
            var b = ids[random.nextInt(ids.size)]
            while (b == a) b = ids[random.nextInt(ids.size)]
            val pa = 1.0 / (1.0 + kotlin.math.exp(-(skill.getValue(a) - skill.getValue(b))))
            match(a, b, if (random.nextDouble() < pa) a else b)
        }
    }

    @Test
    fun `한 판도 없으면 앵커 점수에 선다`() {
        val fit = DuelRating.fit(listOf(1L, 2L, 3L), emptyList())
        fit.ratings.forEach {
            assertEquals(DuelRating.ANCHOR_SCORE, it.score)
            assertEquals(1.0, it.strength, 1e-9)
            assertEquals(0, it.played)
        }
        assertTrue(fit.converged)
    }

    @Test
    fun `판 순서를 바꿔도 점수가 같다`() {
        val matches = wins(1L, 2L, 3) + wins(2L, 3L, 2) + wins(3L, 1L, 1) + wins(1L, 3L, 2)
        val forward = DuelRating.fit(listOf(1L, 2L, 3L), matches)
        val backward = DuelRating.fit(listOf(1L, 2L, 3L), matches.reversed())
        val shuffled = DuelRating.fit(listOf(1L, 2L, 3L), matches.sortedBy { it.bId })

        // 최대점은 결과 **집합**의 함수라 순서와 무관하다. 멈추는 지점만 경로에 따라
        // 미세하게 달라지므로 점수는 정확히, 강함은 상대 오차로 견준다.
        for (id in listOf(1L, 2L, 3L)) {
            assertEquals(forward.of(id)!!.score, backward.of(id)!!.score)
            assertEquals(forward.of(id)!!.score, shuffled.of(id)!!.score)
            assertEquals(1.0, backward.of(id)!!.strength / forward.of(id)!!.strength, 1e-6)
            assertEquals(1.0, shuffled.of(id)!!.strength / forward.of(id)!!.strength, 1e-6)
        }
    }

    @Test
    fun `한 판을 되돌리면 그 판이 없던 상태와 정확히 같다`() {
        val base = wins(1L, 2L, 3) + wins(2L, 3L, 2)
        val extra = match(3L, 1L, 3L)

        val withExtra = DuelRating.fit(listOf(1L, 2L, 3L), base + extra)
        val undone = DuelRating.fit(listOf(1L, 2L, 3L), (base + extra) - extra)
        val never = DuelRating.fit(listOf(1L, 2L, 3L), base)

        assertNotEquals(withExtra.of(1L)!!.score, never.of(1L)!!.score)
        for (id in listOf(1L, 2L, 3L)) {
            assertEquals(never.of(id)!!.strength, undone.of(id)!!.strength, 1e-12)
        }
    }

    @Test
    fun `전승이어도 점수가 발산하지 않는다`() {
        val fit = DuelRating.fit(listOf(1L, 2L), wins(1L, 2L, 50))
        val top = fit.of(1L)!!
        assertTrue(top.strength.isFinite())
        assertTrue(top.score > DuelRating.ANCHOR_SCORE)
        assertTrue("전승이 무한대가 되면 순위표가 숫자를 못 쓴다", top.score < 5000)
        assertTrue(fit.of(2L)!!.score < DuelRating.ANCHOR_SCORE)
    }

    @Test
    fun `이긴 순서대로 점수가 선다`() {
        val matches = wins(1L, 2L, 5) + wins(2L, 3L, 5) + wins(3L, 4L, 5)
        val fit = DuelRating.fit(listOf(1L, 2L, 3L, 4L), matches)
        val scores = listOf(1L, 2L, 3L, 4L).map { fit.of(it)!!.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `무승부는 양쪽에 반 승씩 든다`() {
        val fit = DuelRating.fit(listOf(1L, 2L), listOf(match(1L, 2L, null), match(1L, 2L, null)))
        assertEquals(fit.of(1L)!!.strength, fit.of(2L)!!.strength, 1e-9)
        assertEquals(1.0, fit.of(1L)!!.wins, 1e-9)
        assertEquals(2, fit.of(1L)!!.played)
    }

    @Test
    fun `참가자에 없는 캐릭터의 판은 세어서 알리고 점수에는 안 든다`() {
        val matches = wins(1L, 2L, 3) + wins(1L, 99L, 4)
        val fit = DuelRating.fit(listOf(1L, 2L), matches)
        assertEquals(4, fit.orphanMatches)
        assertEquals(3, fit.usedMatches)
        // 휴지통에 든 캐릭터의 판은 지우지 않는다 — 복원하면 되살아나야 하므로 개수만 든다.
        val restored = DuelRating.fit(listOf(1L, 2L, 99L), matches)
        assertEquals(0, restored.orphanMatches)
        assertEquals(7, restored.usedMatches)
    }

    @Test
    fun `승자가 두 참가자 중 어느 쪽도 아니면 깨진 판으로 센다`() {
        val fit = DuelRating.fit(listOf(1L, 2L), listOf(match(1L, 2L, 7L)))
        assertEquals(1, fit.malformedMatches)
        assertEquals(0, fit.usedMatches)
    }

    @Test
    fun `상성으로 확정한 짝을 빼면 제3자의 순위가 돌아온다`() {
        // 1이 3·4·5를 이겨 가장 세고, 2는 3·4·5에 져 가장 약하다.
        // 그런데 2는 1만은 늘 잡는다 — 그 판이 점수에 남으면 2가 부풀고 1이 깎여
        // **둘과 무관한 3의 상대 순위까지 흔들린다.**
        val backbone = wins(1L, 3L, 6) + wins(1L, 4L, 6) + wins(1L, 5L, 6) +
            wins(3L, 2L, 6) + wins(4L, 2L, 6) + wins(5L, 2L, 6)
        val counter = wins(2L, 1L, 6)

        val polluted = DuelRating.fit(listOf(1L, 2L, 3L, 4L, 5L), backbone + counter)
        val split = DuelRating.fit(
            listOf(1L, 2L, 3L, 4L, 5L),
            backbone + counter,
            excludedPairs = setOf(DuelRating.PairKey.of(1L, 2L))
        )

        assertEquals(6, split.excludedMatches)
        // 빼고 나면 1이 2보다 확실히 세고, 그 격차가 오염된 적합보다 크다.
        val pollutedGap = polluted.of(1L)!!.score - polluted.of(2L)!!.score
        val splitGap = split.of(1L)!!.score - split.of(2L)!!.score
        assertTrue("상성 판이 남으면 격차가 좁혀진다: $pollutedGap vs $splitGap", splitGap > pollutedGap)
        assertTrue(split.of(1L)!!.score > split.of(3L)!!.score)
        assertTrue(split.of(3L)!!.score > split.of(2L)!!.score)
    }

    @Test
    fun `확신은 점수 차만이 아니라 판 수를 함께 본다`() {
        val thin = DuelRating.fit(listOf(1L, 2L), wins(1L, 2L, 1))
        val thick = DuelRating.fit(listOf(1L, 2L), wins(1L, 2L, 12))
        assertTrue(thin.confidence(1L, 2L) > 0.5)
        assertTrue(
            "판이 쌓이면 같은 방향이라도 확신이 커져야 한다",
            thick.confidence(1L, 2L) > thin.confidence(1L, 2L)
        )
        assertEquals(1.0, thick.confidence(1L, 2L) + thick.confidence(2L, 1L), 1e-9)
    }

    @Test
    fun `표준오차는 판이 쌓일수록 줄어든다`() {
        val thin = DuelRating.fit(listOf(1L, 2L), wins(1L, 2L, 2))
        val thick = DuelRating.fit(listOf(1L, 2L), wins(1L, 2L, 20))
        assertTrue(thick.of(1L)!!.stdError < thin.of(1L)!!.stdError)
        assertTrue(thick.of(1L)!!.scoreError < thin.of(1L)!!.scoreError)
    }

    @Test
    fun `짝 집계를 적합이 내놓아 다시 세지 않아도 된다`() {
        val matches = wins(1L, 2L, 3) + listOf(match(2L, 1L, 1L), match(1L, 2L, null))
        val fit = DuelRating.fit(listOf(1L, 2L), matches)
        val tally = fit.tallies.getValue(DuelRating.PairKey.of(1L, 2L))
        assertEquals(5, tally.matches)
        assertEquals(4.5, tally.lowWins, 1e-9)
        assertEquals(0.5, tally.highWins, 1e-9)
        assertTrue(tally.split)
    }

    @Test
    fun `따뜻한 시작은 결과를 바꾸지 않고 반복만 줄인다`() {
        val ids = (1L..20L).toList()
        val base = noisyLeague(ids, matchCount = 400, seed = 11L)
        val cold = DuelRating.fit(ids, base)

        // 실제 경로는 **이미 아는 것을 확인하는 한 판**이다 — 대기열이 정보량 순이라
        // 순위를 통째로 뒤집는 판은 드물게 온다.
        val plusOne = base + match(1L, 2L, 1L)
        val warm = DuelRating.fit(
            ids, plusOne,
            initialStrengths = cold.ratings.associate { it.id to it.strength }
        )
        val coldAgain = DuelRating.fit(ids, plusOne)

        // 앵커가 척도를 고정해 최대점이 유일하므로 **어디서 시작하든 같은 답으로 간다.**
        // 다만 멈춤 기준은 '걸음 크기'라, 가능도가 납작한 자리(전승 캐릭터)에서는 강함이
        // 상대 1e-4쯤 다른 지점에서 멈출 수 있다 — **표시 한 점보다 훨씬 작아** 화면은 같다.
        for (id in ids) {
            assertEquals(coldAgain.of(id)!!.score, warm.of(id)!!.score)
            val reference = coldAgain.of(id)!!.strength
            assertEquals(1.0, warm.of(id)!!.strength / reference, 1e-3)
        }
        assertEquals(
            coldAgain.ratings.sortedByDescending { it.strength }.map { it.id },
            warm.ratings.sortedByDescending { it.strength }.map { it.id }
        )
        assertTrue(
            "따뜻한 시작이 더 오래 돌면 쓸 이유가 없다: ${warm.iterations} vs ${coldAgain.iterations}",
            warm.iterations < coldAgain.iterations
        )
    }

    @Test
    fun `완전히 갈라진 데이터도 수렴하고 점수가 유한하다`() {
        // 위 등수가 아래를 늘 이기면 최대가능도가 무한대 쪽으로 달아나고 **사전분포만이 그것을
        // 막는다.** MM이 가장 느린 부류이며(이 10명 데이터가 3,583회를 먹는다) 기본 상한
        // 10,000회는 그것을 덮으라고 잡은 값이다 — **여기서 수렴이 꺼지면 상한이 모자란 것이다.**
        val ids = (1L..10L).toList()
        val separated = ids.flatMap { a -> (a + 1..10L).flatMap { b -> wins(a, b, 3) } }
        val fit = DuelRating.fit(ids, separated)

        assertTrue("갈라진 데이터에서도 수렴해야 점수가 데이터만의 함수가 된다", fit.converged)
        assertTrue(fit.iterations <= DuelRating.Options().maxIterations)
        ids.forEach { assertTrue(fit.of(it)!!.strength.isFinite()) }
        assertEquals(ids, fit.ratings.sortedByDescending { it.strength }.map { it.id })
    }

    @Test
    fun `적합이 수렴한다`() {
        val matches = (1L..12L).flatMap { a ->
            (a + 1..12L).map { b -> match(a, b, if (a % 3 == 0L) b else a) }
        }
        val fit = DuelRating.fit((1L..12L).toList(), matches)
        // 전승 캐릭터가 있어 거의 갈라진 데이터다 — MM이 가장 느린 부류이며, 상한 안에서
        // 끝나는지가 기본값(2,000회)이 넉넉한지의 실측이다.
        assertTrue("수렴하지 않으면 순위가 실행마다 흔들린다", fit.converged)
        assertTrue(fit.iterations <= DuelRating.Options().maxIterations)
    }
}
