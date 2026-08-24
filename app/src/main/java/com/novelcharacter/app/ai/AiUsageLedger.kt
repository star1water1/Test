package com.novelcharacter.app.ai

/**
 * AI 사용량 누적 집계의 **순수 판정** — 무엇을 어느 통에 얼마나 더하고, 언제 걷어내는가
 * (JVM 시험 대상). 저장·시각은 [AiUsageStore]가, 화면은 설정 → AI 연동의 '사용량' 카드가 맡는다.
 *
 * 왜 있는가: 회당 토큰은 각 실행 결과가 이미 말하지만(`field_library_ai_token_usage`)
 * **기간 누적은 어디에도 없었다** — BYOK 사용자가 실제로 묻는 것은 *"이번 달 이 키로 얼마나
 * 썼는가"*인데, 그 답을 제공사 콘솔에 가서 확인해야 했다(문서의 「한계」가 스스로 적어 둔 결손).
 *
 * 설계 셋:
 * - **관문이 기록한다.** 인앱 기능 여덟이 각자 기록하면 여덟 벌이 되고 빠뜨린 자리는 조용하다
 *   (B-150·B-108이 표식에서 실증한 그 부류). [AiService]의 HTTP 성공 출구 하나가 기록하므로
 *   새 기능이 늘어도 집계는 자동으로 따라온다.
 * - **일 단위 통 + 프로바이더별 총계.** 일 통은 [RETENTION_DAYS]로 걷어 저장이 유한하고
 *   (받쳐주는 확장성 — 하루에 프로바이더 수만큼만 자란다), 총계는 걷지 않는 대신
 *   [Total.sinceDay]를 들고 있어 "누적"이 **언제부터의 누적인지** 화면이 말할 수 있다.
 * - **토큰 미보고는 갈라 센다.** usage를 안 실어 주는 서버의 요청을 0토큰으로 합치면
 *   집계가 실제보다 작게 보이는 거짓이 된다 — 요청 수에는 넣되 [Summary.unmeteredRequests]로
 *   표시해 "이 수에는 토큰 미보고 N건이 있다"를 화면이 말한다(변수 제어).
 */
object AiUsageLedger {

    /**
     * 일 통을 보관하는 날수. 화면의 최장 기간(30일)에 넉넉한 여유를 둔 값이고,
     * 그보다 오랜 몫은 총계가 든다 — 늘려도 집계가 달라지지 않고 저장만 는다.
     */
    const val RETENTION_DAYS = 92

    /** 하루 × 프로바이더 하나의 통. [displayName]·[model]은 마지막으로 본 값이다(아래 KDoc). */
    data class Bucket(
        val epochDay: Long,
        val providerId: String,
        /**
         * 마지막으로 본 표시명·모델 — 프로바이더가 **지워져도** 집계가 이름 없이 남지 않게
         * 기록 시점의 값을 함께 든다. 모델은 응답이 보고한 실제 모델이다(요청 모델이 아니라).
         */
        val displayName: String,
        val model: String,
        val requests: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        /** usage를 보고하지 않은 요청 수 — 0토큰으로 합치면 집계가 작게 보이는 거짓이 된다. */
        val unmeteredRequests: Int
    )

    /** 프로바이더 하나의 전체 누적. [sinceDay]가 "언제부터의 누적인지"를 든다. */
    data class Total(
        val providerId: String,
        val displayName: String,
        val model: String,
        val requests: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val unmeteredRequests: Int,
        /** 이 프로바이더의 첫 기록일 — "누적"이 열린 날짜다. */
        val sinceDay: Long
    )

    /** 저장 단위 — 일 통 목록 + 총계 목록. 직렬화는 [AiUsageCodec]이 단일 소스다. */
    data class Data(
        val days: List<Bucket> = emptyList(),
        val totals: List<Total> = emptyList()
    )

    /** 화면에 실을 프로바이더별 합산 — 기간 뷰와 누적 뷰가 같은 모양을 쓴다. */
    data class Summary(
        val providerId: String,
        val displayName: String,
        val model: String,
        val requests: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val unmeteredRequests: Int
    )

    /**
     * 성공 1건을 더한다. [inputTokens]·[outputTokens]가 **둘 다 null**이면 토큰 미보고로
     * 갈라 센다(한쪽만 온 응답은 온 쪽만 더한다 — 버리는 것보다 작게 잃는다).
     * 같은 호출에서 [RETENTION_DAYS]를 넘긴 일 통을 걷는다 — 기록하는 자리에서만 걷으면
     * 별도 청소 경로 없이도 낡은 통이 쌓이지 않는다(쿨다운 해제와 같은 관행).
     */
    fun record(
        data: Data,
        epochDay: Long,
        providerId: String,
        displayName: String,
        model: String,
        inputTokens: Int?,
        outputTokens: Int?
    ): Data {
        val unmetered = if (inputTokens == null && outputTokens == null) 1 else 0
        val inAdd = (inputTokens ?: 0).toLong()
        val outAdd = (outputTokens ?: 0).toLong()

        val cutoff = epochDay - (RETENTION_DAYS - 1)
        val kept = data.days.filter { it.epochDay >= cutoff }
        val dayIndex = kept.indexOfFirst { it.epochDay == epochDay && it.providerId == providerId }
        val days = if (dayIndex >= 0) {
            val b = kept[dayIndex]
            kept.toMutableList().also {
                it[dayIndex] = b.copy(
                    displayName = displayName, model = model,
                    requests = b.requests + 1,
                    inputTokens = b.inputTokens + inAdd,
                    outputTokens = b.outputTokens + outAdd,
                    unmeteredRequests = b.unmeteredRequests + unmetered
                )
            }
        } else {
            kept + Bucket(epochDay, providerId, displayName, model, 1, inAdd, outAdd, unmetered)
        }

        val totalIndex = data.totals.indexOfFirst { it.providerId == providerId }
        val totals = if (totalIndex >= 0) {
            val t = data.totals[totalIndex]
            data.totals.toMutableList().also {
                it[totalIndex] = t.copy(
                    displayName = displayName, model = model,
                    requests = t.requests + 1,
                    inputTokens = t.inputTokens + inAdd,
                    outputTokens = t.outputTokens + outAdd,
                    unmeteredRequests = t.unmeteredRequests + unmetered
                )
            }
        } else {
            data.totals + Total(providerId, displayName, model, 1, inAdd, outAdd, unmetered, epochDay)
        }
        return Data(days, totals)
    }

    /**
     * [fromDay]부터(포함)의 일 통을 프로바이더별로 합산한다.
     * 정렬은 **쓴 토큰 큰 순**이다 — 이 화면이 답할 질문이 "어디에 얼마나 썼는가"라서다.
     * 동률은 표시명 → id로 갈라 결정적이다(난수 없음).
     */
    fun summarize(days: List<Bucket>, fromDay: Long): List<Summary> =
        days.asSequence()
            .filter { it.epochDay >= fromDay }
            .groupBy { it.providerId }
            .map { (id, buckets) ->
                val latest = buckets.maxBy { it.epochDay }
                Summary(
                    providerId = id,
                    displayName = latest.displayName,
                    model = latest.model,
                    requests = buckets.sumOf { it.requests },
                    inputTokens = buckets.sumOf { it.inputTokens },
                    outputTokens = buckets.sumOf { it.outputTokens },
                    unmeteredRequests = buckets.sumOf { it.unmeteredRequests }
                )
            }
            .sortedWith(
                compareByDescending<Summary> { it.inputTokens + it.outputTokens }
                    .thenBy { it.displayName }.thenBy { it.providerId }
            )

    /** 누적 뷰 — 총계를 같은 [Summary] 모양·같은 정렬로 편다. */
    fun totalsSummary(totals: List<Total>): List<Summary> =
        totals.map {
            Summary(
                it.providerId, it.displayName, it.model,
                it.requests, it.inputTokens, it.outputTokens, it.unmeteredRequests
            )
        }.sortedWith(
            compareByDescending<Summary> { it.inputTokens + it.outputTokens }
                .thenBy { it.displayName }.thenBy { it.providerId }
        )

    /** 누적이 열린 첫 기록일 — 총계가 비어 있으면 null. "누적(…부터)" 라벨이 쓴다. */
    fun earliestSinceDay(totals: List<Total>): Long? = totals.minOfOrNull { it.sinceDay }

    /**
     * 사용자가 [AiProviderConfig]에 적어 둔 단가로 어림한 비용 — **추정이지 청구서가 아니다.**
     * 이 오브젝트도 앱도 단가표를 두지 않는다(모델마다 다르고 곧 낡는다) — 곱하는 값은
     * 전부 사용자가 직접 입력한 것이다.
     *
     * **둘 다 있어야 계산한다.** 한쪽만 있으면 절반은 빠진 금액인데 화면에는 숫자 하나만
     * 뜨므로 사용자는 그것을 전체 비용으로 읽는다 — 부정확한 절반을 보여 주느니 안 보여
     * 주는 쪽이 낫다(「한계」의 판단과 같은 근거).
     */
    fun estimatedCost(
        summary: Summary,
        inputPricePerMillionTokens: Double?,
        outputPricePerMillionTokens: Double?
    ): Double? {
        if (inputPricePerMillionTokens == null || outputPricePerMillionTokens == null) return null
        return summary.inputTokens / 1_000_000.0 * inputPricePerMillionTokens +
            summary.outputTokens / 1_000_000.0 * outputPricePerMillionTokens
    }
}
