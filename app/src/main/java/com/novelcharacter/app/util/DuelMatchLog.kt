package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelMatch

/**
 * **대결 기록을 사람이 읽고 고칠 수 있는 모양으로** (B-104 — 사용자 요청 2026.08.04:
 * *"대결 기록이 남아서 그걸 엑셀이나 인앱에서도 편집할 수 있게 해주면 좋겠고"*).
 *
 * 판 하나하나는 이미 남아 있었지만(`duel_matches`) **볼 길이 없었다.** 되돌리기가 집는 것은
 * *방금 그거* 하나뿐이고, 상성 상세가 집는 것은 *그 관계를 만든 판들*이라 — 어제 잘못 누른
 * 판 하나를 찾아 고치는 길은 어디에도 없었다. 원칙 04가 금지하는 부류다
 * (*"일일이 확인하지 않으면 존재를 알 수 없는 데이터가 있어서는 안 된다"*).
 *
 * ## 여기가 층 C의 소비처다
 * 축에 걸린 **영향 필드**([DuelFieldLinks])가 이 목록에서 값을 한다 — 판마다 *"필드는 이쪽이
 * 위라는데 실제로는 저쪽이 이겼다"*를 짚어 준다. 그것이 사용자가 고칠 자리이고, 고치는 자리와
 * 짚는 자리가 같은 화면인 것이 이 설계의 요점이다.
 *
 * **판단을 전부 여기 두는 것은 화면이 얇아야 검증되기 때문이다**(「세션 착수 규칙」 4번).
 */
object DuelMatchLog {

    /** 한 판의 결과 — 저장된 `winnerCode`를 사람이 읽는 넷으로 옮긴 것. */
    enum class Outcome {
        A_WON, B_WON,

        /** 무승부(`winnerCode`가 null). */
        DRAW,

        /**
         * **깨진 판** — 승자가 두 참가자 중 어느 쪽도 아니다. 외부에서 편집한 엑셀이 들어올 수
         * 있는 자리이며, 조용히 무승부로 접지 않고 이대로 보여 사용자가 고치게 한다
         * (개발 의도 2번 — 검증 → 알림 → 바로잡을 경로).
         */
        BROKEN
    }

    /** 영향 필드 한 줄 — 두 참가자의 값을 나란히 놓은 것. */
    data class FieldCell(
        val key: String,
        val label: String,
        val rank: Int,
        val aValue: String,
        val bValue: String,
        /** 이 필드가 가리키는 쪽. 견줄 수 없으면 [DuelFieldLinks.Side.UNKNOWN]. */
        val side: DuelFieldLinks.Side
    )

    /**
     * 목록의 한 줄.
     *
     * @property code 판의 안정 식별자 — **화면이 다시 집는 열쇠**다. 목록 위치로 집으면
     *   그 사이에 판이 늘거나 줄었을 때 엉뚱한 판을 고친다(설계 5장 ③이 되돌리기에서 겪은 것과
     *   같은 부류).
     * @property aMissing 참가자가 지금 살아 있지 않은가 — 판은 남지만 이름을 낼 수 없다.
     */
    data class Row(
        val code: String,
        val aCode: String,
        val bCode: String,
        val aName: String?,
        val bName: String?,
        val outcome: Outcome,
        val winnerCode: String?,
        val decidedAt: Long,
        val cells: List<FieldCell>,
        val prediction: DuelFieldLinks.Prediction,
        val agreement: DuelFieldLinks.Agreement
    ) {
        val aMissing: Boolean get() = aName == null
        val bMissing: Boolean get() = bName == null

        /** 필드가 말한 것과 실제가 반대인가 — 목록이 짚어 주는 자리. */
        val disagrees: Boolean get() = agreement == DuelFieldLinks.Agreement.DISAGREE
    }

    /**
     * @property shown 지금 목록에 실린 줄 수.
     * @property total 이 축에 쌓인 판 전량. [shown]보다 크면 **상한에 걸려 잘렸다는 뜻**이고
     *   화면이 그 사실을 말해야 한다(조용히 자르지 않는다).
     */
    data class Summary(
        val shown: Int,
        val total: Int,
        val disagreements: Int,
        val draws: Int,
        val broken: Int
    ) {
        val truncated: Boolean get() = shown < total
    }

    /**
     * 저장된 판들을 목록 줄로 옮긴다.
     *
     * @param matches 볼 판(이미 상한만큼 잘라서 넘길 것). 차례가 그대로 화면 차례다.
     * @param namesByCode 참가자 코드 → 이름. 없는 코드는 *사라진 참가자*다 — **버리지 않는다.**
     * @param influences 축의 영향 필드(순위 순).
     * @param labels 필드 키 → 이름. 없으면 키를 그대로 보인다(지워진 필드일 수 있다).
     * @param valuesByCode 참가자 코드 → (필드 키 → 값).
     */
    fun rows(
        matches: List<DuelMatch>,
        namesByCode: Map<String, String>,
        influences: List<DuelFieldLinks.Link>,
        labels: Map<String, String>,
        valuesByCode: Map<String, Map<String, String>>
    ): List<Row> = matches.map { match ->
        val aValues = valuesByCode[match.aCode].orEmpty()
        val bValues = valuesByCode[match.bCode].orEmpty()
        val prediction = DuelFieldLinks.predict(influences, aValues, bValues)
        val outcome = outcomeOf(match)
        Row(
            code = match.code,
            aCode = match.aCode,
            bCode = match.bCode,
            aName = namesByCode[match.aCode],
            bName = namesByCode[match.bCode],
            outcome = outcome,
            winnerCode = match.winnerCode,
            decidedAt = match.decidedAt,
            cells = influences.mapIndexed { index, link ->
                FieldCell(
                    key = link.key,
                    label = labels[link.key] ?: link.key,
                    rank = index + 1,
                    aValue = aValues[link.key].orEmpty(),
                    bValue = bValues[link.key].orEmpty(),
                    side = DuelFieldLinks.compareOne(link, aValues[link.key], bValues[link.key])
                )
            },
            prediction = prediction,
            agreement = DuelFieldLinks.agreementOf(prediction, sideOf(outcome))
        )
    }

    /**
     * 승자 코드를 결과로 읽는다.
     *
     * **깨진 판을 무승부로 접지 않는 것이 요점이다** — 접으면 외부에서 잘못 편집된 행이
     * *"사용자가 비겼다고 판정한 판"*으로 둔갑하고, 그러면 고칠 자리가 사라진다.
     */
    fun outcomeOf(match: DuelMatch): Outcome = when {
        match.winnerCode == null -> Outcome.DRAW
        match.winnerCode == match.aCode -> Outcome.A_WON
        match.winnerCode == match.bCode -> Outcome.B_WON
        else -> Outcome.BROKEN
    }

    /** 대조에 넘길 쪽. 무승부·깨진 판은 *갈리지 않은 것*으로 넘긴다. */
    private fun sideOf(outcome: Outcome): DuelFieldLinks.Side? = when (outcome) {
        Outcome.A_WON -> DuelFieldLinks.Side.A
        Outcome.B_WON -> DuelFieldLinks.Side.B
        Outcome.DRAW, Outcome.BROKEN -> null
    }

    /** 목록이 머리에 다는 요약. [total]은 상한 밖의 판까지 센 전량이다. */
    fun summarize(rows: List<Row>, total: Int): Summary = Summary(
        shown = rows.size,
        total = total,
        disagreements = rows.count { it.disagrees },
        draws = rows.count { it.outcome == Outcome.DRAW },
        broken = rows.count { it.outcome == Outcome.BROKEN }
    )

    /** '어긋난 것만' 걸러 보기 — 사용자가 실제로 고치러 오는 자리다. */
    fun onlyDisagreements(rows: List<Row>): List<Row> = rows.filter { it.disagrees }
}
