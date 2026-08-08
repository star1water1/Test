package com.novelcharacter.app.util

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * **"다음에 무엇을 붙일까"의 단일 소스** — 대결(B-104)의 짝 고르기 계층.
 *
 * 사용자 확정은 **ㄴ1+ㄴ3**이고, 확정 문서 2-2가 그것을 3층으로 풀어 두었다
 * (전수 지향 → 접어두기 → 표본 재확인). **3층은 그대로 두고 세 자리를 채운다** — 확정 문서
 * 6장이 조사 축으로 지목한 *"어떤 짝을 먼저 내야 순위가 가장 빨리 확정되는가"*가 셋 다
 * 비어 있던 자리다.
 *
 * **① 층 1의 순서 = 정보량.** 확정 문서는 층 1을 *"목표는 전 조합"*이라고만 적었고 **순서를
 * 말하지 않았다.** BT 모델에서 한 판이 주는 피셔 정보량은 `p(1-p)`이고 **예측이 반반일 때
 * 최대**다. 그래서 대기열은 **모델이 가장 헷갈려 하는 짝**부터 낸다. 이것이 사용자가 말한
 * *"적게 눌러도 순위가 빨리 자리 잡는 것"*의 정확한 형태다.
 *
 * **② 이행 추론은 따로 만들지 않는다 — ①이 이미 한다.** A>B와 B>C가 굳으면 모델은 A>C를
 * 자동으로 높은 확률로 예측하고, 그러면 `p(1-p)`가 0에 가까워져 그 짝은 **대기열 바닥으로
 * 가라앉는다.** 별도의 추이 계산기를 두면 같은 판단이 두 곳에 생겨 갈라진다.
 * 그 덕에 **전수(N²/2)를 목표로 두고도 실제로 묻는 판은 그보다 훨씬 적다** — 순위만 놓고
 * 보면 정렬의 하한인 n·log₂n 쪽에 가깝게 내려간다([Progress.folded]가 그 몫을 센다).
 *
 * **③ 층 2의 기준 = 점수 차가 아니라 확신.** 확정 문서는 *"점수 차가 충분히 벌어진 짝"*이라
 * 적었는데, **점수 차를 그대로 쓰면 둘 다 아직 불확실한 캐릭터의 큰 점수 차를 '정해졌다'로
 * 착각한다.** [DuelRating.Fit.confidence]는 점수 차를 그 오차로 나눈 값이라 **판이 적으면
 * 저절로 더 많은 증거를 요구한다.** 손잡이가 [Options.foldConfidence] 하나로 줄고 뜻이 분명해진다
 * (*"99% 확신하면 접는다"*).
 *
 * **④ 층 3의 표적 = 무작위가 아니라 의심되는 곳.** 44,850짝에서 무작위로 몇 개 꺼내
 * 다시 붙이는 것으로 상성을 만날 확률은 사실상 0이다. 그래서 접어둔 것 중 **ⓐ 상성 후보에
 * 얽힌 캐릭터가 낀 짝**과 **ⓑ 이미 붙였는데 모델의 예상을 뒤집은 적이 있는 짝**을 앞세운다
 * ([recheckTargets]). 사용자가 우려한 *"상성 때문에 뒤집힐 짝을 영영 안 붙여 보는 것"*을
 * 무작위보다 훨씬 촘촘하게 막는다.
 *
 * **접는 것이지 버리는 것이 아니다**(확정 문서 2-2 층 2). 접힌 짝은 [Progress.folded]로 세어
 * 보이고, [Options.foldConfidence]를 낮추면 그대로 대기열에 돌아온다.
 */
object DuelPairing {

    /** 이 짝을 왜 냈는가 — 화면이 사용자에게 설명할 수 있어야 한다(원칙 02). */
    enum class Reason {
        /** 한쪽이라도 아직 한 판도 안 했다. */
        NEW,

        /** 모델이 반반으로 본다 — 정보량이 가장 큰 부류. */
        CLOSE,

        /** 승부는 어느 정도 예측되지만 아직 확신에 못 미친다. */
        UNCERTAIN,

        /** 접어둔 것을 다시 꺼냈다(층 3). */
        RECHECK
    }

    /**
     * @property winProbability [DuelRating.PairKey.lowId]가 이길 확률(모델 예측).
     * @property confidence 둘 중 센 쪽이 더 세다는 확신(0.5~1.0). [Options.foldConfidence]와 견준다.
     */
    data class Candidate(
        val pair: DuelRating.PairKey,
        val priority: Double,
        val winProbability: Double,
        val confidence: Double,
        val played: Int,
        val reason: Reason
    )

    /**
     * 전수 지향의 진행률(층 1).
     *
     * @property played 직접 붙인 짝. 화면의 *"1,234 / 44,850판"*이 이것과 [total]이다.
     * @property folded 안 붙였는데 **이행 추론으로 접힌** 짝. 이 수가 이 설계의 값어치를
     *   눈에 보이게 한다 — 안 눌러도 정해진 몫이다.
     * @property exact 짝 전수를 실제로 훑었는가. [Options.maxPairScan]을 넘으면 false이고
     *   [folded]·[remaining]은 추정이 아니라 **훑은 범위의 값**이다(조용히 자르지 않는다).
     */
    data class Progress(
        val total: Long,
        val played: Long,
        val folded: Long,
        val remaining: Long,
        val exact: Boolean
    ) {
        /** 직접 붙인 비율(%). */
        val playedPercent: Double get() = if (total <= 0L) 0.0 else played * 100.0 / total

        /** 붙였거나 접힌 비율(%) — 사용자가 체감하는 '정해진 정도'다. */
        val settledPercent: Double get() = if (total <= 0L) 0.0 else (played + folded) * 100.0 / total
    }

    data class Plan(
        val queue: List<Candidate>,
        val progress: Progress,
        val scannedPairs: Long,
        val scanCapped: Boolean
    )

    data class Options(
        /** 이 확신을 넘으면 접는다(층 2). */
        val foldConfidence: Double = 0.99,
        val queueLimit: Int = 40,
        /** 대기열에서 층 3(다시 꺼내기)이 차지하는 몫. */
        val recheckShare: Double = 0.15,
        /**
         * 짝 전수를 훑는 상한. 목표 규모(캐릭터 수백)에서는 늘 이 안이다 —
         * 900명이면 404,550짝으로 한 자릿수 밀리초다. 넘으면 순위 이웃 + 표본만 훑고
         * [Plan.scanCapped]로 **자른 사실을 알린다**.
         */
        val maxPairScan: Long = 2_000_000L,
        /** 상한을 넘겼을 때 훑을 순위 이웃 폭. */
        val rankWindow: Int = 24,
        /** 상한을 넘겼을 때 함께 훑을 무작위 표본 수. */
        val sampleTail: Int = 4_000,
        val seed: Long = 0L
    )

    /** 표준오차는 판이 없으면 무한대가 될 수 있다 — 우선순위 계산에서만 이 값으로 자른다. */
    private const val ERROR_CAP = 4.0

    /** `p(1-p)`가 이 이상이면 '반반'으로 부른다(p가 대략 0.28~0.72). */
    private const val CLOSE_INFORMATION = 0.2

    /**
     * @param recheckTargets 층 3이 우선해 다시 꺼낼 캐릭터 — 상성 후보에 얽힌 쪽
     *   ([DuelCounterRelations.Report.involvedIds]를 그대로 넣으면 된다).
     * @param eligibleIds **짝이 될 수 있는** 참가자 (B-168 후보 필터). null이면 전원이다.
     *   적합([fit])은 전적이 있는 비후보까지 담고 있어야 점수가 흔들리지 않지만(설계 9-3),
     *   **새로 붙일 짝은 후보끼리만** 낸다 — 그것이 필터의 뜻이다. 진행률·접힘 수도 후보끼리의
     *   조합으로 센다: 비후보가 낀 짝은 이 대기열이 영영 내지 않을 것이라, 분모에 넣으면
     *   진행률이 끝에 못 닿는 거짓 척도가 된다.
     */
    fun plan(
        fit: DuelRating.Fit,
        recheckTargets: Set<Long> = emptySet(),
        options: Options = Options(),
        eligibleIds: Set<Long>? = null
    ): Plan {
        val ranked = fit.ratings
            .let { ratings -> if (eligibleIds == null) ratings else ratings.filter { it.id in eligibleIds } }
            .sortedWith(
                compareByDescending<DuelRating.Rating> { it.strength }.thenBy { it.id }
            )
        val n = ranked.size
        val total = if (n < 2) 0L else n.toLong() * (n - 1L) / 2L
        if (n < 2) {
            return Plan(emptyList(), Progress(0L, 0L, 0L, 0L, true), 0L, false)
        }

        val capped = total > options.maxPairScan
        val active = ArrayList<Candidate>()
        val recheck = ArrayList<Pair<Double, Candidate>>()
        var playedPairs = 0L
        var foldedPairs = 0L
        var scanned = 0L
        val random = Random(options.seed)

        val visit = fun(i: Int, j: Int) {
            scanned++
            val a = ranked[i]
            val b = ranked[j]
            val key = DuelRating.PairKey.of(a.id, b.id)
            val excluded = fit.excludedPairKeys.contains(key)
            val tally = fit.tallies[key]
            val played = tally?.matches ?: 0
            if (played > 0 || excluded) playedPairs++

            // 상성으로 확정된 짝은 순위를 정하는 데 쓰지 않는다 — 그 짝의 몫은 상성 계층이 든다.
            if (excluded) return

            val confidence = fit.confidence(a.id, b.id)
            val settled = max(confidence, 1.0 - confidence) >= options.foldConfidence
            if (settled && played == 0) foldedPairs++

            val lowIsA = key.lowId == a.id
            val probability = if (lowIsA) {
                fit.winProbability(a.id, b.id)
            } else {
                fit.winProbability(b.id, a.id)
            }
            val information = probability * (1.0 - probability)
            val uncertainty = min(a.stdError, ERROR_CAP) + min(b.stdError, ERROR_CAP)
            val priority = information * (1.0 + uncertainty) / (1.0 + played)

            if (!settled) {
                val reason = when {
                    played == 0 && (a.played == 0 || b.played == 0) -> Reason.NEW
                    information >= CLOSE_INFORMATION -> Reason.CLOSE
                    else -> Reason.UNCERTAIN
                }
                active.add(Candidate(key, priority, probability, confidence, played, reason))
                return
            }

            // 층 3 — 접힌 것을 다시 꺼낼 표적. 무작위가 아니라 의심되는 곳을 앞세운다.
            var weight = random.nextDouble() * 0.5
            if (recheckTargets.contains(a.id) || recheckTargets.contains(b.id)) weight += 2.0
            if (tally != null && surprised(tally, probability)) weight += 1.0
            if (weight >= 0.5) {
                recheck.add(
                    weight to Candidate(key, priority, probability, confidence, played, Reason.RECHECK)
                )
            }
        }

        if (!capped) {
            for (i in 0 until n) for (j in i + 1 until n) visit(i, j)
        } else {
            for (i in 0 until n) {
                val last = min(n - 1, i + options.rankWindow)
                for (j in i + 1..last) visit(i, j)
            }
            repeat(options.sampleTail) {
                val i = random.nextInt(n)
                val j = random.nextInt(n)
                if (i != j) visit(min(i, j), max(i, j))
            }
        }

        active.sortWith(compareByDescending<Candidate> { it.priority }.thenBy { it.pair.lowId }
            .thenBy { it.pair.highId })
        recheck.sortWith(compareByDescending { it.first })

        val recheckSlots = if (options.queueLimit <= 0) 0 else {
            min((options.queueLimit * options.recheckShare).toInt(), recheck.size)
        }
        val activeSlots = max(0, options.queueLimit - recheckSlots)
        val queue = ArrayList<Candidate>(options.queueLimit.coerceAtLeast(0))
        queue.addAll(active.take(activeSlots))
        queue.addAll(recheck.take(recheckSlots).map { it.second })

        val remaining = max(0L, total - playedPairs - foldedPairs)
        return Plan(
            queue = queue,
            progress = Progress(total, playedPairs, foldedPairs, remaining, !capped),
            scannedPairs = scanned,
            scanCapped = capped
        )
    }

    /** 이미 붙였는데 모델의 예상을 뒤집은 적이 있는가 — 층 3이 우선 꺼낼 두 번째 표적. */
    private fun surprised(tally: DuelRating.PairTally, lowWinProbability: Double): Boolean {
        if (tally.matches <= 0) return false
        if (lowWinProbability >= 0.5 && tally.lowWins < tally.matches) return true
        if (lowWinProbability < 0.5 && tally.lowWins > 0.0) return true
        return false
    }
}
