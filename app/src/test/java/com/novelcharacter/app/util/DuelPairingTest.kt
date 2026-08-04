package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 짝 고르기(B-104)의 하네스 — 확정 문서 2-2의 3층에 조사가 채워 넣은 세 자리를 지킨다.
 *
 * 특히 **이행 추론으로 접히는 것**(층 1의 순서를 정보량으로 정하면 저절로 따라오는 성질)이
 * 깨지면 "적게 눌러도 순위가 자리 잡는다"는 설계의 근거가 사라진다.
 */
class DuelPairingTest {

    private fun wins(winner: Long, loser: Long, times: Int): List<DuelRating.Match> =
        (1..times).map { DuelRating.Match(winner, loser, winner) }

    private fun chainFit(): DuelRating.Fit =
        DuelRating.fit(listOf(1L, 2L, 3L), wins(1L, 2L, 20) + wins(2L, 3L, 20))

    @Test
    fun `아무 판도 없으면 전 조합이 남아 있다`() {
        val fit = DuelRating.fit(listOf(1L, 2L, 3L, 4L), emptyList())
        val plan = DuelPairing.plan(fit)
        assertEquals(6L, plan.progress.total)
        assertEquals(0L, plan.progress.played)
        assertEquals(0L, plan.progress.folded)
        assertEquals(6L, plan.progress.remaining)
        assertTrue(plan.progress.exact)
        assertEquals(6, plan.queue.size)
        assertTrue(plan.queue.all { it.reason == DuelPairing.Reason.NEW })
    }

    @Test
    fun `이행 추론으로 접힌다 — 안 붙인 짝이 대기열에서 빠지고 접힘으로 센다`() {
        val plan = DuelPairing.plan(chainFit())
        val far = DuelRating.PairKey.of(1L, 3L)

        assertTrue(
            "1>2>3이 굳었으면 1대3은 물을 필요가 없다",
            plan.queue.none { it.pair == far }
        )
        assertEquals(1L, plan.progress.folded)
        assertEquals(2L, plan.progress.played)
        assertEquals(0L, plan.progress.remaining)
        assertEquals(100.0, plan.progress.settledPercent, 1e-9)
    }

    @Test
    fun `접는 문턱을 올리면 접힌 짝이 대기열로 돌아온다 — 버린 것이 아니다`() {
        val fit = chainFit()
        val loose = DuelPairing.plan(fit, options = DuelPairing.Options(foldConfidence = 0.99))
        val strict = DuelPairing.plan(fit, options = DuelPairing.Options(foldConfidence = 0.999999))
        assertEquals(1L, loose.progress.folded)
        assertEquals(0L, strict.progress.folded)
        assertTrue(strict.queue.any { it.pair == DuelRating.PairKey.of(1L, 3L) })
    }

    @Test
    fun `진행률은 붙인 것 접힌 것 남은 것으로 정확히 갈린다`() {
        val fit = DuelRating.fit(
            (1L..8L).toList(),
            wins(1L, 2L, 12) + wins(2L, 3L, 12) + wins(4L, 5L, 3)
        )
        val plan = DuelPairing.plan(fit)
        val p = plan.progress
        assertEquals(28L, p.total)
        assertEquals(p.total, p.played + p.folded + p.remaining)
        assertEquals(3L, p.played)
    }

    @Test
    fun `모델이 헷갈려 하는 짝이 대기열 앞에 온다`() {
        // 1·2는 서로 대등하고, 3은 둘에게 확실히 진다.
        val matches = wins(1L, 3L, 10) + wins(2L, 3L, 10) +
            listOf(DuelRating.Match(1L, 2L, 1L), DuelRating.Match(1L, 2L, 2L))
        val fit = DuelRating.fit(listOf(1L, 2L, 3L), matches)
        val plan = DuelPairing.plan(fit)
        assertTrue(plan.queue.isNotEmpty())
        assertEquals(
            "대등한 1대2가 이미 결판난 짝보다 앞이어야 한다",
            DuelRating.PairKey.of(1L, 2L),
            plan.queue.first().pair
        )
    }

    @Test
    fun `상성으로 확정한 짝은 대기열에 나오지 않는다`() {
        val fit = DuelRating.fit(
            listOf(1L, 2L, 3L),
            wins(1L, 3L, 4) + wins(2L, 3L, 4) + wins(2L, 1L, 4),
            excludedPairs = setOf(DuelRating.PairKey.of(1L, 2L))
        )
        val plan = DuelPairing.plan(fit)
        assertTrue(plan.queue.none { it.pair == DuelRating.PairKey.of(1L, 2L) })
        // 확정된 상성도 '붙인 짝'으로 세어야 진행률이 뒷걸음치지 않는다.
        assertEquals(3L, plan.progress.played)
    }

    @Test
    fun `상성 후보에 얽힌 캐릭터는 접힌 짝에서도 다시 꺼내진다`() {
        val fit = chainFit()
        val plain = DuelPairing.plan(fit)
        val targeted = DuelPairing.plan(fit, recheckTargets = setOf(1L, 3L))

        assertTrue(plain.queue.none { it.reason == DuelPairing.Reason.RECHECK })
        val rechecked = targeted.queue.filter { it.reason == DuelPairing.Reason.RECHECK }
        assertEquals(1, rechecked.size)
        assertEquals(DuelRating.PairKey.of(1L, 3L), rechecked.first().pair)
    }

    @Test
    fun `대기열에서 다시 꺼내기가 차지하는 몫에 상한이 있다`() {
        val fit = DuelRating.fit((1L..12L).toList(), (1L..11L).flatMap { wins(it, it + 1, 25) })
        val plan = DuelPairing.plan(
            fit,
            recheckTargets = (1L..12L).toSet(),
            options = DuelPairing.Options(queueLimit = 20, recheckShare = 0.25)
        )
        val rechecks = plan.queue.count { it.reason == DuelPairing.Reason.RECHECK }
        assertTrue("다시 꺼내기가 대기열을 잠식하면 전수 지향이 멈춘다", rechecks <= 5)
        assertTrue(plan.queue.size <= 20)
    }

    @Test
    fun `짝 전수 상한을 넘기면 자른 사실을 알린다`() {
        val fit = DuelRating.fit((1L..40L).toList(), emptyList())
        val plan = DuelPairing.plan(fit, options = DuelPairing.Options(maxPairScan = 100L))
        assertTrue(plan.scanCapped)
        assertFalse("조용히 자르면 '다 훑었다'로 읽힌다", plan.progress.exact)
        assertEquals(780L, plan.progress.total)
    }

    @Test
    fun `같은 씨앗이면 같은 대기열이 나온다`() {
        val fit = DuelRating.fit((1L..30L).toList(), (1L..29L).flatMap { wins(it, it + 1, 6) })
        val a = DuelPairing.plan(fit, setOf(3L, 9L), DuelPairing.Options(seed = 42L))
        val b = DuelPairing.plan(fit, setOf(3L, 9L), DuelPairing.Options(seed = 42L))
        assertEquals(a.queue.map { it.pair }, b.queue.map { it.pair })
    }

    @Test
    fun `캐릭터가 하나뿐이면 붙일 것이 없다`() {
        val plan = DuelPairing.plan(DuelRating.fit(listOf(1L), emptyList()))
        assertTrue(plan.queue.isEmpty())
        assertEquals(0L, plan.progress.total)
        assertEquals(0.0, plan.progress.settledPercent, 1e-9)
    }
}
