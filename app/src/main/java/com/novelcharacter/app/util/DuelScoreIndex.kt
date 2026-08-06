package com.novelcharacter.app.util

/**
 * **대결 점수가 대결 밖에서 무엇으로 보이는가의 단일 소스** (B-117).
 *
 * 설계 정본은 `docs/duel_system_design_2026-08.md` **9장**이다.
 *
 * 대결은 축을 만들고 판을 쌓는 데까지 갖췄지만 **그 결과를 앱의 나머지가 몰랐다** —
 * *"강함 순으로 정렬"*도 *"점수 분포"*도 되지 않았다. 이 파일이 그 자리를 잇는다.
 *
 * ## 지키는 계약 셋
 *
 * 1. **밖에서 보는 수는 순위표가 보이는 그 수다.** 점수는 [DuelStandings.rows]가 낸 것을
 *    그대로 담는다 — 여기서 다시 계산하면 **같은 캐릭터가 화면마다 다른 점수를 갖는다.**
 *    그것이 설계 4장이 파생값을 저장하지 않는 이유(*"두 자리가 갈라진다"*)와 정확히 같은 병이며,
 *    저장 대신 **계산의 출구를 하나로 묶는 것**이 이 파일의 몫이다.
 * 2. **한 판도 안 치른 참가자는 값이 없다.** 그 점수는 계산된 것이 아니라 앵커
 *    ([DuelRating.ANCHOR_SCORE]) 그대로라 다른 1500과 뜻이 다르고
 *    ([DuelStandings.Row.provisional]이 순위표에서 가리는 그 사실이다), **정렬에 섞으면
 *    아무 판도 안 한 캐릭터가 목록 한가운데에 박힌다** — 앱이 지어낸 서열이고 원칙 02가
 *    금지하는 겉핥기다. 값 없음으로 두면 커스텀 필드 정렬과 같은 규칙(최후순)을 탄다.
 * 3. **빠진 것은 세어서 알린다**(개발 의도 2번). 참가자인데 점수가 없는 수, 지워진 캐릭터의
 *    판, 상성으로 뺀 판, 깨진 판, 적합이 수렴하지 않은 사실이 [AxisScores]에 함께 실린다 —
 *    *"내 캐릭터가 왜 이 목록에 없지"*의 답이 화면에 있어야 한다.
 *
 * ## 여기 없는 것 — 판을 읽는 일
 *
 * 이 파일은 **적합이 끝난 뒤**를 맡는다. 판을 읽고 적합을 돌리는 것은
 * [com.novelcharacter.app.data.repository.DuelRepository]이며, 그쪽이 순위표와 **같은
 * 진입점**([DuelCounterRelations.analyzeTwoPass])을 쓰기 때문에 계약 1이 성립한다.
 */
object DuelScoreIndex {

    /**
     * 한 참가자의 점수 — 순위표 한 줄에서 **밖이 쓸 것만** 남긴 것.
     *
     * @property rank 그 축의 순위표에서의 등수. 동점은 같은 값이다([DuelStandings.rows]의 규칙).
     * @property played 적합에 쓰인 판 수. **0인 참가자는 이 표에 들어오지 않는다**(계약 2).
     */
    data class Entry(
        val code: String,
        val score: Int,
        val scoreError: Int,
        val rank: Int,
        val played: Int
    )

    /**
     * 한 축의 점수 전부와, 그 축이 함께 말해야 하는 것들.
     *
     * @property byCode 점수가 있는 참가자만. **키는 참가자 코드**다(캐릭터 축이면 `Character.code`).
     * @property unplayed 참가자인데 한 판도 안 치러 값이 없는 수 — 계약 2로 빠진 인원이다.
     * @property scanned 이 축이 들고 있는 판 수. 상한 고지가 아니라 규모의 표시다.
     */
    data class AxisScores(
        val axisId: Long,
        val axisCode: String,
        val axisName: String,
        val universeId: Long,
        val byCode: Map<String, Entry>,
        val unplayed: Int,
        val scanned: Int,
        val orphanMatches: Int,
        val missingParticipants: Int,
        val excludedMatches: Int,
        val malformedMatches: Int,
        val notConverged: Boolean
    ) {
        /** 점수가 매겨진 참가자 수. */
        val scored: Int get() = byCode.size

        fun scoreOf(code: String): Int? = byCode[code]?.score

        fun entryOf(code: String): Entry? = byCode[code]

        /** 화면이 함께 말할 것이 있는가 — 없으면 고지 영역을 통째로 감춘다. */
        val hasCaveat: Boolean
            get() = unplayed > 0 || orphanMatches > 0 || missingParticipants > 0 ||
                excludedMatches > 0 || malformedMatches > 0 || notConverged

        companion object {
            /** 아직 아무것도 읽지 않은 축 — 판이 하나도 없을 때의 모양이다(예외가 아니다). */
            fun empty(axisId: Long, axisCode: String, axisName: String, universeId: Long) =
                AxisScores(
                    axisId = axisId,
                    axisCode = axisCode,
                    axisName = axisName,
                    universeId = universeId,
                    byCode = emptyMap(),
                    unplayed = 0,
                    scanned = 0,
                    orphanMatches = 0,
                    missingParticipants = 0,
                    excludedMatches = 0,
                    malformedMatches = 0,
                    notConverged = false
                )
        }
    }

    /**
     * 순위표의 줄들을 밖이 쓰는 표로 옮긴다.
     *
     * @param rows [DuelStandings.rows]가 낸 것 그대로 — **여기서 점수를 다시 내지 않는다**(계약 1).
     * @param fit 그 줄들이 나온 적합. 고지 숫자를 여기서 든다 — [DuelStandings.Caveats]를 받지
     *   않는 것은 그쪽이 **짝 고르기 계획을 요구**하기 때문이다(`pairScanCapped`). 대기열은
     *   대결 화면의 것이지 목록·통계의 것이 아니라, 계획을 세우려고 짝 전수를 훑는
     *   비용(목표 규모 위쪽에서 147ms)을 이 경로에 지울 이유가 없다. **숫자의 출처는 같다** —
     *   `Caveats`도 이 값들을 [DuelRating.Fit]에서 그대로 옮겨 담을 뿐이다.
     */
    fun of(
        axisId: Long,
        axisCode: String,
        axisName: String,
        universeId: Long,
        rows: List<DuelStandings.Row>,
        fit: DuelRating.Fit,
        missingParticipants: Int,
        scanned: Int
    ): AxisScores {
        val byCode = LinkedHashMap<String, Entry>(rows.size)
        var unplayed = 0
        for (row in rows) {
            // 계약 2 — 앵커 그대로인 점수는 값이 아니다. 코드가 빈 줄(해석 실패)도 같이 뺀다:
            // 키가 없으면 어느 캐릭터의 것인지 말할 수 없어 어차피 이을 수 없다.
            if (row.provisional || row.code.isEmpty()) {
                if (row.provisional) unplayed++
                continue
            }
            byCode[row.code] = Entry(
                code = row.code,
                score = row.score,
                scoreError = row.scoreError,
                rank = row.rank,
                played = row.played
            )
        }
        return AxisScores(
            axisId = axisId,
            axisCode = axisCode,
            axisName = axisName,
            universeId = universeId,
            byCode = byCode,
            unplayed = unplayed,
            scanned = scanned,
            orphanMatches = fit.orphanMatches,
            missingParticipants = missingParticipants,
            excludedMatches = fit.excludedMatches,
            malformedMatches = fit.malformedMatches,
            notConverged = !fit.converged
        )
    }

    /**
     * **점수가 있는 참가자끼리 다시 매긴 등수** — 표준 경쟁 순위(동점은 같은 등수, 다음은 건너뜀).
     *
     * [Entry.rank]를 그대로 쓰면 안 되는 자리가 있다. 그 등수는 **순위표 줄의 등수**라
     * 한 판도 안 친 참가자까지 세는데, 그들의 앵커 1500이 표 한가운데에 끼므로 1500보다 낮은
     * 캐릭터의 등수가 [AxisScores.scored]를 넘어선다 — *"3명 중 4위"*다. AI 컨텍스트가 자기
     * 재검토에서 실제로 그 자리를 밟았고, 등급 산정(B-113)의 백분위는 **이 등수 ÷ [AxisScores.scored]**라
     * 같은 함정 위에 서 있다. 분모를 넘는 백분위는 곧 100%를 넘는 상위 %이고, 그것은
     * 어떤 컷에도 걸리지 않아 **말없이 최하 등급으로 떨어진다.**
     *
     * 그래서 두 소비처가 각자 매기지 않고 여기서 한 번 매긴다 — 두 경로가 다른 등수를 갖는
     * 것은 이 저장소가 반복해 겪은 실패 형태다(계약 1이 점수에 대해 말하는 것과 같은 이유).
     */
    fun rankWithinScored(scores: AxisScores): Map<String, Int> {
        val ordered = scores.byCode.values.sortedByDescending { it.score }
        val out = HashMap<String, Int>(ordered.size)
        var rank = 1
        var previous: Int? = null
        ordered.forEachIndexed { index, entry ->
            if (index > 0 && entry.score != previous) rank = index + 1
            previous = entry.score
            out[entry.code] = rank
        }
        return out
    }

    /**
     * 점수로 줄 세운다 — **값 없음은 방향과 무관하게 최후순**이다.
     *
     * 커스텀 필드 정렬(`CharacterViewModel.sortByField`)이 이미 그 규칙을 쓰고 있고, 같은
     * 목록의 정렬 둘이 *"값이 없는 것"*을 반대로 다루면 사용자가 오름차순을 누를 때마다
     * 빈 캐릭터가 목록 머리로 몰려온다. **정렬 방향은 값에 대한 물음이지 값의 유무에 대한
     * 물음이 아니다.**
     *
     * 같은 점수끼리는 [tieBreak]가 가른다(보통 이름) — 넣지 않으면 목록이 갱신될 때마다
     * 동점자의 자리가 서로 바뀌어 **손대지 않았는데 목록이 움직인다.**
     */
    fun <T> sorted(
        items: List<T>,
        ascending: Boolean,
        scores: AxisScores,
        codeOf: (T) -> String,
        tieBreak: (T) -> String
    ): List<T> {
        val scoreOf = { item: T -> scores.scoreOf(codeOf(item)) }
        val valued = items.filter { scoreOf(it) != null }
        val missing = items.filter { scoreOf(it) == null }
        val ordered = if (ascending) {
            valued.sortedWith(compareBy<T> { scoreOf(it) ?: 0 }.thenBy { tieBreak(it).lowercase() })
        } else {
            valued.sortedWith(
                compareByDescending<T> { scoreOf(it) ?: 0 }.thenBy { tieBreak(it).lowercase() }
            )
        }
        // 값 없음끼리도 안정된 차례를 준다 — 그러지 않으면 최후순 덩어리가 매번 뒤섞인다.
        return ordered + missing.sortedBy { tieBreak(it).lowercase() }
    }

    /**
     * 점수 분포 — 구간은 [NumericBinning]이 만든다.
     *
     * **여기서 경계를 따로 계산하지 않는 것이 요점이다.** 이 앱은 분포를 만드는 쪽과 그 조각의
     * 인원을 세는 쪽이 구간을 각자 계산해 **두 수가 어긋난 전례**를 갖고 있다(그 문서가
     * [NumericBinning]의 머리 주석이다). 점수도 수치이므로 그 단일 소스를 그대로 탄다.
     *
     * @return 구간과 그 구간에 든 인원. 값이 둘 미만이거나 전원이 같은 점수면 빈 목록이다 —
     *   나눌 폭이 없다는 뜻이고, 호출부는 분포 대신 그 사실을 말한다.
     */
    fun distribution(
        scores: AxisScores,
        binCount: Int = NumericBinning.DEFAULT_BIN_COUNT
    ): List<Pair<NumericBinning.Bin, Int>> {
        val values = scores.byCode.values.map { it.score.toFloat() }
        val bins = NumericBinning.autoBins(values, binCount)
        if (bins.isEmpty()) return emptyList()
        return bins.map { bin -> bin to values.count { bin.contains(it) } }
    }
}
