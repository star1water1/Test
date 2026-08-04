package com.novelcharacter.app.util

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **"무엇이 상성인가"의 단일 소스** — 대결(B-104)의 상성 계층.
 *
 * 사용자 확정은 확정 문서 2-1의 **순환 3층안**이다(층 A 순환 감지 · 층 B 사용자 처분 ·
 * 층 C 필드 대조). 그 안은 사용자가 *"p1좋다"*로 채택했고 **바닥이지 천장이 아니다** —
 * 6장이 조사·검토로 갈음해도 좋다고 적었고, **상성 표현**을 조사 축 셋 중 하나로 지목했다.
 *
 * ## 조사가 찾은 것 — 층 A는 가장 흔한 상성을 못 본다
 *
 * 확정 문서의 층 A는 상성을 **`A>B, B>C, C>A` 순환**으로 정의한다. 그런데 창작에서 훨씬 흔한
 * 모양은 **순환이 아니다**: *"A가 전체적으로는 훨씬 세지만, B에게만은 늘 진다"* — 이른바
 * 천적이다. 여기에는 **순환이 없으므로 층 A가 원리적으로 아무것도 찾지 못한다.**
 * 사용자가 *"나름대로 강한놈이 이긴다도 있겠지만 상성도 있을 거 아니야?"*라고 말한 그 상성의
 * 1차적 모양이 정확히 이것이다.
 *
 * → **갈음: 상성 = 점수 모델의 예측과 실제 결과의 어긋남(잔차).**
 * 3-순환은 그 어긋남이 삼각형 위에 나타난 **특수한 경우**이지 정의가 아니다.
 * 둘 다 검출하고([Kind]), 처분은 확정된 층 B가 그대로 받는다 — **갈음하는 것은 층 A의
 * 정의뿐이고 3층 구조와 사용자 처분은 확정 그대로다.**
 *
 * ## 왜 이것이 ㄴ1(전수 지향)을 정당화하는가
 *
 * 잔차는 **두 캐릭터가 각각 다른 상대와도 붙어 있어야** 드러난다. A와 B가 서로하고만 붙었다면
 * 점수가 그 전적에 그대로 맞춰지므로 잔차가 0이 되어 천적이 보이지 않는다. 즉 **상성을 캐내려면
 * 넓게 붙여야 한다** — 사용자가 고른 "최대한 붙이되"(ㄴ1)가 상성 검출의 전제 조건이었다는 뜻이고,
 * 짝 고르기가 접어둔 것을 다시 꺼내는 층 3이 필요한 이유이기도 하다.
 *
 * ## 2단 적합 — 의심스러운 짝을 빼고 다시 재는 것
 *
 * 한 번만 적합하면 **천적 관계 자체가 점수를 오염시켜 잔차를 스스로 지운다**(B가 A를 이긴
 * 판들이 B의 점수를 올려 버려 "예상대로였다"가 된다). 그래서 [analyzeTwoPass]는
 * ① 전부로 적합해 후보를 찾고 ② **그 후보를 뺀 채 다시 적합해** 깨끗한 점수를 얻은 뒤
 * ③ 같은 전적을 그 점수에 대고 다시 잰다. 적합이 한 번 더 돌 뿐이라 값이 싸다.
 *
 * **이것이 확정 문서 2-1의 *"상성은 점수 위에 별도 층으로 얹혀야 한다"*를 계산으로 옮긴 것이다** —
 * 두 층을 가르면 점수는 이행적인 부분만 갖고, 상성은 나머지를 갖는다. **양쪽이 다 정확해진다.**
 */
object DuelCounterRelations {

    enum class Kind {
        /** 천적 — 모델이 약하다고 본 쪽이 예상보다 유의하게 많이 이긴다. 순환이 없다. */
        DIRECT,

        /** 순환 — `A>B>C>A`. 확정 문서 층 A가 정의한 모양. */
        CYCLE
    }

    /**
     * @property members [Kind.DIRECT]이면 `[센 쪽, 잡는 쪽]`, [Kind.CYCLE]이면 이기는 차례대로 셋.
     * @property deviation 어긋남의 크기. DIRECT는 표준화 잔차 |z|, CYCLE은 **가장 약한 변의
     *   승률 여유**(순환은 제일 약한 고리만큼만 믿을 수 있다).
     * @property samples 근거가 된 판 수. CYCLE은 세 변 중 **가장 적은** 쪽이다.
     */
    data class Candidate(
        val kind: Kind,
        val members: List<Long>,
        val deviation: Double,
        val samples: Int
    )

    /**
     * 흔들림 — 같은 짝을 여러 번 붙였는데 결과가 갈렸고, **모델은 갈릴 리 없다고 본** 자리.
     * 확정 문서의 '덧'이 말한 *"이 축은 기준이 모호할 수 있다"*의 신호다.
     */
    data class Wobble(
        val pair: DuelRating.PairKey,
        val matches: Int,
        val lowWinRate: Double,
        val predicted: Double
    )

    data class Report(
        val direct: List<Candidate>,
        val cycles: List<Candidate>,
        val wobbles: List<Wobble>,
        val comparedPairs: Int,
        val repeatedPairs: Int,
        val triangleSteps: Long,
        val triangleScanCapped: Boolean
    ) {
        /** 순위표 배지의 수 — P-10이 확정한 *"이 축에 상성 N건"*. */
        val count: Int get() = direct.size + cycles.size

        /** 상성 후보에 얽힌 캐릭터. [DuelPairing.plan]의 `recheckTargets`가 이것을 받는다. */
        val involvedIds: Set<Long>
            get() = LinkedHashSet<Long>().apply {
                direct.forEach { addAll(it.members) }
                cycles.forEach { addAll(it.members) }
            }

        /** 여러 번 붙인 짝 중 흔들린 비율 — 축 자체의 품질 신호라 축 단위로 읽는다. */
        val wobbleRate: Double get() = if (repeatedPairs <= 0) 0.0 else wobbles.size.toDouble() / repeatedPairs
    }

    data class Options(
        /** 천적으로 부르려면 이만큼은 붙여 봐야 한다. */
        val minSamples: Int = 3,
        /** 표준화 잔차가 이 이상이면 후보. 2.0은 대략 95% 수준이다. */
        val zThreshold: Double = 2.0,
        /** 순환으로 부르려면 세 변이 각각 이만큼은 붙어 있어야 한다. */
        val cycleMinSamples: Int = 1,
        /** 모델이 이보다 한쪽으로 기울었는데 결과가 갈리면 흔들림으로 센다. */
        val wobbleThreshold: Double = 0.25,
        /** 삼각형 훑기의 상한. 넘으면 [Report.triangleScanCapped]로 **자른 사실을 알린다**. */
        val maxTriangleSteps: Long = 5_000_000L
    )

    data class TwoPass(val fit: DuelRating.Fit, val report: Report)

    /**
     * 점수와 전적으로 상성 후보를 찾는다. **순수 함수다.**
     *
     * @param tallies 검사할 짝의 전적. 기본은 [fit]에 들어간 것이지만, [analyzeTwoPass]는
     *   **적합에서 뺀 짝까지** 넣어 부른다(뺐다고 검사에서까지 빠지면 후보가 사라진다).
     */
    fun analyze(
        fit: DuelRating.Fit,
        tallies: Collection<DuelRating.PairTally> = fit.tallies.values,
        options: Options = Options()
    ): Report {
        val direct = ArrayList<Candidate>()
        val wobbles = ArrayList<Wobble>()
        var repeated = 0

        for (tally in tallies) {
            if (tally.matches <= 0) continue
            val low = tally.pair.lowId
            val high = tally.pair.highId
            val probability = fit.winProbability(low, high)

            if (tally.matches >= 2) {
                repeated++
                if (tally.split && min(probability, 1.0 - probability) < options.wobbleThreshold) {
                    wobbles.add(Wobble(tally.pair, tally.matches, tally.lowWinRate, probability))
                }
            }

            if (tally.matches < options.minSamples) continue
            val variance = tally.matches * probability * (1.0 - probability)
            if (variance <= 1e-12) continue
            val z = (tally.lowWins - tally.matches * probability) / sqrt(variance)
            if (abs(z) < options.zThreshold) continue

            // 상성은 **약자가 예상보다 많이 이기는 것**이다. 강자가 더 이긴 것은 그냥 점수가
            // 낮게 잡혔다는 뜻이지 천적이 아니다 — 방향을 보지 않으면 이 둘이 섞인다.
            val members = when {
                z > 0.0 && probability < 0.5 -> listOf(high, low)
                z < 0.0 && probability > 0.5 -> listOf(low, high)
                else -> continue
            }
            direct.add(Candidate(Kind.DIRECT, members, abs(z), tally.matches))
        }

        val cycleScan = findCycles(tallies, options)
        direct.sortWith(compareByDescending<Candidate> { it.deviation }.thenBy { it.members[0] })

        return Report(
            direct = direct,
            cycles = cycleScan.cycles,
            wobbles = wobbles,
            comparedPairs = tallies.count { it.matches > 0 },
            repeatedPairs = repeated,
            triangleSteps = cycleScan.steps,
            triangleScanCapped = cycleScan.capped
        )
    }

    /**
     * 2단 적합으로 상성을 찾는다 — **이것이 권장 진입점이다**(클래스 설명의 '2단 적합' 참조).
     *
     * @param confirmedCounters 사용자가 층 B ③으로 **이미 상성이라 확정한** 짝. 두 적합 모두에서
     *   빠지고, 그래서 순위표의 점수가 그 관계에 오염되지 않는다.
     * @return 돌려주는 [TwoPass.fit]은 **2차 적합**이다 — 순위표가 써야 할 깨끗한 점수다.
     */
    fun analyzeTwoPass(
        participants: Collection<Long>,
        matches: List<DuelRating.Match>,
        confirmedCounters: Set<DuelRating.PairKey> = emptySet(),
        ratingOptions: DuelRating.Options = DuelRating.Options(),
        options: Options = Options()
    ): TwoPass {
        val first = DuelRating.fit(participants, matches, confirmedCounters, ratingOptions)
        val allTallies = first.tallies.values.toList()
        val firstPass = analyze(first, allTallies, options)
        if (firstPass.direct.isEmpty()) return TwoPass(first, firstPass)

        val suspects = LinkedHashSet(confirmedCounters)
        firstPass.direct.forEach { suspects.add(DuelRating.PairKey.of(it.members[0], it.members[1])) }
        val second = DuelRating.fit(participants, matches, suspects, ratingOptions)
        return TwoPass(second, analyze(second, allTallies, options))
    }

    private class CycleScan(val cycles: List<Candidate>, val steps: Long, val capped: Boolean)

    /**
     * 다수결 그래프의 3-순환(확정 문서 층 A). 반씩 갈린 짝은 방향이 없으므로 변을 만들지 않는다 —
     * 그런 짝은 순환이 아니라 흔들림·대등이며 [Wobble]이 따로 든다.
     */
    private fun findCycles(
        tallies: Collection<DuelRating.PairTally>,
        options: Options
    ): CycleScan {
        val out = HashMap<Long, ArrayList<Long>>()
        val edges = HashSet<Pair<Long, Long>>()
        val edgeInfo = HashMap<DuelRating.PairKey, DuelRating.PairTally>()

        for (tally in tallies) {
            if (tally.matches < options.cycleMinSamples || tally.matches <= 0) continue
            val rate = tally.lowWinRate
            if (rate == 0.5) continue
            val from = if (rate > 0.5) tally.pair.lowId else tally.pair.highId
            val to = if (rate > 0.5) tally.pair.highId else tally.pair.lowId
            out.getOrPut(from) { ArrayList() }.add(to)
            edges.add(from to to)
            edgeInfo[tally.pair] = tally
        }

        val seen = HashSet<List<Long>>()
        val cycles = ArrayList<Candidate>()
        var steps = 0L
        var capped = false
        outer@ for ((a, firstHops) in out) {
            for (b in firstHops) {
                for (c in out[b].orEmpty()) {
                    if (++steps > options.maxTriangleSteps) { capped = true; break@outer }
                    if (c == a) continue
                    if (!edges.contains(c to a)) continue

                    val rotation = canonical(a, b, c)
                    if (!seen.add(rotation)) continue

                    val legs = listOf(
                        edgeInfo[DuelRating.PairKey.of(a, b)],
                        edgeInfo[DuelRating.PairKey.of(b, c)],
                        edgeInfo[DuelRating.PairKey.of(c, a)]
                    )
                    if (legs.any { it == null }) continue
                    val margin = legs.minOf { abs(it!!.lowWinRate - 0.5) }
                    val samples = legs.minOf { it!!.matches }
                    cycles.add(Candidate(Kind.CYCLE, rotation, margin, samples))
                }
            }
        }
        cycles.sortWith(compareByDescending<Candidate> { it.deviation }.thenBy { it.members[0] })
        return CycleScan(cycles, steps, capped)
    }

    /** 순환은 회전해도 같은 것이다 — 가장 작은 id가 앞에 오게 돌려 한 번만 센다. */
    private fun canonical(a: Long, b: Long, c: Long): List<Long> = when {
        a <= b && a <= c -> listOf(a, b, c)
        b <= a && b <= c -> listOf(b, c, a)
        else -> listOf(c, a, b)
    }
}
