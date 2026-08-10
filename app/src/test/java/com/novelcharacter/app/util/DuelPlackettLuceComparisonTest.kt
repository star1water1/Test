package com.novelcharacter.app.util

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **k지선다의 적합을 Plackett–Luce로 바꾸면 이득이 있는가 — 재 봤다** (B-176).
 *
 * ## 왜 이 파일이 있는가
 *
 * 지금 한 화면(k명 중 1등 고르기)은 **승자 대 각 패자의 `k−1`판**으로 쪼개져
 * [DuelRating]의 Bradley–Terry에 들어간다([DuelRound.outcomes]). *"k명 중 1등"*의 원리
 * 모형은 **Plackett–Luce**이고, 쪼개기는 **한 번의 선택을 독립 관측 `k−1`개로 세므로
 * 승자의 증거를 과다 계상한다** — 판당 정보량이 정직해지는 것이 PL의 값어치다.
 *
 * B-115(k지선다) 구현이 *"착수 시 적합도 함께 볼 것, **단 대각 뉴턴의 전례대로 실측이
 * 먼저다 — 이득이 작으면 안 바꾼다**"*라 적어 두고 **그 실측을 하지 않았다.** 그래서
 * *"안 쟀다"*가 백로그 한 행으로 남았다(B-176). **지어낸 근거로 *"이득이 작을 것"*이라
 * 적는 것은 새 기능 체크리스트 6번이 금지한 그 자리**라, 재는 것 말고는 닫을 길이 없었다.
 *
 * 전례는 설계 1장 **'최적화기 — 실측이 한 번 뒤집었다'**이다: 대각 뉴턴이 더 좋아 보였고
 * 재 보니 아니었다. *"재지 않았으면 반대로 갔다"*가 그 절의 결론이다.
 *
 * ## 무엇을 재는가
 *
 * 참값 강함을 **알고 있는** 참가자들에게 k지선다를 돌려(승자는 참값 PL 확률로 뽑는다)
 * 두 적합을 견준다 — **순위 상관**(Spearman)과 **로그 강함의 오차**(RMSE).
 * 난수는 고정 씨앗의 LCG다 — 같은 입력이면 언제나 같은 출력이라야 이 시험이 판정이 된다.
 *
 * ## 결과 — **안 바꾼다**
 *
 * 아래 [두 적합의 차이가 저장 형식을 바꿀 만큼 크지 않다]가 실제 수치를 들고 있다.
 * 요지는 이렇다: **PL이 살짝 낫지만 그 차이가 순위에서 사실상 사라진다.** 화면이 쓰는 것은
 * 순위와 표시 점수이고, 과다 계상은 강함을 **한쪽으로 밀지 않고 폭만 부풀리므로**
 * (승자·패자 양쪽에 같은 배수로 증거가 늘어난다) 순서가 잘 보존된다.
 *
 * **그러므로 기본값은 '안 바꾼다'이고, 이 파일은 그 판정의 근거다.** 뒤집을 자료가 생기면
 * (예: k를 크게 키우는 화면이 생겨 과다 계상이 커지면) 여기 숫자를 다시 내고 판단한다 —
 * **저장 형식이 그대로라 이 전환은 언제든 뒤에 올 수 있다**(쌓인 판을 다시 적합하면 되고
 * 마이그레이션이 없다). 그것이 이 행을 급하지 않게 만드는 사실이다.
 */
class DuelPlackettLuceComparisonTest {

    // ── 합성 데이터 ────────────────────────────────────────────────────────────

    /** 한 화면 = 후보 [members] 중 [winnerId]를 골랐다. */
    private data class Choice(val members: List<Long>, val winnerId: Long)

    /**
     * 고정 씨앗 LCG — `Math.random()`을 쓰면 시험이 돌 때마다 답이 달라져 판정이 못 된다.
     * 계수는 Numerical Recipes의 것이다.
     */
    private class Lcg(private var state: Long) {
        fun nextDouble(): Double {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return state.toDouble() / 4294967296.0
        }
        fun nextInt(bound: Int): Int = (nextDouble() * bound).toInt().coerceIn(0, bound - 1)
    }

    private val participantCount = 40
    private val ids: List<Long> = (1L..participantCount.toLong()).toList()

    /** 참값 강함 — 로그 척도로 고르게 편 다음 γ로 옮긴다(폭 약 2.4 nat). */
    private val trueBeta: Map<Long, Double> =
        ids.withIndex().associate { (i, id) -> id to (i - participantCount / 2.0) * 0.12 }

    /** 참값 확률대로 한 화면의 승자를 뽑는다 — 이것이 PL의 정의 그대로다. */
    private fun drawWinner(members: List<Long>, rng: Lcg): Long {
        val weights = members.map { Math.exp(trueBeta.getValue(it)) }
        val total = weights.sum()
        var t = rng.nextDouble() * total
        for ((i, w) in weights.withIndex()) {
            t -= w
            if (t <= 0.0) return members[i]
        }
        return members.last()
    }

    private fun generate(rounds: Int, k: Int, seed: Long): List<Choice> {
        val rng = Lcg(seed)
        val out = ArrayList<Choice>(rounds)
        repeat(rounds) {
            val members = LinkedHashSet<Long>()
            while (members.size < k) members.add(ids[rng.nextInt(ids.size)])
            val list = members.toList()
            out.add(Choice(list, drawWinner(list, rng)))
        }
        return out
    }

    // ── 적합 둘 ────────────────────────────────────────────────────────────────

    /** 현행 — 한 화면을 **승자 대 각 패자 `k−1`판**으로 쪼개 BT에 넣는다([DuelRound.outcomes]와 같은 규칙). */
    private fun fitBySplitting(choices: List<Choice>): Map<Long, Double> {
        val matches = choices.flatMap { c ->
            c.members.filter { it != c.winnerId }
                .map { DuelRating.Match(c.winnerId, it, c.winnerId) }
        }
        return DuelRating.fit(ids, matches).ratings.associate { it.id to ln(it.strength) }
    }

    /**
     * Plackett–Luce의 MM 갱신 (Hunter 2004) — 한 화면을 **관측 하나**로 센다.
     *
     * `γ_i ← W_i / Σ_{S∋i} 1/(Σ_{j∈S} γ_j)`. 앵커 사전분포는 [DuelRating]과 **글자 그대로
     * 같게** 둔다 — 각자 앵커(γ=1 고정)와 `prior`승 `prior`패, 즉 크기 2의 선택 집합
     * `{i, 앵커}`가 `2·prior`번 있는 것으로 친다. 그래야 두 적합이 *모형*에서만 다르고
     * 사전분포·척도에서는 다르지 않아 **비교가 성립한다.**
     */
    private fun fitByPlackettLuce(
        choices: List<Choice>,
        prior: Double = 0.5,
        maxIterations: Int = 10_000,
        tolerance: Double = 1e-9
    ): Map<Long, Double> {
        val index = ids.withIndex().associate { (i, id) -> id to i }
        val n = ids.size
        val wins = DoubleArray(n)
        for (c in choices) wins[index.getValue(c.winnerId)] += 1.0
        for (i in 0 until n) wins[i] += prior

        var gamma = DoubleArray(n) { 1.0 }
        var iterations = 0
        while (iterations < maxIterations) {
            iterations++
            val denom = DoubleArray(n)
            for (c in choices) {
                var sum = 0.0
                for (m in c.members) sum += gamma[index.getValue(m)]
                val inv = 1.0 / sum
                for (m in c.members) denom[index.getValue(m)] += inv
            }
            for (i in 0 until n) denom[i] += 2.0 * prior / (gamma[i] + 1.0)

            var maxStep = 0.0
            val next = DoubleArray(n)
            for (i in 0 until n) {
                next[i] = if (denom[i] > 0.0) wins[i] / denom[i] else gamma[i]
                val step = abs(ln(next[i]) - ln(gamma[i]))
                if (step > maxStep) maxStep = step
            }
            gamma = next
            if (maxStep < tolerance) break
        }
        return ids.withIndex().associate { (i, id) -> id to ln(gamma[i]) }
    }

    // ── 견주는 자 ──────────────────────────────────────────────────────────────

    /** 참값과의 순위 상관(Spearman) — 화면이 실제로 쓰는 것이 순위다. */
    private fun spearman(fit: Map<Long, Double>): Double {
        val fitRank = rankOf(ids.map { fit.getValue(it) })
        val trueRank = rankOf(ids.map { trueBeta.getValue(it) })
        return pearson(fitRank, trueRank)
    }

    /** 참값과의 로그 강함 오차 — **평균을 맞춘 뒤** 잰다(척도의 상수 차이는 순위와 무관하다). */
    private fun rmse(fit: Map<Long, Double>): Double {
        val f = ids.map { fit.getValue(it) }
        val t = ids.map { trueBeta.getValue(it) }
        val shift = f.average() - t.average()
        return sqrt(f.indices.sumOf { val d = f[it] - shift - t[it]; d * d } / f.size)
    }

    private fun rankOf(xs: List<Double>): List<Double> {
        val order = xs.indices.sortedBy { xs[it] }
        val ranks = MutableList(xs.size) { 0.0 }
        order.forEachIndexed { rank, idx -> ranks[idx] = rank.toDouble() }
        return ranks
    }

    private fun pearson(a: List<Double>, b: List<Double>): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) {
            val x = a[i] - ma; val y = b[i] - mb
            num += x * y; da += x * x; db += y * y
        }
        return if (da <= 0.0 || db <= 0.0) 0.0 else num / sqrt(da * db)
    }

    // ── ① 자기 검증 — PL 구현이 맞는가 ─────────────────────────────────────────

    /**
     * **크기 2의 선택 집합에서 PL은 BT와 같은 모형이다** — 그러므로 같은 답을 내야 한다.
     *
     * **이 시험이 없으면 아래 비교가 아무것도 증명하지 못한다.** PL 쪽이 틀리게 짜여 있어도
     * *"PL이 더 낫지 않다"*는 결론은 그대로 나오기 때문이다 — 즉 **원하는 답이 버그로도
     * 나온다.** 재는 도구가 자기를 먼저 증명해야 한다는 것이 이 저장소의 관행이다
     * (검사 스크립트들의 '탐지기 자기 시험'과 같은 자리).
     */
    @Test
    fun `두 참가자짜리 선택에서 PL은 BT와 같은 답을 낸다`() {
        val choices = generate(rounds = 600, k = 2, seed = 20260810L)
        val bt = fitBySplitting(choices)
        val pl = fitByPlackettLuce(choices)

        for (id in ids) {
            assertEquals(
                "id=$id — k=2면 두 모형이 같은 가능도라 답도 같아야 한다",
                bt.getValue(id), pl.getValue(id), 1e-6
            )
        }
    }

    /** 재는 자 자신도 시험한다 — 참값을 그대로 넣으면 상관 1, 오차 0이어야 한다. */
    @Test
    fun `견주는 자가 참값을 완벽하다고 판정한다`() {
        assertEquals(1.0, spearman(trueBeta), 1e-12)
        assertEquals(0.0, rmse(trueBeta), 1e-12)
    }

    // ── ② 본 측정 ──────────────────────────────────────────────────────────────

    /**
     * **실측 — k=3·k=4에서 두 적합을 견준다.**
     *
     * 실행 시점의 값(고정 씨앗이라 재현된다):
     *
     * | 자료 | 쪼개기(BT) 상관 / RMSE | PL 상관 / RMSE |
     * |---|---|---|
     * | k=3 · 2,000화면 | 아래 `println`이 낸다 | |
     * | k=4 · 2,000화면 | | |
     *
     * **수치를 주석에 박아 적지 않는 것은 일부러다** — 박으면 씨앗이나 규모를 조금만 바꿔도
     * 그 자리가 낡는다(이 저장소가 반복해 겪은 모양). 판정에 쓰는 것은 아래 단언이고,
     * 사람이 볼 수치는 시험이 실행될 때 출력한다.
     *
     * **단언의 형태가 결론이다:** *"PL이 쪼개기보다 **의미 있게** 낫지 않다"*.
     * 여유(margin)는 화면이 쓰는 단위로 잡는다 — 순위 상관은 0.01, 로그 오차는 0.05 nat이며
     * 후자는 표시 점수로 **9점 남짓**이다(400/ln10 ≈ 174점/nat). 순위표가 그만큼도 안 움직이면
     * 되돌리기 어려운 축을 건드릴 이유가 없다.
     */
    @Test
    fun `두 적합의 차이가 저장 형식을 바꿀 만큼 크지 않다`() {
        for (k in listOf(3, 4)) {
            val choices = generate(rounds = 2_000, k = k, seed = 20260810L + k)
            val bt = fitBySplitting(choices)
            val pl = fitByPlackettLuce(choices)

            val btRho = spearman(bt); val btErr = rmse(bt)
            val plRho = spearman(pl); val plErr = rmse(pl)
            // 출력만 ASCII다 — 이 줄은 **콘솔에서 사람이 읽는 것**이고, 콘솔 인코딩이
            // UTF-8이 아니면 한글이 깨져 수치를 못 읽는다(로컬에서 실제로 그랬다).
            println(
                "B-176 k=%d n=2000 | split rho=%.5f rmse=%.4f | PL rho=%.5f rmse=%.4f"
                    .format(k, btRho, btErr, plRho, plErr)
            )

            // ⓐ 두 적합 다 쓸 만해야 비교가 뜻이 있다 — 한쪽이 망가져 있으면 '차이 없음'은 거짓이다.
            assertTrue("k=$k 쪼개기 상관 $btRho", btRho > 0.95)
            assertTrue("k=$k PL 상관 $plRho", plRho > 0.95)

            // ⓑ 결론 — PL의 이득이 화면이 볼 만한 크기가 아니다.
            assertTrue(
                "k=$k — PL의 순위 상관 이득이 0.01을 넘었다(ρ: $btRho → $plRho). " +
                    "넘었다면 이 판정('안 바꾼다')을 다시 볼 것 — B-176 행과 설계 14장.",
                plRho - btRho < 0.01
            )
            assertTrue(
                "k=$k — PL의 로그 오차 이득이 0.05 nat(표시 9점)을 넘었다(RMSE: $btErr → $plErr). " +
                    "넘었다면 이 판정('안 바꾼다')을 다시 볼 것 — B-176 행과 설계 14장.",
                btErr - plErr < 0.05
            )
        }
    }

    /**
     * **쪼개기가 무엇을 부풀리는지는 그대로 보인다** — 이득이 작다는 것이 *같다*는 뜻은 아니다.
     *
     * 한 화면을 `k−1`판으로 세므로 쪼개기 쪽 강함의 **폭(표준편차)이 더 크다.** 그것이
     * 과다 계상의 모양이고, 그런데도 순위가 보존되는 것이 위 판정의 이유다. 이 사실을
     * 시험으로 박아 두는 이유는 *"차이가 없다"*로 잘못 기억되는 것을 막기 위해서다 —
     * 차이는 있고, **화면이 쓰는 축에서 작을 뿐이다.**
     */
    @Test
    fun `쪼개기는 강함의 폭을 부풀린다 — 순위가 아니라 폭이다`() {
        val choices = generate(rounds = 2_000, k = 4, seed = 20260814L)
        val bt = fitBySplitting(choices)
        val pl = fitByPlackettLuce(choices)

        val btSpread = spread(bt)
        val plSpread = spread(pl)
        val trueSpread = spread(trueBeta)
        println(
            "B-176 spread | split sd=%.4f | PL sd=%.4f | true sd=%.4f"
                .format(btSpread, plSpread, trueSpread)
        )
        assertTrue("쪼개기 sd=$btSpread · PL sd=$plSpread", btSpread > plSpread)
        // 부푼 쪽이 참값에서 **더 멀다** — 과다 계상의 방향까지 맞다는 확인이다.
        // (이것이 아니면 '폭이 다르다'는 그저 두 수가 다르다는 말일 뿐이다.)
        assertTrue(
            "PL sd=$plSpread 가 쪼개기 sd=$btSpread 보다 참값 sd=$trueSpread 에 가까워야 한다",
            abs(plSpread - trueSpread) < abs(btSpread - trueSpread)
        )
    }

    private fun spread(fit: Map<Long, Double>): Double {
        val xs = ids.map { fit.getValue(it) }
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
    }
}
