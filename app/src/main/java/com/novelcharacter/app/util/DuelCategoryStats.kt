package com.novelcharacter.app.util

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **범주형 영향 필드의 집계** (B-112 — B-104 소비처 ⓐ의 나머지 반쪽).
 *
 * 사용자가 든 예는 *"1순위: 마력량, 2순위: 속성, 3순위: 작품 소속"*이었다. 그런데 뒤의 둘은
 * **차례가 없어 예측에서 넘어간다** — [DuelFieldLinks.compareOne]이 [DuelFieldLinks.Side.UNKNOWN]을
 * 내고, 그 처분 자체는 옳다(억지로 정렬하면 앱이 지어낸 서열이 창작자의 판단을 밀어낸다).
 * 그래서 속성·소속을 걸어도 **화면에 값이 뜨는 것이 전부**였다.
 *
 * **차례가 없는 값에도 할 일이 있다: 집계다.** 판과 그 필드값만 있으면
 * *"불이 물 상대로 3승 12패"*가 곧바로 나온다.
 *
 * ## 이것이 상성 층이 못 하는 일을 한다
 * [DuelCounterRelations]는 *"A가 B에게 진다"*까지만 말한다. 범주 집계는 **왜 그런지**를 말한다
 * (*"불이 물에 약하다"*). 원칙 02가 요구하는 '실질적 쓰임'이 여기 있고, **순서 없는 필드를
 * 거는 진짜 값어치**도 여기 있다.
 *
 * ## 승패를 그냥 세면 거짓말이 된다 — 예상과 견준다
 *
 * *"불이 물에 3승 12패"*만 놓으면 **불이 물에 약한 것인지, 물을 든 캐릭터들이 그냥 센 것인지
 * 구별되지 않는다.** 이 앱의 상성 층이 50%가 아니라 **모델의 예상**([DuelRating.Fit.winProbability])과
 * 견주는 것도 같은 이유다(설계 3장) — 그 뺄셈이 *개체의 강함*을 걷어내고 **값이 하는 몫**만 남긴다.
 *
 * 그래서 한 칸이 드는 것은 셋이다: 실제 승수 · **예상 승수** · 그 차이의 표준화 값(z).
 * 눈금은 [DuelCounterRelations.Options]와 같은 것을 쓴다(`minSamples` 3 · `zThreshold` 2.0) —
 * 두 화면이 같은 말을 다른 기준으로 하면 사용자가 어느 쪽을 믿을지 알 수 없다.
 *
 * ## 값이 여럿인 필드(MULTI_TEXT)는 토큰마다 센다
 * 통계가 이미 그렇게 한다([FieldValueTokenizer.splitForStats] — 태그 셋을 단 캐릭터는 세 칸에
 * 든다). 여기서도 같다: `{불, 물}` 대 `{물}`은 **불↔물 한 칸**에 들어간다.
 * **같은 값끼리는 세지 않는다**(물↔물) — 어느 쪽이 유리한지 말할 수 없는 자리라 담으면 소음이다.
 * 그래서 한 판이 칸 여럿에 들 수 있고, [Report.scannedMatches]가 **실제 판 수**를 따로 든다.
 */
object DuelCategoryStats {

    data class Options(
        /**
         * 칸이 목록에 실리는 하한. **[DuelCounterRelations.Options.minSamples]와 같은 값**이다 —
         * *"2승 0패를 상성이라 부르면 소음"*이 두 자리에 똑같이 적용된다.
         */
        val minMatches: Int = 3,

        /** 기운 자리로 부르는 눈금. [DuelCounterRelations.Options.zThreshold]와 같다. */
        val zThreshold: Double = 2.0,

        /**
         * 값이 이 비율 이상 수로 읽히면 **범주 필드가 아니다**([isCategorical]).
         * 마력량 350 대 351을 칸으로 가르면 표가 참가자 수만큼 늘고 아무 말도 하지 못한다 —
         * 그런 필드는 이미 [DuelFieldLinks.predict]가 차례로 다룬다.
         */
        val maxNumericShare: Double = 0.5,

        /**
         * 목록에 담을 칸의 상한. 넘친 만큼은 [Report.totalMatchups]가 들어 **자른 사실을 알린다**
         * (조용히 자르지 않는다 — 개발 의도 2번). 버려지는 것은 **z가 작은 칸**, 곧
         * *"예상대로였던 자리"*라 사용자가 볼 값어치가 가장 낮은 쪽이다.
         */
        val maxMatchups: Int = 30
    )

    /** 실제와 예상의 차이를 표준화한 값. 분산이 0이면 말할 것이 없다. */
    private fun zOf(wins: Double, expected: Double, variance: Double): Double =
        if (variance <= 1e-12) 0.0 else (wins - expected) / sqrt(variance)

    /**
     * 값 하나의 전체 전적 — *"불은 통틀어 몇 승인가"*.
     *
     * 칸([Matchup])과 **같은 판만** 센다(같은 값끼리 붙은 판은 양쪽에서 빠진다). 그래야 두
     * 목록의 수가 서로 맞고, 사용자가 더해 보다가 어긋난 곳을 만나지 않는다.
     */
    data class ValueTally(
        val value: String,
        val matches: Int,
        val wins: Double,
        val expectedWins: Double,
        val variance: Double
    ) {
        val losses: Double get() = matches - wins

        /** 예상보다 얼마나 더(덜) 이겼는가. 양수면 이 값이 예상보다 강하다. */
        val z: Double get() = zOf(wins, expectedWins, variance)
    }

    /**
     * 두 값이 맞붙은 칸.
     *
     * @property left 사전순으로 앞선 값 — **칸을 하나로 모으는 정규 키**다. 방향을 그대로 두면
     *   `불↔물`과 `물↔불`이 두 칸으로 갈려 같은 자리를 두 번 세게 된다
     *   ([DuelRecords.memberKey]가 상성에서 하는 일과 같은 부류).
     * @property leftWins [left] 쪽의 승수(무승부는 0.5).
     * @property expectedLeftWins 점수 모델이 본 [left] 쪽의 예상 승수.
     */
    data class Matchup(
        val left: String,
        val right: String,
        val matches: Int,
        val leftWins: Double,
        val expectedLeftWins: Double,
        val variance: Double
    ) {
        val rightWins: Double get() = matches - leftWins

        /** 양수면 [left]가 예상보다 강하고, 음수면 [right]가 강하다. */
        val z: Double get() = zOf(leftWins, expectedLeftWins, variance)

        /** 예상보다 한쪽으로 기운 칸인가 — 화면이 짚어 주는 자리. */
        fun leans(options: Options = Options()): Boolean =
            matches >= options.minMatches && abs(z) >= options.zThreshold

        /** 기운 쪽의 값. 기울지 않았으면 null. */
        fun favored(options: Options = Options()): String? = when {
            !leans(options) -> null
            z > 0.0 -> left
            else -> right
        }
    }

    /**
     * @property matchups 상한까지 담은 칸. z가 큰 것부터다 — **예상과 가장 어긋난 자리**가
     *   사용자가 볼 값어치가 가장 큰 자리이기 때문이다.
     * @property totalMatchups [Options.minMatches]를 넘긴 칸 전량. [matchups]보다 크면 잘렸다.
     * @property values 값별 전체 전적. 판이 [Options.minMatches] 미만인 값도 담는다 —
     *   *"이 값은 아직 덜 붙었다"*는 것 자체가 사용자가 알아야 할 정보다.
     * @property scannedMatches 실제로 센 판 수. 한 판이 칸 여럿에 들 수 있어 칸의 합과 다르다.
     * @property skippedNoValue 두 쪽 중 한쪽이라도 이 필드의 값이 비어 세지 못한 판.
     *   **0이 아니면 화면이 말해야 한다** — 값을 안 적은 캐릭터가 많으면 이 표가 전체를 대표하지
     *   못하는데, 그 사실이 표에는 드러나지 않는다(개발 의도 2번).
     * @property skippedSameValue 두 쪽의 값이 같아 세지 않은 판(물↔물).
     * @property malformedMatches 승자가 두 참가자 중 어느 쪽도 아닌 깨진 판 — 외부에서 편집된
     *   엑셀이 들어올 수 있는 자리라 여기서도 세어 둔다([DuelMatchLog.Outcome.BROKEN]과 같은 부류).
     */
    data class Report(
        val key: String,
        val matchups: List<Matchup>,
        val values: List<ValueTally>,
        val totalMatchups: Int,
        val scannedMatches: Int,
        val skippedNoValue: Int,
        val skippedSameValue: Int,
        val malformedMatches: Int
    ) {
        val truncated: Boolean get() = matchups.size < totalMatchups

        /** **표에 그릴 칸**이 하나라도 있는가. */
        val any: Boolean get() = matchups.isNotEmpty() || values.isNotEmpty()

        /**
         * **사용자에게 말할 것**이 하나라도 있는가 — 화면의 가시성 판정은 이것이다.
         *
         * [any]와 갈라 둔 것이 요점이다. 값이 빈 쪽이 낀 판은 칸에 들어가지 않으므로,
         * 값 있는 참가자끼리 붙은 판이 하나도 없으면 칸이 통째로 비고 [any]가 거짓이 된다.
         * 그 상태에서 필드를 목록에서 빼 버리면 **못 센 판 고지가 함께 사라지고**, 그 자리에
         * "차례가 없는 영향 필드가 걸려 있지 않습니다"라는 거짓 문구가 대신 뜬다 —
         * 사용자는 값을 비워 둔 캐릭터 때문이라는 사실에 닿을 길이 없다(개발 의도 2번).
         */
        val hasSomethingToSay: Boolean
            get() = any || skippedNoValue > 0 || skippedSameValue > 0 || malformedMatches > 0

        /** 예상보다 기운 칸 — 화면이 머리에서 세는 수. */
        fun leaning(options: Options = Options()): List<Matchup> = matchups.filter { it.leans(options) }
    }

    /**
     * **이 필드를 범주로 다룰 것인가.**
     *
     * 수로 읽히는 값이 절반을 넘으면 아니다 — 그런 필드는 [DuelFieldLinks.predict]가 이미
     * 차례로 다루고, 칸으로 가르면 표가 참가자 수만큼 늘어난다. 등급 이름(`S-1`)처럼 수로
     * 읽히지 **않는** 값은 범주다([DuelFieldLinks.numberOf]가 맨 앞에서만 수를 읽으므로
     * `S-1`은 −1로 둔갑하지 않는다 — 인수인계 ⑥의 관행이 여기서도 값을 한다).
     *
     * 서로 다른 값이 둘 미만이면 견줄 자리가 없으므로 아니다.
     */
    fun isCategorical(tokensById: Map<Long, List<String>>, options: Options = Options()): Boolean {
        val all = tokensById.values.flatten()
        if (all.isEmpty()) return false
        if (all.distinct().size < 2) return false
        val numeric = all.count { DuelFieldLinks.numberOf(it) != null }
        return numeric.toDouble() / all.size < options.maxNumericShare
    }

    /**
     * 한 범주 필드의 집계를 낸다. **순수 함수다.**
     *
     * @param matches 이 축의 판. [DuelRecords.Resolved.matches]가 그대로 들어온다(고아 판 포함 —
     *   값을 가진 참가자의 판이면 세고, 아니면 [Report.skippedNoValue]가 센다).
     *
     *   **사용자가 ③(상성이다)으로 확정해 점수 적합에서 뺀 짝도 그대로 센다.**
     *   [DuelCounterRelations.analyze]가 *"뺐다고 검사에서까지 빠지면 후보가 사라진다"*며
     *   같은 판들을 받는 것과 같은 근거다 — 오히려 **그 짝들이야말로 이 표가 이유를 대야 할
     *   자리**다. 빼면 사용자가 상성이라고 이미 판정한 관계가 범주 표에서 사라져,
     *   *"왜 그런가"*를 물으러 온 화면이 정작 그 관계에 대해 아무 말도 못 한다.
     *   예상 승수가 **그 짝을 보지 않은 깨끗한 적합**에서 나오는 것도 이 방향이 맞다
     *   (오염되지 않은 예상과 견줘야 어긋남이 그대로 드러난다).
     * @param fit 점수 적합. **예상 승수의 출처**이며, 이것 없이 승패만 세면 개체의 강함과
     *   값의 몫이 섞인다(위 설명).
     * @param tokensById 참가자 id → 이 필드값의 토큰. 비어 있는 참가자는 담지 않아도 된다.
     */
    fun report(
        key: String,
        matches: List<DuelRating.Match>,
        fit: DuelRating.Fit,
        tokensById: Map<Long, List<String>>,
        options: Options = Options()
    ): Report {
        val cells = LinkedHashMap<Pair<String, String>, DoubleArray>()
        val perValue = LinkedHashMap<String, DoubleArray>()
        var scanned = 0
        var noValue = 0
        var sameValue = 0
        var malformed = 0

        for (match in matches) {
            if (match.winnerId != null && match.winnerId != match.aId && match.winnerId != match.bId) {
                malformed++
                continue
            }
            val aTokens = tokensById[match.aId].orEmpty().distinct()
            val bTokens = tokensById[match.bId].orEmpty().distinct()
            if (aTokens.isEmpty() || bTokens.isEmpty()) { noValue++; continue }

            // 무승부는 양쪽 0.5승 — 저장 형식이 처음부터 받는 값이다(DuelRating.Match).
            val aWin = when (match.winnerId) {
                null -> 0.5
                match.aId -> 1.0
                else -> 0.0
            }
            val pA = fit.winProbability(match.aId, match.bId)

            var counted = false
            for (aToken in aTokens) {
                for (bToken in bTokens) {
                    if (aToken == bToken) continue
                    counted = true
                    val leftIsA = aToken < bToken
                    val cellKey = if (leftIsA) aToken to bToken else bToken to aToken
                    val leftWin = if (leftIsA) aWin else 1.0 - aWin
                    val leftP = if (leftIsA) pA else 1.0 - pA
                    cells.getOrPut(cellKey) { DoubleArray(4) }.let {
                        it[0] += 1.0            // 판 수
                        it[1] += leftWin        // left 승수
                        it[2] += leftP          // left 예상 승수
                        it[3] += leftP * (1.0 - leftP)
                    }
                    perValue.getOrPut(aToken) { DoubleArray(4) }.let {
                        it[0] += 1.0; it[1] += aWin; it[2] += pA; it[3] += pA * (1.0 - pA)
                    }
                    perValue.getOrPut(bToken) { DoubleArray(4) }.let {
                        it[0] += 1.0; it[1] += 1.0 - aWin; it[2] += 1.0 - pA
                        it[3] += pA * (1.0 - pA)
                    }
                }
            }
            if (counted) scanned++ else sameValue++
        }

        val allMatchups = cells
            .map { (pair, acc) ->
                Matchup(
                    left = pair.first,
                    right = pair.second,
                    matches = acc[0].toInt(),
                    leftWins = acc[1],
                    expectedLeftWins = acc[2],
                    variance = acc[3]
                )
            }
            .filter { it.matches >= options.minMatches }
            // z가 큰 것부터 — 예상과 가장 어긋난 자리가 먼저다. 같으면 판이 많은 쪽,
            // 그마저 같으면 이름순으로 **차례를 고정한다**(같은 기록에 같은 화면이 나와야 한다).
            .sortedWith(
                compareByDescending<Matchup> { abs(it.z) }
                    .thenByDescending { it.matches }
                    .thenBy { it.left }
                    .thenBy { it.right }
            )

        val values = perValue
            .map { (value, acc) ->
                ValueTally(
                    value = value,
                    matches = acc[0].toInt(),
                    wins = acc[1],
                    expectedWins = acc[2],
                    variance = acc[3]
                )
            }
            .sortedWith(
                compareByDescending<ValueTally> { it.matches }
                    .thenByDescending { it.wins }
                    .thenBy { it.value }
            )

        return Report(
            key = key,
            matchups = allMatchups.take(options.maxMatchups),
            values = values,
            totalMatchups = allMatchups.size,
            scannedMatches = scanned,
            skippedNoValue = noValue,
            skippedSameValue = sameValue,
            malformedMatches = malformed
        )
    }
}
