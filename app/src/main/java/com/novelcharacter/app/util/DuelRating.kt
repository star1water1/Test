package com.novelcharacter.app.util

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * **"이 축에서 누가 얼마나 센가"의 단일 소스** — 대결(B-104)의 점수 계층.
 *
 * 사용자 확정은 **ㄱ1(점수 방식)**이다. 그 안에서 어떤 점수인지는 착수 세션의 조사·검토가
 * 정한다(`docs/judgment_confirmations_2026-08.md` 6장). **Elo가 아니라 Bradley–Terry 최대사후(MAP)를
 * 쓴다.** 근거는 셋이며 전부 이 앱의 확정 요구에서 나온다:
 *
 * 1. **되돌리기가 일상 경로다.** 층 B ①(*"잘못 눌렀다 → 해당 판을 되돌린다"*)이 확정이라
 *    한 판 빼기가 예외가 아니다. Elo는 **경로 의존**이라 한 판만 빼려면 그 뒤의 모든 판을
 *    다시 계산해야 한다 — 결국 재적합이므로 Elo의 유일한 장점(O(1) 갱신)이 사라진다.
 *    BT는 **결과 집합의 함수**라 기록을 지우고 다시 적합하면 그것이 정확한 답이다.
 * 2. **캐릭터 추가·삭제 처분이 확정 요구다**(백로그 B-104 행 ①). Elo의 초기값 1500은
 *    근거 없는 상수이고, 삭제된 캐릭터가 남에게 이미 준 점수는 회수할 길이 없다.
 *    BT는 참가자 목록이 바뀌면 다시 적합하면 된다.
 * 3. **같은 데이터가 늘 같은 순위를 낸다.** Elo는 입력 순서가 바뀌면 점수가 달라져
 *    *"똑같이 눌렀는데 순위가 다르다"*가 생긴다. 그것은 개발 의도 2번(변수 제어)이 금지하는
 *    부류의 조용한 어긋남이다.
 *
 * **앵커 사전분포가 척도를 고정한다.** 각 캐릭터는 강함이 1.0으로 **고정된 가상 상대**와
 * [Options.priorMatches]판씩 이기고 진 것으로 친다. 이것이 두 가지를 한꺼번에 해결한다 —
 * ⓐ 무패(또는 전패) 캐릭터의 강함이 무한대로 발산하는 것을 막고(BT 최대가능도의 고전적 결함),
 * ⓑ **척도가 자동으로 고정돼** 별도 정규화가 필요 없다. 그래서 한 판도 안 한 캐릭터의 점수는
 * 언제나 정확히 [ANCHOR_SCORE]이고, 축이 달라도 점수의 뜻이 같다.
 *
 * **버리지 않고 세어서 알린다**(개발 의도 2번). 적합에서 빠지는 판은 세 부류이며 전부
 * [Fit]이 개수를 들고 나온다 — 참가자 목록에 없는 캐릭터의 판([Fit.orphanMatches] — 휴지통에
 * 들어간 캐릭터의 판이 여기다. **지우지 않으므로 복원하면 되살아난다**), 사용자가 상성으로
 * 확정해 점수 계층에서 뺀 판([Fit.excludedMatches]), 승자가 두 참가자 중 어느 쪽도 아닌
 * 깨진 판([Fit.malformedMatches]).
 *
 * **상성 판을 빼는 것이 점수를 더 정확하게 만든다.** B가 A를 늘 잡는데 그 판이 적합에 남아
 * 있으면 B의 강함이 부풀고 A의 강함이 깎여 **둘과 무관한 제3자의 순위까지 틀어진다.**
 * 확정 문서 2-1이 *"상성은 점수 위에 별도 층으로 얹혀야 한다"*고 적은 것의 계산적 이유가
 * 이것이다 — 두 층을 가르면 양쪽이 다 정확해진다.
 */
object DuelRating {

    /** 한 판도 치르지 않은 캐릭터의 표시 점수. 앵커 상대의 강함이 1.0이라 여기에 선다. */
    const val ANCHOR_SCORE = 1500

    /** 표시 점수의 배율 — 강함이 10배면 점수가 400 오른다(Elo와 같은 눈금이라 읽는 법이 익숙하다). */
    private const val SCALE = 400.0

    /** 자연로그 척도의 값을 표시 점수 눈금으로 옮기는 계수. */
    private const val LOG_SCALE = SCALE / 2.302585092994046

    /** 순서 없는 짝의 정규 키. 저장·비교는 언제나 이것을 지난다. */
    data class PairKey(val lowId: Long, val highId: Long) {
        companion object {
            fun of(a: Long, b: Long): PairKey = if (a <= b) PairKey(a, b) else PairKey(b, a)
        }
    }

    /**
     * 한 판.
     *
     * @property winnerId 이긴 쪽. **null이면 무승부다** — 데이터 모델은 처음부터 이것을 받는다.
     *   화면이 '비슷함' 버튼을 내주느냐는 설정이지만, 저장 형식은 나중에 바꾸기 어려운 축이라
     *   되돌릴 수 있는 쪽으로 세워 둔다(인수인계 ⑤의 관행). 무승부는 양쪽에 0.5승으로 든다.
     */
    data class Match(val aId: Long, val bId: Long, val winnerId: Long?)

    /**
     * 한 짝의 집계. **[fit]이 이것을 만들어 [Fit.tallies]로 내놓으므로 짝 고르기·상성 검출이
     * 기록을 다시 세지 않는다** — 같은 집계를 세 곳이 따로 하면 반드시 갈라진다(단일 소스 지도).
     *
     * @property lowWins [PairKey.lowId] 쪽의 승수(무승부 0.5).
     */
    data class PairTally(val pair: PairKey, val matches: Int, val lowWins: Double) {
        val highWins: Double get() = matches - lowWins

        /** [PairKey.lowId]의 실제 승률. 판이 없으면 0.5. */
        val lowWinRate: Double get() = if (matches <= 0) 0.5 else lowWins / matches

        /** 양쪽이 한 번씩은 이겼는가 — 흔들림 판정의 재료. */
        val split: Boolean get() = lowWins > 0.0 && lowWins < matches
    }

    /**
     * @property strength BT 강함 γ. 앵커가 1.0이다.
     * @property score 표시 점수. γ=1이면 [ANCHOR_SCORE].
     * @property stdError ln γ 척도의 표준오차. **판이 적을수록 크다** — 순위표가 이것으로
     *   *"아직 덜 정해졌다"*를 말할 수 있고, 짝 고르기가 이것으로 우선순위를 매긴다.
     * @property played 적합에 쓰인 판 수.
     * @property wins 승수(무승부는 0.5).
     */
    data class Rating(
        val id: Long,
        val strength: Double,
        val score: Int,
        val stdError: Double,
        val played: Int,
        val wins: Double
    ) {
        /** 표시 점수의 오차 폭(±). 순위표의 신뢰 구간이 이것을 쓴다. */
        val scoreError: Int get() = (stdError * LOG_SCALE).roundToInt()
    }

    data class Options(
        /**
         * 앵커 상대와 몇 판씩 치른 것으로 칠 것인가. 클수록 점수가 [ANCHOR_SCORE] 쪽으로 당겨진다.
         * 0으로 두면 무패 캐릭터의 강함이 발산하므로 **0보다 커야 한다**.
         */
        val priorMatches: Double = 0.5,
        /**
         * MM은 **거의 갈라진 데이터**(늘 센 쪽이 이겨 최대점이 사전분포에만 매인 축)에서 느리다 —
         * 실측으로 10명 완전 분리가 3,583회를 먹었다. 다만 그런 데이터는 **판이 적을 때만 생기므로**
         * (한 번의 이변이면 분리가 깨진다) 회당 값이 싸서 실제 시간은 밀리초 단위다.
         * 잘 이어진 현실 데이터는 900명·18,000판이 1,728회다. 상한은 그 둘을 다 덮는다.
         */
        val maxIterations: Int = 10_000,
        /**
         * 로그 척도의 걸음이 이보다 작으면 멈춘다. 표시 점수 1점 = β로 0.0058이라
         * 1e-9는 **화면이 쓰는 정밀도보다 여섯 자리 이상 촘촘하다** — 확신 계산에 쓰는
         * ln 척도까지 여유 있게 덮으려고 이만큼 잡았다.
         */
        val tolerance: Double = 1e-9
    )

    data class Fit(
        val ratings: List<Rating>,
        val iterations: Int,
        val converged: Boolean,
        val usedMatches: Int,
        val orphanMatches: Int,
        val excludedMatches: Int,
        val malformedMatches: Int,
        /** 적합에 들어간 짝의 집계. **상성으로 확정돼 빠진 짝은 여기 없다**([excludedPairKeys]에 있다). */
        val tallies: Map<PairKey, PairTally> = emptyMap(),
        val excludedPairKeys: Set<PairKey> = emptySet()
    ) {
        private val byId: Map<Long, Rating> = ratings.associateBy { it.id }

        fun of(id: Long): Rating? = byId[id]

        /** 모델이 보는 [aId]의 승률. 두 강함의 비다(BT의 정의 그대로). */
        fun winProbability(aId: Long, bId: Long): Double {
            val a = byId[aId] ?: return 0.5
            val b = byId[bId] ?: return 0.5
            val sum = a.strength + b.strength
            return if (sum <= 0.0) 0.5 else a.strength / sum
        }

        /**
         * **[aId]가 [bId]보다 세다고 얼마나 확신하는가.** [winProbability]와 다르다 —
         * 저쪽은 *"다음 판에서 이길 확률"*이고 이쪽은 *"순위가 정해졌는가"*다.
         * 한 판도 안 한 두 캐릭터는 승률이 0.5이면서 확신도 0.5이지만, 서로 멀리 떨어진 채
         * 판을 많이 치른 둘은 승률도 확신도 1에 가깝다. **접어두기(층 2)는 이 값으로 판정한다** —
         * 확정 문서가 적은 *"점수 차가 충분히 벌어진 짝"*을 점수 차 그대로 쓰면 **둘 다 불확실한
         * 캐릭터의 큰 점수 차**를 정해진 것으로 착각한다.
         */
        fun confidence(aId: Long, bId: Long): Double {
            val a = byId[aId] ?: return 0.5
            val b = byId[bId] ?: return 0.5
            val diff = ln(a.strength) - ln(b.strength)
            val se = sqrt(a.stdError * a.stdError + b.stdError * b.stdError)
            if (se <= 0.0) return if (diff > 0) 1.0 else if (diff < 0) 0.0 else 0.5
            return normalCdf(diff / se)
        }
    }

    /**
     * 기록에서 점수를 낸다. **순수 함수다** — 같은 입력이면 언제나 같은 출력이고,
     * 되돌리기는 [matches]에서 그 판을 빼고 다시 부르는 것으로 끝난다.
     *
     * @param participants 점수를 낼 캐릭터. 여기 없는 id가 든 판은 [Fit.orphanMatches]로 센다.
     * @param excludedPairs 사용자가 **상성으로 확정한** 짝(층 B ③). 점수 계층에서 뺀다.
     * @param initialStrengths **직전 적합의 강함을 넣으면 반복이 줄어든다.** 앵커가 척도를
     *   고정해 최대점이 유일하므로 **어디서 시작하든 같은 답으로 간다** — 실측에서 표시 점수가
     *   전원 일치했다(900명·18,000판, 찬 시작 대비 반복 1,728회 → 731회).
     *   **대결 화면이 매 판 뒤 순위를 새로 내는 경로가 이것이다.**
     *   단, 멈춤 기준이 '걸음 크기'라 가능도가 납작한 자리(전승 캐릭터)에서는 강함 자체가
     *   상대 1e-4쯤 다른 지점에서 멈출 수 있다 — 표시 한 점(강함의 0.58%)보다 훨씬 작다.
     */
    fun fit(
        participants: Collection<Long>,
        matches: List<Match>,
        excludedPairs: Set<PairKey> = emptySet(),
        options: Options = Options(),
        initialStrengths: Map<Long, Double> = emptyMap()
    ): Fit {
        val ids = participants.distinct().sorted()
        val n = ids.size
        if (n == 0) return Fit(emptyList(), 0, true, 0, matches.size, 0, 0, emptyMap(), excludedPairs)

        val index = HashMap<Long, Int>(n * 2)
        ids.forEachIndexed { i, id -> index[id] = i }

        var used = 0
        var orphan = 0
        var excluded = 0
        var malformed = 0
        // 짝별 집계 — [0] 판 수, [1] lowId의 승수(무승부 0.5).
        val pairs = HashMap<PairKey, DoubleArray>()
        for (m in matches) {
            val ia = index[m.aId]
            val ib = index[m.bId]
            if (ia == null || ib == null || ia == ib) { orphan++; continue }
            val key = PairKey.of(m.aId, m.bId)
            if (excludedPairs.contains(key)) { excluded++; continue }
            val lowWin = when (m.winnerId) {
                null -> 0.5
                key.lowId -> 1.0
                key.highId -> 0.0
                else -> { malformed++; continue }
            }
            used++
            val cell = pairs.getOrPut(key) { DoubleArray(2) }
            cell[0] += 1.0
            cell[1] += lowWin
        }

        val pairCount = pairs.size
        val pairLow = IntArray(pairCount)
        val pairHigh = IntArray(pairCount)
        val pairPlays = DoubleArray(pairCount)
        val pairLowWins = DoubleArray(pairCount)
        val wins = DoubleArray(n)
        val played = IntArray(n)
        var slot = 0
        for ((key, cell) in pairs) {
            val i = index.getValue(key.lowId)
            val j = index.getValue(key.highId)
            val count = cell[0]
            pairLow[slot] = i
            pairHigh[slot] = j
            pairPlays[slot] = count
            pairLowWins[slot] = cell[1]
            slot++
            wins[i] += cell[1]
            wins[j] += count - cell[1]
            played[i] += count.toInt()
            played[j] += count.toInt()
        }

        val prior = options.priorMatches.coerceAtLeast(0.0)
        // 로그 척도 β = ln γ에서 푼다. 가능도가 β에 대해 오목이라 최대점이 유일하고,
        // 표시 점수가 β의 1차식(1500 + 400·log₁₀ e·β)이라 멈춤 기준이 화면 단위와 맞는다.
        var beta = DoubleArray(n) { i ->
            val seed = initialStrengths[ids[i]]
            if (seed != null && seed.isFinite() && seed > 0.0) ln(seed) else 0.0
        }
        var iterations = 0
        var converged = false
        while (iterations < options.maxIterations) {
            iterations++
            val next = minorizeStep(beta, pairLow, pairHigh, pairPlays, wins, prior, n)
            var maxStep = 0.0
            for (i in 0 until n) {
                val step = abs(next[i] - beta[i])
                if (step > maxStep) maxStep = step
            }
            beta = next
            if (maxStep < options.tolerance) { converged = true; break }
        }

        val gamma = DoubleArray(n) { exp(beta[it]) }
        val information = DoubleArray(n)
        if (prior > 0.0) {
            for (i in 0 until n) {
                val p = sigmoid(beta[i])
                information[i] += 2.0 * prior * p * (1.0 - p)
            }
        }
        for (k in 0 until pairCount) {
            val i = pairLow[k]
            val j = pairHigh[k]
            val p = sigmoid(beta[i] - beta[j])
            val curve = pairPlays[k] * p * (1.0 - p)
            information[i] += curve
            information[j] += curve
        }

        val ratings = ArrayList<Rating>(n)
        for (i in 0 until n) {
            val se = if (information[i] > 0.0) 1.0 / sqrt(information[i]) else Double.POSITIVE_INFINITY
            ratings.add(
                Rating(
                    id = ids[i],
                    strength = gamma[i],
                    score = (ANCHOR_SCORE + SCALE * log10(gamma[i])).roundToInt(),
                    stdError = se,
                    played = played[i],
                    wins = wins[i]
                )
            )
        }
        val tallies = HashMap<PairKey, PairTally>(pairs.size * 2)
        for ((key, cell) in pairs) {
            tallies[key] = PairTally(key, cell[0].toInt(), cell[1])
        }
        return Fit(
            ratings, iterations, converged, used, orphan, excluded, malformed,
            tallies, excludedPairs
        )
    }

    private fun sigmoid(x: Double): Double =
        if (x >= 0.0) 1.0 / (1.0 + exp(-x)) else exp(x) / (1.0 + exp(x))

    /**
     * Hunter(2004)의 MM 갱신 한 걸음 — 로그 척도로 옮겨 적었다.
     *
     * **가능도를 절대 낮추지 않는다**는 것이 이 걸음의 성질이고, 그래서 손잡이(걸음 크기·감쇠)
     * 없이도 반드시 수렴한다. 대각 뉴턴을 얹어 보았으나 **되레 느렸다** — 이 모델의 헤시안이
     * 그래프 라플라시안 꼴이라(각 짝이 두 대각에 +, 비대각에 −를 같은 크기로 넣는다) 대각
     * 근사가 안정 경계에 걸려 잘 이어진 데이터에서 진동한다. 실측: 900명·18,000판에서 MM은
     * 1,728회에 멈췄고 뉴턴은 감쇠를 줘도 상한 500회를 다 쓰고 못 멈췄다.
     */
    private fun minorizeStep(
        beta: DoubleArray,
        pairLow: IntArray,
        pairHigh: IntArray,
        pairPlays: DoubleArray,
        wins: DoubleArray,
        prior: Double,
        n: Int
    ): DoubleArray {
        val gamma = DoubleArray(n) { exp(beta[it]) }
        val denominator = DoubleArray(n)
        if (prior > 0.0) {
            for (i in 0 until n) denominator[i] += 2.0 * prior / (gamma[i] + 1.0)
        }
        for (k in pairLow.indices) {
            val i = pairLow[k]
            val j = pairHigh[k]
            val sum = gamma[i] + gamma[j]
            if (sum <= 0.0) continue
            denominator[i] += pairPlays[k] / sum
            denominator[j] += pairPlays[k] / sum
        }
        return DoubleArray(n) { i ->
            if (denominator[i] > 0.0) ln((wins[i] + prior) / denominator[i]) else beta[i]
        }
    }

    /**
     * 표준정규 누적분포. Zelen–Severo 근사(A&S 26.2.17, 오차 7.5e-8)이며 **의존을 늘리지 않으려고
     * 직접 둔다** — 이 앱의 순수 계층은 표준 kotlinc로 실행 검증되므로 외부 통계 라이브러리를
     * 들이면 그 검증 경로가 막힌다.
     */
    private fun normalCdf(x: Double): Double {
        if (x < 0.0) return 1.0 - normalCdf(-x)
        val t = 1.0 / (1.0 + 0.2316419 * x)
        val poly = t * (0.319381530 +
            t * (-0.356563782 +
                t * (1.781477937 +
                    t * (-1.821255978 + t * 1.330274429))))
        val density = exp(-0.5 * x * x) / 2.5066282746310002
        return 1.0 - density * poly
    }
}
