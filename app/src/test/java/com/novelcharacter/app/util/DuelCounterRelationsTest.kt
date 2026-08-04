package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 상성 계층(B-104)의 하네스.
 *
 * **맨 위 테스트가 이 슬라이스의 갈음 근거 그 자체다** — 확정 문서 층 A(3-순환 감지)가
 * *원리적으로 볼 수 없는* 상성이 실재함을 코드로 세운다. 이것이 무너지면 갈음의 근거가
 * 사라지므로 확정된 순환 3층안으로 돌아가야 한다.
 */
class DuelCounterRelationsTest {

    private fun wins(winner: Long, loser: Long, times: Int): List<DuelRating.Match> =
        (1..times).map { DuelRating.Match(winner, loser, winner) }

    /** 1>2>3>4>5의 사슬. 이웃끼리만 붙었으므로 삼각형이 하나도 없다. */
    private fun chain(): List<DuelRating.Match> =
        wins(1L, 2L, 10) + wins(2L, 3L, 10) + wins(3L, 4L, 10) + wins(4L, 5L, 10)

    private val everyone = listOf(1L, 2L, 3L, 4L, 5L)

    @Test
    fun `순환이 없어도 천적을 찾는다 — 층 A가 원리적으로 못 보는 자리`() {
        // 5는 사슬의 맨 아래인데 1만은 잡는다. 1과 5를 잇는 경로는 길이 4라
        // **3-순환이 하나도 만들어지지 않는다** — 층 A는 여기서 아무것도 찾지 못한다.
        val matches = chain() + wins(5L, 1L, 6)
        val pass = DuelCounterRelations.analyzeTwoPass(everyone, matches)

        assertTrue("이 배치에는 삼각형이 없다", pass.report.cycles.isEmpty())
        assertEquals(1, pass.report.direct.size)

        val found = pass.report.direct.first()
        assertEquals(DuelCounterRelations.Kind.DIRECT, found.kind)
        assertEquals("센 쪽이 앞, 잡는 쪽이 뒤다", listOf(1L, 5L), found.members)
        assertEquals(6, found.samples)
        assertTrue(found.deviation >= 2.0)
        assertEquals(setOf(1L, 5L), pass.report.involvedIds)
        assertEquals(1, pass.report.count)
    }

    @Test
    fun `2단 적합이 천적을 더 또렷하게 만든다`() {
        val matches = chain() + wins(5L, 1L, 6)
        val single = DuelRating.fit(everyone, matches)
        val singlePass = DuelCounterRelations.analyze(single)
        val twoPass = DuelCounterRelations.analyzeTwoPass(everyone, matches)

        val before = singlePass.direct.firstOrNull()?.deviation ?: 0.0
        val after = twoPass.report.direct.first().deviation
        assertTrue(
            "상성 판이 점수에 남아 있으면 잔차가 스스로 지워진다: $before → $after",
            after > before
        )
        // 2차 적합의 점수는 상성에 오염되지 않은 것이라 순위표가 이것을 써야 한다.
        assertTrue(twoPass.fit.of(1L)!!.score > twoPass.fit.of(5L)!!.score)
    }

    @Test
    fun `순환도 그대로 찾는다 — 확정된 층 A는 유지된다`() {
        val matches = wins(1L, 2L, 5) + wins(2L, 3L, 5) + wins(3L, 1L, 5)
        val fit = DuelRating.fit(listOf(1L, 2L, 3L), matches)
        val report = DuelCounterRelations.analyze(fit)

        assertEquals(1, report.cycles.size)
        val cycle = report.cycles.first()
        assertEquals(DuelCounterRelations.Kind.CYCLE, cycle.kind)
        assertEquals("회전은 같은 순환이므로 가장 작은 id가 앞에 온다", listOf(1L, 2L, 3L), cycle.members)
        assertEquals(5, cycle.samples)
        assertEquals(0.5, cycle.deviation, 1e-9)
        assertEquals(setOf(1L, 2L, 3L), report.involvedIds)
    }

    @Test
    fun `같은 순환을 여러 번 세지 않는다`() {
        val matches = wins(1L, 2L, 3) + wins(2L, 3L, 3) + wins(3L, 1L, 3)
        val report = DuelCounterRelations.analyze(DuelRating.fit(listOf(1L, 2L, 3L), matches))
        assertEquals(1, report.cycles.size)
    }

    @Test
    fun `강자가 예상보다 더 이긴 것은 상성이 아니다`() {
        // 방향을 보지 않으면 "모델이 점수를 낮게 잡았다"와 "천적이다"가 섞인다.
        val matches = chain() + wins(1L, 5L, 6)
        val pass = DuelCounterRelations.analyzeTwoPass(everyone, matches)
        assertTrue(pass.report.direct.isEmpty())
        assertTrue(pass.report.cycles.isEmpty())
    }

    @Test
    fun `판이 모자라면 천적으로 부르지 않는다`() {
        val matches = chain() + wins(5L, 1L, 2)
        val strict = DuelCounterRelations.analyzeTwoPass(
            everyone, matches, options = DuelCounterRelations.Options(minSamples = 3)
        )
        assertTrue("두 판으로 천적을 선언하면 오조작이 상성이 된다", strict.report.direct.isEmpty())
    }

    @Test
    fun `흔들림은 모델이 기울었다고 본 짝이 갈릴 때 뜬다`() {
        val matches = chain() +
            listOf(
                DuelRating.Match(1L, 5L, 1L),
                DuelRating.Match(1L, 5L, 5L),
                DuelRating.Match(1L, 5L, 1L),
                DuelRating.Match(1L, 5L, 5L)
            )
        val report = DuelCounterRelations.analyze(DuelRating.fit(everyone, matches))
        val wobble = report.wobbles.firstOrNull { it.pair == DuelRating.PairKey.of(1L, 5L) }
        assertTrue("한쪽으로 기운다던 짝이 갈리면 축의 기준이 모호하다는 신호다", wobble != null)
        assertEquals(4, wobble!!.matches)
        assertTrue(report.wobbleRate > 0.0)
    }

    @Test
    fun `한 판씩만 치른 짝은 흔들림으로 세지 않는다`() {
        val report = DuelCounterRelations.analyze(DuelRating.fit(everyone, chain()))
        assertTrue(report.wobbles.isEmpty())
        assertEquals(4, report.comparedPairs)
        assertEquals(4, report.repeatedPairs)
    }

    @Test
    fun `반씩 갈린 짝은 방향이 없어 순환을 만들지 않는다`() {
        val matches = listOf(
            DuelRating.Match(1L, 2L, 1L), DuelRating.Match(1L, 2L, 2L),
            DuelRating.Match(2L, 3L, 2L), DuelRating.Match(2L, 3L, 3L),
            DuelRating.Match(3L, 1L, 3L), DuelRating.Match(3L, 1L, 1L)
        )
        val report = DuelCounterRelations.analyze(DuelRating.fit(listOf(1L, 2L, 3L), matches))
        assertTrue(report.cycles.isEmpty())
    }

    @Test
    fun `삼각형 훑기 상한을 넘기면 자른 사실을 알린다`() {
        val matches = (1L..12L).flatMap { a ->
            (a + 1..12L).map { b -> DuelRating.Match(a, b, if ((a + b) % 3 == 0L) b else a) }
        }
        val fit = DuelRating.fit((1L..12L).toList(), matches)
        val report = DuelCounterRelations.analyze(
            fit, options = DuelCounterRelations.Options(maxTriangleSteps = 5L)
        )
        assertTrue(report.triangleScanCapped)
        assertTrue(report.triangleSteps <= 6L)
    }

    @Test
    fun `확정된 상성은 두 적합 모두에서 빠진다`() {
        val matches = chain() + wins(5L, 1L, 6)
        val confirmed = setOf(DuelRating.PairKey.of(1L, 5L))
        val pass = DuelCounterRelations.analyzeTwoPass(everyone, matches, confirmedCounters = confirmed)
        assertEquals(6, pass.fit.excludedMatches)
        assertTrue("이미 확정된 것을 후보로 다시 올리면 사용자가 같은 것을 두 번 처분한다",
            pass.report.direct.isEmpty())
    }
}
