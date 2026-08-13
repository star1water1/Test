package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelCounterVerdict

/**
 * **"순위표와 상성 상세가 무엇을 보이는가"의 단일 소스** (B-104 화면 계층).
 *
 * 점수·상성·진행률은 전부 [DuelRating]·[DuelCounterRelations]·[DuelPairing]이 이미 냈다.
 * 여기서 정하는 것은 **그것을 줄로 세우는 규칙**이며, 그 규칙에 판단이 셋 들어 있어
 * pure로 둔다(「세션 착수 규칙」 4번 — 화면 안에 두면 어떤 로컬 검증도 보지 못한다).
 *
 * 1. **동점은 같은 등수다** — 그것도 **표시 점수 기준으로**. 강함 γ는 1e-9까지 다르므로
 *    내부 값으로 가르면 **화면에 1500이 둘 있는데 1위와 2위**가 된다. 사용자가 보는 것으로
 *    가르는 것이 유일하게 설명 가능한 규칙이다.
 * 2. **"믿어도 되는 줄인가"를 행이 스스로 말한다**(설계 3장) — `±오차`와 판 수를 점수 옆에
 *    두고, 한 판도 안 한 참가자는 [Row.provisional]로 가린다. 그 점수는 계산된 것이 아니라
 *    **앵커 그대로**([DuelRating.ANCHOR_SCORE])라 다른 1500과 뜻이 다르다.
 * 3. **버려진 것은 세어서 알린다**(개발 의도 2번). 적합에서 빠진 판, 사라진 참가자, 상한에
 *    걸려 덜 훑은 사실이 전부 [Caveats] 하나로 모인다 — 화면 셋이 각자 세면 갈린다.
 */
object DuelStandings {

    /**
     * 순위표 한 줄.
     *
     * @property rank 1부터. 동점이면 같은 값이고 **다음 등수는 그만큼 건너뛴다**(1,1,3).
     * @property counters 이 참가자가 낀 상성 건수 — P-10이 확정한 배지를 행 단위로 나눈 것.
     * @property provisional 한 판도 치르지 않았다. 점수는 앵커 그대로이며 등수의 뜻이 약하다.
     */
    data class Row(
        val rank: Int,
        val id: Long,
        val code: String,
        val score: Int,
        val scoreError: Int,
        val played: Int,
        val wins: Double,
        val counters: Int,
        val provisional: Boolean
    )

    /**
     * 화면이 반드시 함께 말해야 하는 것들 — **전부 "조용히 버리지 않았다"의 증거다**.
     *
     * @property orphanMatches 참가자 목록에 없는 코드가 낀 판. 지워진 캐릭터의 판이 여기다
     *   (**지우지 않았으므로 복원하면 되살아난다** — [DuelRecords]의 규칙 1).
     * @property missingParticipants 그 판에 남아 있는, 지금 살아 있지 않은 참가자 수.
     * @property excludedMatches 사용자가 ③ 상성으로 확정해 점수에서 뺀 판.
     * @property malformedMatches 승자가 두 참가자 중 어느 쪽도 아닌 깨진 판(외부 편집 파일).
     * @property notConverged 적합이 반복 상한에서 멈췄다. 점수를 그대로 믿기 어렵다.
     * @property pairScanCapped 짝 전수를 다 훑지 못했다 — 진행률이 **훑은 범위의 값**이다.
     * @property triangleScanCapped 순환 훑기가 상한에 걸렸다. 순환 목록이 전부가 아니다.
     * @property crossCharacterMatches **이미지 축에서만** 0이 아니다 (B-175) — 내 그림과 남의
     *   그림이 함께 적힌 판이다. 이미지 대결은 캐릭터마다 따로 도므로(설계 13-2) 그런 판은
     *   **어느 캐릭터의 순위에도 들지 않는데**, [orphanMatches]에 섞어 세면 화면이 그것을
     *   *"지워진 참가자의 판"*이라 말한다 — 지워지지 않았으므로 **거짓 고지**다. 갈라 센다.
     */
    data class Caveats(
        val orphanMatches: Int,
        val missingParticipants: Int,
        val excludedMatches: Int,
        val malformedMatches: Int,
        val notConverged: Boolean,
        val pairScanCapped: Boolean,
        val triangleScanCapped: Boolean,
        val crossCharacterMatches: Int = 0
    ) {
        /** 하나라도 말할 것이 있는가 — 없으면 화면이 고지 영역을 통째로 감춘다. */
        val any: Boolean
            get() = orphanMatches > 0 || missingParticipants > 0 || excludedMatches > 0 ||
                malformedMatches > 0 || notConverged || pairScanCapped || triangleScanCapped ||
                crossCharacterMatches > 0
    }

    /**
     * 상성 상세의 한 항목 — 후보든 이미 내린 처분이든 같은 모양으로 읽는다.
     *
     * @property memberCodes 뜻이 있는 순서. 천적은 `[센 쪽, 잡는 쪽]`, 순환은 이기는 차례다.
     * @property verdictKind 이미 내려진 처분([DuelCounterVerdict.KIND_COUNTER] /
     *   [DuelCounterVerdict.KIND_UNDECIDED]). null이면 아직 판정하지 않은 후보다.
     * @property verdictId 처분 행의 id — 화면이 '처분 물리기'를 걸 대상.
     * @property stillDetected 처분한 뒤에도 지금 기록에서 같은 어긋남이 잡히는가.
     *   **②(아직 안 정했다)의 *"나중에 다시 묻는다"*가 이 값으로 성립한다**(확정 문서 2-1) —
     *   ②는 점수에서 빼지 않으므로 어긋남이 그대로 남고, 그것이 곧 다시 물을 때가 됐다는 신호다.
     *   ③은 적합에서 빠져 잔차가 사라지므로 보통 false이며, true라면 **그 짝 말고 다른
     *   자리에서 같은 관계가 또 잡혔다**는 뜻이라 사용자가 알아야 한다.
     */
    data class CounterItem(
        val kind: DuelCounterRelations.Kind,
        val memberCodes: List<String>,
        val deviation: Double,
        val samples: Int,
        val verdictKind: String? = null,
        val verdictId: Long = 0L,
        val stillDetected: Boolean = false
    ) {
        val isCycle: Boolean get() = kind == DuelCounterRelations.Kind.CYCLE
    }

    /**
     * @property candidates 앱이 찾은 어긋남 중 **아직 판정하지 않은 것**. 이미 처분한 관계는
     *   여기 넣지 않는다 — 넣으면 한 관계가 두 줄로 보이고, 사용자가 같은 것을 두 번 판정한다.
     * @property decided 사용자가 이미 처분한 관계. ③으로 확정한 짝은 점수 적합에서 빠져
     *   후보 목록에 다시 나타나지 않으므로, **여기 없으면 물릴 길이 없다.**
     * @property unresolvedMembers 코드를 되찾지 못해 목록에 싣지 못한 항목 수.
     *   **0이 아니면 화면이 그 사실을 말해야 한다**(개발 의도 2번 — 말없이 사라지지 않는다).
     */
    data class CounterReview(
        val candidates: List<CounterItem>,
        val decided: List<CounterItem>,
        val unresolvedMembers: Int
    )

    /**
     * 순위표의 줄들. 강함 내림차순이고, 같은 **표시 점수**끼리 같은 등수를 받는다.
     *
     * @param report 상성 보고 — 배지 수를 행에 나눠 붙이는 데만 쓴다.
     */
    fun rows(
        fit: DuelRating.Fit,
        report: DuelCounterRelations.Report,
        records: DuelRecords.Resolved
    ): List<Row> {
        val counterCounts = HashMap<Long, Int>()
        for (candidate in report.direct + report.cycles) {
            for (id in candidate.members.distinct()) {
                counterCounts[id] = (counterCounts[id] ?: 0) + 1
            }
        }

        val ordered = fit.ratings.sortedWith(
            compareByDescending<DuelRating.Rating> { it.score }
                .thenByDescending { it.strength }
                .thenBy { it.id }
        )
        val rows = ArrayList<Row>(ordered.size)
        var rank = 0
        var previousScore: Int? = null
        ordered.forEachIndexed { index, rating ->
            if (rating.score != previousScore) {
                rank = index + 1
                previousScore = rating.score
            }
            rows.add(
                Row(
                    rank = rank,
                    id = rating.id,
                    code = records.codeOf(rating.id).orEmpty(),
                    score = rating.score,
                    scoreError = rating.scoreError,
                    played = rating.played,
                    wins = rating.wins,
                    counters = counterCounts[rating.id] ?: 0,
                    provisional = rating.played == 0
                )
            )
        }
        return rows
    }

    /** 화면이 함께 말해야 하는 것들을 한 자리에 모은다. */
    fun caveats(
        fit: DuelRating.Fit,
        report: DuelCounterRelations.Report,
        plan: DuelPairing.Plan,
        missingParticipants: Int,
        crossCharacterMatches: Int = 0
    ): Caveats = Caveats(
        orphanMatches = fit.orphanMatches,
        missingParticipants = missingParticipants,
        excludedMatches = fit.excludedMatches,
        malformedMatches = fit.malformedMatches,
        notConverged = !fit.converged,
        pairScanCapped = plan.scanCapped,
        triangleScanCapped = report.triangleScanCapped,
        crossCharacterMatches = crossCharacterMatches
    )

    /**
     * 상성 상세가 보일 것 — 후보와 이미 내린 처분을 **같은 정규 키로** 맞춰 가른다.
     *
     * 회전(`A>B>C` vs `B>C>A`)이나 반전으로 적힌 같은 관계가 후보에도 처분에도 있으면
     * **한 관계가 두 줄로 보인다.** [DuelRecords.memberKey]가 그 순서를 지운 키이고,
     * 저장 쪽 유니크 인덱스도 같은 값 위에 서 있다 — 대조를 다른 방법으로 하면 두 자리가 갈린다.
     *
     * **열쇠를 저장된 `memberKey`로 쓰지 않는다** (B-175). 후보 쪽 열쇠는 [DuelRecords.Resolved]를
     * 거쳐 나온 **대표 표기**로 만들어지는데, 저장된 값은 원본 표기다(R-42). 이미지 축에서 그
     * 둘이 갈리면 — 같은 파일의 표기가 둘일 때다 — **이미 판정한 처분이 후보로 한 번 더 뜬다.**
     * 양쪽을 같은 표로 옮겨 견주면 그 길이 아예 없다([DuelRecords.Resolved.canonicalCode]).
     * 캐릭터 축에서는 대표 표기가 코드 그대로라 **글자 하나 달라지지 않는다.**
     */
    fun counterReview(
        report: DuelCounterRelations.Report,
        records: DuelRecords.Resolved,
        verdicts: List<DuelCounterVerdict>
    ): CounterReview {
        // 처분마다 (풀어낸 참가자, 정규 키)를 한 번만 만든다 — 아래에서 두 번 쓴다.
        val decoded = verdicts.map { verdict ->
            val members = DuelRecords.decodeMembers(verdict.memberCodes)
            Triple(verdict, members, DuelRecords.memberKey(members.map { records.canonicalCode(it) }))
        }
        val verdictByKey = decoded.associate { (verdict, _, key) -> key to verdict }
        val candidates = ArrayList<CounterItem>(report.count)
        val detected = HashMap<String, DuelCounterRelations.Candidate>()
        var unresolved = 0

        for (candidate in report.direct + report.cycles) {
            val codes = candidate.members.map { records.codeOf(it) }
            if (codes.any { it == null }) { unresolved++; continue }
            val members = codes.filterNotNull()
            val key = DuelRecords.memberKey(members)
            detected[key] = candidate
            if (verdictByKey.containsKey(key)) continue
            candidates.add(
                CounterItem(
                    kind = candidate.kind,
                    memberCodes = members,
                    deviation = candidate.deviation,
                    samples = candidate.samples
                )
            )
        }

        val decided = decoded
            .sortedByDescending { (verdict, _, _) -> verdict.decidedAt }
            .map { (verdict, members, key) ->
                // 어긋남의 크기는 **지금 기록에서 다시 낸 것**을 쓴다. 처분할 때의 값을 저장해
                // 두면 기록이 바뀐 뒤에도 옛 숫자를 들고 있게 된다(파생값을 저장하지 않는 이유).
                val current = detected[key]
                CounterItem(
                    kind = if (verdict.shape == DuelCounterVerdict.SHAPE_CYCLE) {
                        DuelCounterRelations.Kind.CYCLE
                    } else {
                        DuelCounterRelations.Kind.DIRECT
                    },
                    memberCodes = members,
                    deviation = current?.deviation ?: 0.0,
                    samples = current?.samples ?: 0,
                    verdictKind = verdict.kind,
                    verdictId = verdict.id,
                    stillDetected = current != null
                )
            }

        return CounterReview(candidates, decided, unresolved)
    }
}
