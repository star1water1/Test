package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelGradeRef
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 대결 순위 → 등급 배정의 **순수 계산 전부** (B-113).
 *
 * 설계 정본은 `docs/feature_roadmap_2026-08.md` **4장**, 화면 정본은 목업
 * `docs/mockups/duel_grade_ui_2026-08.html`이다. config 표현은 [DuelGradeRef]가 든다.
 *
 * 여기 있는 것은 넷이다 — **컷 검증**([validate]) · **배정**([assign]) · **경계 제안**
 * ([suggestCuts]) · **미리보기 분류**([preview]). 넷 다 DB도 화면도 모르는 계산이라
 * 하네스가 통째로 고정한다.
 *
 * ## 백분위의 정의 — 분모가 전부다
 *
 * **백분위 = 점수 보유자끼리 다시 매긴 표준 경쟁 순위 ÷ 점수 보유 인원 × 100.**
 *
 * 두 자리에서 틀리기 쉽고, 둘 다 조용히 틀린다:
 *
 * 1. **분자** — [DuelScoreIndex.Entry.rank]는 순위표 줄의 등수라 한 판도 안 친 참가자까지
 *    센다. 그대로 나누면 백분위가 100%를 넘고, 100%를 넘는 값은 **어떤 컷에도 걸리지 않아
 *    말없이 최하 등급이 된다.** 그래서 [DuelScoreIndex.rankWithinScored]가 매긴 등수만 쓴다.
 * 2. **분모** — 참가자 전체가 아니라 **점수 보유 인원**이다([DuelScoreIndex] 계약 2).
 *    한 판도 안 친 캐릭터는 앵커 1500을 들고 있을 뿐 측정된 실력이 아니라, 그를 세면
 *    *"20명 중 3위"*라 해 놓고 실제로 견준 것은 12명인 어긋남이 된다. 무기록 캐릭터는
 *    **배정 대상도 아니다** — 앵커로 'C'를 지어내는 것이 계약 2가 막는 바로 그 병이다.
 *
 * ## 컷의 표현 — 누적이지 폭이 아니다
 *
 * [DuelGradeRef.Cut.topPercent]는 *"상위 몇 %까지 이 등급"*의 **누적값**이다. 화면의 구간
 * 슬라이더는 폭으로 보이지만(합 100%), 저장은 누적으로 한다 — 그래야 라벨 하나가 표에서
 * 사라졌을 때 **그 컷만 지우면 구간이 자동으로 다음 등급에 합쳐진다**(폭 표현이면 남은
 * 폭을 누구에게 줄지 다시 계산해야 하고, 그 계산이 곧 합이 100이 아니게 되는 자리다).
 */
object DuelGradeAssign {

    /** 컷 숫자의 눈금 — 소수 한 자리. 화면의 스테퍼는 이 눈금 위에서 0.5씩 움직인다(목업). */
    const val PERCENT_DECIMALS = 1

    /** 컷 문제 — 문구는 화면 계층(strings.xml)의 일이다([GradeTable.Problem]과 같은 규칙). */
    sealed class Problem {
        /** 실효 등급 표가 비었다 — 나눌 라벨이 없으면 컷은 뜻을 갖지 못한다. */
        object NoLabels : Problem()

        /** 등급이 하나뿐이다 — 나눌 경계가 없다(전원이 그 하나를 받는다면 산정이 아니다). */
        object SingleLabel : Problem()

        /** 컷의 수가 `라벨 수 - 1`이 아니다. 마지막 등급은 나머지 전부라 컷을 갖지 않는다. */
        data class CountMismatch(val expected: Int, val actual: Int) : Problem()

        /** 컷의 라벨이 실효 표의 차례(높은 등급부터)와 어긋난다 — 유령 라벨·순서 뒤바뀜. */
        data class LabelMismatch(val index: Int, val expected: String, val actual: String) : Problem()

        /** 상위 %가 0~100 밖이다. */
        data class OutOfRange(val label: String, val percent: Double) : Problem()

        /**
         * 상위 %가 앞 등급보다 작다 — 검증은 '단조 증가'가 아니라 **'단조 비감소'**다.
         * 같은 값은 **0% 구간**(이 축에서 안 쓰는 등급)이라 허용한다(설계 4-4 ⓒ).
         */
        data class Decreasing(val label: String, val percent: Double, val previous: Double) : Problem()
    }

    /**
     * 실효 등급 표 → **높은 등급부터의 라벨 차례**.
     *
     * 숫자가 서열이다(`GradeValueResolver`가 통계·수식에서 쓰는 그 숫자). 같은 숫자끼리는
     * 표의 차례를 지킨다 — 안정 정렬이라 config를 다시 읽어도 같은 줄이 나온다.
     */
    fun orderedLabels(grades: Map<String, Double>): List<String> =
        grades.entries.sortedByDescending { it.value }.map { it.key }

    /**
     * 컷 기본 초안 — **등급 수로 균등 분할**(Q2 사용자 확정. 5등급이면 20%씩).
     *
     * 빈 편집기로 시작하지 않는 것은 마찰이기 때문이고(원칙 04), 어차피 [suggestCuts]와 손
     * 편집이 그 위에 있다.
     */
    fun evenCuts(labels: List<String>): List<DuelGradeRef.Cut> {
        if (labels.size < 2) return emptyList()
        val n = labels.size
        return (1 until n).map { i -> DuelGradeRef.Cut(labels[i - 1], round(i * 100.0 / n)) }
    }

    /**
     * 컷 검증 — **저장 시 1회가 아니라 반영 직전에도 다시 돈다.**
     *
     * 그 사이에 등급 체계가 편집돼 실효 표가 바뀌었을 수 있고, 그때 컷은 실재하지 않는
     * 라벨을 가리킨다. 유령 라벨로 배정하면 **어떤 등급에도 없는 문자열이 캐릭터 값이 되어**
     * 스피너에서 고를 수도, 통계에서 셀 수도 없는 값이 조용히 심긴다.
     */
    fun validate(cuts: List<DuelGradeRef.Cut>, labels: List<String>): List<Problem> {
        val problems = mutableListOf<Problem>()
        if (labels.isEmpty()) return listOf(Problem.NoLabels)
        if (labels.size < 2) return listOf(Problem.SingleLabel)
        if (cuts.size != labels.size - 1) problems.add(Problem.CountMismatch(labels.size - 1, cuts.size))
        var previous = 0.0
        cuts.forEachIndexed { index, cut ->
            val expected = labels.getOrNull(index)
            if (expected != null && expected != cut.label) {
                problems.add(Problem.LabelMismatch(index, expected, cut.label))
            }
            if (cut.topPercent < 0.0 || cut.topPercent > 100.0 || !cut.topPercent.isFinite()) {
                problems.add(Problem.OutOfRange(cut.label, cut.topPercent))
            } else if (cut.topPercent < previous) {
                problems.add(Problem.Decreasing(cut.label, cut.topPercent, previous))
            }
            if (cut.topPercent.isFinite()) previous = maxOf(previous, cut.topPercent)
        }
        return problems
    }

    /**
     * 한 캐릭터의 배정 결과.
     *
     * @property percentile 상위 %(1~100). 화면이 *"상위 4%"*로 그대로 말한다 — 결정적이고
     *   설명 가능한 것이 이 방식을 고른 이유다(설계 4-4).
     * @property onBoundary **±오차가 컷을 가로지른다** — 판을 더 쌓으면 등급이 달라질 수 있는
     *   자리다. 배정을 막지는 않고 표식만 단다(마찰 최소). 오차 안의 차이는 표본 소음이므로
     *   *"이 등급이 확정"*이라고 말하는 쪽이 거짓말이다.
     */
    data class Assignment(
        val code: String,
        val label: String,
        val percentile: Double,
        val onBoundary: Boolean
    )

    /**
     * 점수 보유자 전원에게 등급을 배정한다.
     *
     * @param scores 축의 점수표. **[DuelScoreIndex.AxisScores.byCode]가 이미 계약 2로 걸러진
     *   목록**이라 여기서 무기록을 다시 거를 필요가 없다 — 애초에 들어오지 않는다.
     * @param labels [orderedLabels]가 낸 높은 등급부터의 차례.
     * @return 코드 → 배정. 라벨이 둘 미만이거나 점수 보유자가 없으면 빈 목록이다.
     */
    fun assign(
        scores: DuelScoreIndex.AxisScores,
        cuts: List<DuelGradeRef.Cut>,
        labels: List<String>
    ): List<Assignment> {
        if (labels.size < 2 || scores.byCode.isEmpty()) return emptyList()
        val ranks = DuelScoreIndex.rankWithinScored(scores)
        val total = scores.byCode.size
        val desc = scores.byCode.values.map { it.score }.sortedDescending().toIntArray()
        return scores.byCode.values.mapNotNull { entry ->
            val rank = ranks[entry.code] ?: return@mapNotNull null
            val percentile = percentileOf(rank, total)
            val label = labelFor(percentile, cuts, labels)
            // 경계 판정 — 점수를 오차만큼 밀었을 때의 등수로 다시 배정해 본다. 낙관·비관
            // 어느 쪽이든 다른 등급이 나오면 그 캐릭터는 컷 위에 서 있다.
            val optimistic = labelFor(
                percentileOf(rankAtScore(entry.score + entry.scoreError, desc, entry.score), total), cuts, labels
            )
            val pessimistic = labelFor(
                percentileOf(rankAtScore(entry.score - entry.scoreError, desc, entry.score), total), cuts, labels
            )
            Assignment(
                code = entry.code,
                label = label,
                percentile = percentile,
                onBoundary = optimistic != label || pessimistic != label
            )
        }
    }

    /**
     * 상위 % → 라벨. **처음으로 컷을 채우지 못한 등급**이 답이고, 전부 넘어서면 마지막 등급이다.
     *
     * 부동소수 비교에 [EPSILON]을 두는 것은 20%씩 5등급 같은 흔한 경우에 백분위가 정확히
     * 컷과 같아지기 때문이다(5명 중 1위 = 20.0%). 눈금이 소수 한 자리인데 표현 오차로
     * *"상위 20%인데 A가 아니다"*가 나오면 사용자는 컷을 잘못 적었다고 읽는다.
     */
    private fun labelFor(percentile: Double, cuts: List<DuelGradeRef.Cut>, labels: List<String>): String {
        for (cut in cuts) {
            if (percentile <= cut.topPercent + EPSILON) return cut.label
        }
        return labels.last()
    }

    private fun percentileOf(rank: Int, total: Int): Double =
        if (total <= 0) 100.0 else (rank.toDouble() / total) * 100.0

    /**
     * *"이 캐릭터의 점수가 [score]였다면 몇 등인가"* — 표준 경쟁 순위.
     *
     * **자기 자신은 빼고 센다.** [desc]에는 이 캐릭터의 현재 점수([selfScore])도 들어 있어서,
     * 오차만큼 낮춘 점수로 물을 때 *자기 원래 점수가 자기보다 위에 있는 것으로* 세어진다 —
     * 그 한 칸이 등수를 밀어 **오차와 무관하게 거의 모든 캐릭터가 '경계'로 표시된다.**
     * 표식이 전원에게 붙으면 아무 말도 안 하는 것과 같다.
     */
    private fun rankAtScore(score: Int, desc: IntArray, selfScore: Int): Int =
        1 + countAbove(desc, score) - (if (selfScore > score) 1 else 0)

    /**
     * 내림차순 배열에서 [score]보다 큰 값의 수 — 이분 탐색.
     *
     * 배정은 인원마다 세 번(본값·낙관·비관) 묻는다. 선형으로 세면 인원 제곱이 되고, 그것은
     * 목표 규모(실사용 ×30)에서 이 계산 하나가 적합보다 비싸지는 자리다('받쳐주는 확장성').
     */
    private fun countAbove(desc: IntArray, score: Int): Int {
        var low = 0
        var high = desc.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (desc[mid] > score) low = mid + 1 else high = mid
        }
        return low
    }

    /** [suggestCuts]의 산출 — 채운 컷과, 그중 **실제 간격에 맞춘** 경계 수. */
    data class Suggestion(val cuts: List<DuelGradeRef.Cut>, val moved: Int, val requested: Int)

    /**
     * **자연 단절 제안** — 점수 분포의 큰 간격으로 경계를 옮긴다(설계 4-4).
     *
     * 자동이 아니라 제안이다(자율성 우선) — 사용자가 고쳐서 저장한다.
     *
     * ## 겹침 조건이 핵심이다
     *
     * 후보는 *"큰 간격"*이 아니라 **"±오차가 겹치지 않는 큰 간격"**이다. 오차 안의 간격은
     * 표본 소음이라 판을 몇 번 더 치면 사라지고, 그것을 경계로 제안하면 **앱이 없는 구조를
     * 지어내 사용자에게 확신을 판다**(원칙 02가 금지하는 겉핥기). 이 앱은 이미 그 판정
     * 기계를 갖고 있다 — 상성 판정·모순 감지가 쓰는 그 겹침 규칙 그대로다.
     *
     * ## 못 찾은 경계는 균등 자리에 그대로 둔다
     *
     * 라벨이 아홉인데 자연 단절이 셋뿐일 수 있다. 그때 **없는 경계를 지어내지 않고**
     * 균등 분할 자리를 지키며, 몇 개를 옮겼는지 [Suggestion.moved]로 돌려준다 —
     * 화면이 *"경계 3개를 분포에 맞췄습니다"*라고 말할 수 있어야 사용자가 나머지를 손볼지
     * 판단한다(조용히 덜 하지 않는다 — 변수 제어).
     */
    fun suggestCuts(scores: DuelScoreIndex.AxisScores, labels: List<String>): Suggestion {
        val even = evenCuts(labels)
        val need = even.size
        val total = scores.byCode.size
        if (need == 0 || total < 2) return Suggestion(even, 0, need)

        val ordered = scores.byCode.values.sortedByDescending { it.score }
        // 후보 = 이웃한 두 점수 사이에서 ±오차가 겹치지 않는 자리. 위치 p는 "p번째까지가
        // 위 등급"을 뜻하므로 1..total-1이다.
        val candidates = (1 until total).mapNotNull { p ->
            val upper = ordered[p - 1]
            val lower = ordered[p]
            val gap = upper.score - lower.score
            val overlaps = (upper.score - upper.scoreError) <= (lower.score + lower.scoreError)
            if (gap <= 0 || overlaps) null else p to gap
        }.sortedByDescending { it.second }

        // 큰 간격부터 가장 가까운 **아직 안 채운 균등 슬롯**에 붙인다 — 경계를 새로 만드는
        // 것이 아니라 목업의 문구 그대로 "옮겨 주는" 것이다.
        val slotPosition = IntArray(need) { i -> ((i + 1).toDouble() * total / labels.size).roundToInt().coerceIn(1, total - 1) }
        val filled = BooleanArray(need)
        var moved = 0
        for ((position, _) in candidates) {
            if (moved >= need) break
            var best = -1
            var bestDistance = Int.MAX_VALUE
            for (slot in 0 until need) {
                if (filled[slot]) continue
                val distance = abs(slotPosition[slot] - position)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = slot
                }
            }
            if (best < 0) break
            filled[best] = true
            slotPosition[best] = position
            moved++
        }

        // 슬롯을 옮기다 보면 차례가 뒤집힐 수 있다(먼 슬롯이 가까운 간격을 가져간 경우).
        // 단조 비감소는 컷의 불변식이므로 여기서 세워 두고 나간다 — 검증에 걸리는 제안을
        // 내놓으면 사용자는 [경계 제안]을 누른 죄로 오류 문구를 본다.
        // 하나도 못 옮겼으면 균등 분할 그대로 돌려준다 — 자리를 인원으로 반올림한 값은
        // 균등 분할과 미세하게 갈리는데, **아무것도 제안하지 못한 결과가 컷을 흔드는 것**은
        // 사용자에게 "무언가 했다"는 거짓 신호다.
        if (moved == 0) return Suggestion(even, 0, need)

        slotPosition.sort()
        val cuts = even.mapIndexed { index, cut ->
            cut.copy(topPercent = round(slotPosition[index].toDouble() / total * 100.0))
        }
        return Suggestion(cuts, moved, need)
    }

    /** 미리보기 한 줄이 속하는 묶음 — 기본 체크가 여기서 갈린다(Q1). */
    enum class Bucket {
        /** 값이 비어 있다 — 채우는 것이라 기본 체크. */
        EMPTY,

        /** 현재 값이 **직전 반영이 그 캐릭터에 쓴 라벨**이다 — 갱신이라 기본 체크. */
        REAPPLY,

        /**
         * 그 외 — **손으로 적은 값**이다. 기본 해제(Q1 사용자 확정: *손값은 사용자의 명시적
         * 판단이라 덮는 쪽이 예외여야 한다*).
         */
        MANUAL
    }

    /**
     * 미리보기 한 줄. **값이 바뀌는 캐릭터만** 줄이 된다 — 안 바뀌는 것은 요약이 센다.
     */
    data class PreviewRow(
        val code: String,
        val current: String,
        val next: String,
        val percentile: Double,
        val onBoundary: Boolean,
        val bucket: Bucket
    )

    /**
     * 미리보기 산출 — 배정 결과와 현재 값을 맞대어 **바뀌는 줄과 안 바뀌는 수**로 가른다.
     *
     * @param currentValues 캐릭터 code → 이 필드의 현재 값(없으면 빈 문자열).
     * @param lastApplied 직전 반영의 흔적. **null이거나 [DuelGradeRef.LastApplied.omitted]면
     *   [Bucket.REAPPLY]를 만들지 않는다** — 판별 근거가 없는데 "지난 반영값"이라고 이름
     *   붙이면 앱이 모르는 것을 아는 척하는 것이고, 그 오판은 **손값을 기본 체크된 채로 덮는**
     *   방향으로 틀린다(가장 나쁜 방향이다). 그때 묶음은 빈 칸/값 있음 둘로 정직하게 줄어든다.
     */
    fun preview(
        assignments: List<Assignment>,
        currentValues: Map<String, String>,
        lastApplied: DuelGradeRef.LastApplied?
    ): Preview {
        val traceable = lastApplied != null && !lastApplied.omitted
        val rows = ArrayList<PreviewRow>(assignments.size)
        var unchanged = 0
        for (assignment in assignments) {
            val current = currentValues[assignment.code].orEmpty()
            if (current == assignment.label) {
                unchanged++
                continue
            }
            val bucket = when {
                current.isBlank() -> Bucket.EMPTY
                traceable && lastApplied?.assignments?.get(assignment.code) == current -> Bucket.REAPPLY
                else -> Bucket.MANUAL
            }
            rows.add(
                PreviewRow(
                    code = assignment.code,
                    current = current,
                    next = assignment.label,
                    percentile = assignment.percentile,
                    onBoundary = assignment.onBoundary,
                    bucket = bucket
                )
            )
        }
        return Preview(rows, unchanged, traceable)
    }

    /**
     * @property unchanged 배정 결과가 현재 값과 같아 줄이 되지 않은 수.
     * @property traceable 직전 반영의 배정표를 갖고 있는가 — false면 [Bucket.REAPPLY] 묶음이
     *   서지 않고 화면은 그 사실을 말한다.
     */
    data class Preview(
        val rows: List<PreviewRow>,
        val unchanged: Int,
        val traceable: Boolean
    ) {
        /** 기본 체크되는 줄 — 빈 칸과 지난 반영값(Q1). */
        val defaultChecked: List<PreviewRow> get() = rows.filter { it.bucket != Bucket.MANUAL }

        fun rowsOf(bucket: Bucket): List<PreviewRow> = rows.filter { it.bucket == bucket }
    }

    private const val EPSILON = 1e-9

    /** 눈금 맞춤 — 소수 한 자리([PERCENT_DECIMALS]). */
    fun round(percent: Double): Double = Math.round(percent * 10.0) / 10.0
}
