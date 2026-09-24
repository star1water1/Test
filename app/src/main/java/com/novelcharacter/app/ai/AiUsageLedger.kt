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
 * - **일 단위 통 + 제공자·모델별 총계.** 일 통은 [RETENTION_DAYS]로 걷어 저장이 유한하고
 *   (받쳐주는 확장성 — 하루에 프로바이더 수만큼만 자란다), 총계는 걷지 않는 대신
 *   [Total.sinceDay]를 들고 있어 "누적"이 **언제부터의 누적인지** 화면이 말할 수 있다.
 * - **토큰 미보고는 갈라 센다.** 토큰 수 일부 또는 전부를 안 실어 주는 서버의 요청을 0토큰으로 합치면
 *   집계가 실제보다 작게 보이는 거짓이 된다 — 요청 수에는 넣되 [Summary.unmeteredRequests]로
 *   표시해 "이 수에는 토큰 미보고 N건이 있다"를 화면이 말한다(변수 제어).
 */
object AiUsageLedger {

    /**
     * 일 통을 보관하는 날수. 화면의 최장 기간(30일)에 넉넉한 여유를 둔 값이고,
     * 그보다 오랜 몫은 총계가 든다 — 늘려도 집계가 달라지지 않고 저장만 는다.
     */
    const val RETENTION_DAYS = 92

    /** 하루 × 프로바이더 × 확인된 모델 하나의 통. 구버전은 모델을 알 수 없는 별도 통이다. */
    data class Bucket(
        val epochDay: Long,
        val providerId: String,
        /** 마지막으로 본 표시명. 모델은 응답이 보고한 실제 모델로 통의 식별자다. */
        val displayName: String,
        val model: String,
        val requests: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        /** 입력·출력 중 하나라도 토큰 수를 보고하지 않은 요청 수. */
        val unmeteredRequests: Int,
        val modelKnown: Boolean = true,
        val pricedCost: Double = 0.0,
        val unpricedRequests: Int = 0
    )

    /** 제공자·모델 하나의 전체 누적. [sinceDay]가 "언제부터의 누적인지"를 든다. */
    data class Total(
        val providerId: String,
        val displayName: String,
        val model: String,
        val requests: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val unmeteredRequests: Int,
        /** 이 프로바이더의 첫 기록일 — "누적"이 열린 날짜다. */
        val sinceDay: Long,
        val modelKnown: Boolean = true,
        val pricedCost: Double = 0.0,
        val unpricedRequests: Int = 0
    )

    data class SpeechBucket(
        val epochDay: Long, val providerId: String, val displayName: String, val model: String,
        val requests: Int, val seconds: Long, val unknownDurationRequests: Int,
        val pricedCost: Double, val unpricedRequests: Int
    )

    data class SpeechTotal(
        val providerId: String, val displayName: String, val model: String,
        val requests: Int, val seconds: Long, val unknownDurationRequests: Int,
        val pricedCost: Double, val unpricedRequests: Int, val sinceDay: Long
    )

    /** 저장 단위 — 일 통 목록 + 총계 목록. 직렬화는 [AiUsageCodec]이 단일 소스다. */
    data class Data(
        val days: List<Bucket> = emptyList(),
        val totals: List<Total> = emptyList(),
        val speechDays: List<SpeechBucket> = emptyList(),
        val speechTotals: List<SpeechTotal> = emptyList()
    )

    /** 화면에 실을 제공자·모델별 합산 — 기간 뷰와 누적 뷰가 같은 모양을 쓴다. */
    data class Summary(
        val providerId: String,
        val displayName: String,
        val model: String,
        val requests: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val unmeteredRequests: Int,
        val modelKnown: Boolean = true,
        val pricedCost: Double = 0.0,
        val unpricedRequests: Int = 0
    )

    data class SpeechSummary(
        val providerId: String, val displayName: String, val model: String,
        val requests: Int, val seconds: Long, val unknownDurationRequests: Int,
        val pricedCost: Double, val unpricedRequests: Int
    )

    /**
     * 성공 1건을 더한다. [inputTokens]·[outputTokens] 중 하나라도 없으면 토큰 미보고로
     * 갈라 센다. 한쪽만 온 응답은 온 쪽을 합산하되 누락 사실을 함께 보인다.
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
        outputTokens: Int?,
        inputPrice: Double? = null,
        outputPrice: Double? = null
    ): Data {
        val modelKnown = model.isNotBlank()
        val unmetered = if (inputTokens == null || outputTokens == null) 1 else 0
        val inAdd = (inputTokens ?: 0).toLong()
        val outAdd = (outputTokens ?: 0).toLong()
        val priceAvailable = modelKnown && inputTokens != null && outputTokens != null &&
            inputTokens >= 0 && outputTokens >= 0 &&
            inputPrice != null && outputPrice != null &&
            inputPrice.isFinite() && outputPrice.isFinite() && inputPrice >= 0 && outputPrice >= 0
        val candidate = if (priceAvailable) inAdd / 1_000_000.0 * inputPrice!! +
            outAdd / 1_000_000.0 * outputPrice!! else null
        val priced = candidate?.isFinite() == true
        val cost = if (priced) candidate!! else 0.0
        val unpriced = if (priced) 0 else 1

        val cutoff = epochDay - (RETENTION_DAYS - 1)
        val kept = data.days.filter { it.epochDay >= cutoff }
        val dayIndex = kept.indexOfFirst {
            it.epochDay == epochDay && it.providerId == providerId && it.modelKnown == modelKnown &&
                (!modelKnown || it.model == model)
        }
        val days = if (dayIndex >= 0) {
            val b = kept[dayIndex]
            kept.toMutableList().also {
                it[dayIndex] = b.copy(
                    displayName = displayName,
                    requests = b.requests + 1,
                    inputTokens = b.inputTokens + inAdd,
                    outputTokens = b.outputTokens + outAdd,
                    unmeteredRequests = b.unmeteredRequests + unmetered,
                    pricedCost = b.pricedCost + cost,
                    unpricedRequests = b.unpricedRequests + unpriced
                )
            }
        } else {
            kept + Bucket(epochDay, providerId, displayName, model, 1, inAdd, outAdd, unmetered,
                modelKnown, cost, unpriced)
        }

        val totalIndex = data.totals.indexOfFirst {
            it.providerId == providerId && it.modelKnown == modelKnown &&
                (!modelKnown || it.model == model)
        }
        val totals = if (totalIndex >= 0) {
            val t = data.totals[totalIndex]
            data.totals.toMutableList().also {
                it[totalIndex] = t.copy(
                    displayName = displayName,
                    requests = t.requests + 1,
                    inputTokens = t.inputTokens + inAdd,
                    outputTokens = t.outputTokens + outAdd,
                    unmeteredRequests = t.unmeteredRequests + unmetered,
                    pricedCost = t.pricedCost + cost,
                    unpricedRequests = t.unpricedRequests + unpriced
                )
            }
        } else {
            data.totals + Total(providerId, displayName, model, 1, inAdd, outAdd, unmetered,
                epochDay, modelKnown, cost, unpriced)
        }
        return data.copy(days = days, totals = totals)
    }

    /**
     * [fromDay]부터(포함)의 일 통을 제공자·모델별로 합산한다.
     * 정렬은 **쓴 토큰 큰 순**이다 — 이 화면이 답할 질문이 "어디에 얼마나 썼는가"라서다.
     * 동률은 표시명 → id로 갈라 결정적이다(난수 없음).
     */
    fun summarize(days: List<Bucket>, fromDay: Long): List<Summary> =
        days.asSequence()
            .filter { it.epochDay >= fromDay }
            .groupBy { Triple(it.providerId, it.modelKnown, if (it.modelKnown) it.model else "") }
            .map { (key, buckets) ->
                val latest = buckets.maxBy { it.epochDay }
                Summary(
                    providerId = key.first,
                    displayName = latest.displayName,
                    model = if (key.second) key.third else "",
                    requests = buckets.sumOf { it.requests },
                    inputTokens = buckets.sumOf { it.inputTokens },
                    outputTokens = buckets.sumOf { it.outputTokens },
                    unmeteredRequests = buckets.sumOf { it.unmeteredRequests },
                    modelKnown = key.second,
                    pricedCost = buckets.sumOf { it.pricedCost },
                    unpricedRequests = buckets.sumOf { it.unpricedRequests }
                )
            }
            .sortedWith(
                compareByDescending<Summary> { it.inputTokens + it.outputTokens }
                    .thenBy { it.displayName }.thenBy { it.providerId }.thenBy { it.model }
            )

    /** 누적 뷰 — 총계를 같은 [Summary] 모양·같은 정렬로 편다. */
    fun totalsSummary(totals: List<Total>): List<Summary> =
        totals.map {
            Summary(
                it.providerId, it.displayName, it.model,
                it.requests, it.inputTokens, it.outputTokens, it.unmeteredRequests,
                it.modelKnown, it.pricedCost, it.unpricedRequests
            )
        }.sortedWith(
            compareByDescending<Summary> { it.inputTokens + it.outputTokens }
                .thenBy { it.displayName }.thenBy { it.providerId }.thenBy { it.model }
        )

    fun recordSpeech(data: Data, epochDay: Long, providerId: String, displayName: String,
        model: String, seconds: Long, pricePerMinute: Double? = null): Data {
        val durationKnown = seconds > 0
        val usableSeconds = if (durationKnown) seconds else 0L
        val priceAvailable = durationKnown && pricePerMinute != null && pricePerMinute.isFinite() &&
            pricePerMinute >= 0
        val candidate = if (priceAvailable) usableSeconds / 60.0 * pricePerMinute!! else null
        val priced = candidate?.isFinite() == true
        val cost = if (priced) candidate!! else 0.0
        val unknown = if (durationKnown) 0 else 1
        val unpriced = if (priced) 0 else 1
        val kept = data.speechDays.filter { it.epochDay >= epochDay - (RETENTION_DAYS - 1) }
        val dayIndex = kept.indexOfFirst { it.epochDay == epochDay && it.providerId == providerId && it.model == model }
        val days = if (dayIndex < 0) kept + SpeechBucket(epochDay, providerId, displayName, model,
            1, usableSeconds, unknown, cost, unpriced) else kept.toMutableList().also {
            val b = it[dayIndex]
            it[dayIndex] = b.copy(displayName = displayName, requests = b.requests + 1,
                seconds = b.seconds + usableSeconds, unknownDurationRequests = b.unknownDurationRequests + unknown,
                pricedCost = b.pricedCost + cost, unpricedRequests = b.unpricedRequests + unpriced)
        }
        val totalIndex = data.speechTotals.indexOfFirst { it.providerId == providerId && it.model == model }
        val totals = if (totalIndex < 0) data.speechTotals + SpeechTotal(providerId, displayName, model,
            1, usableSeconds, unknown, cost, unpriced, epochDay) else data.speechTotals.toMutableList().also {
            val t = it[totalIndex]
            it[totalIndex] = t.copy(displayName = displayName, requests = t.requests + 1,
                seconds = t.seconds + usableSeconds, unknownDurationRequests = t.unknownDurationRequests + unknown,
                pricedCost = t.pricedCost + cost, unpricedRequests = t.unpricedRequests + unpriced)
        }
        return data.copy(speechDays = days, speechTotals = totals)
    }

    fun summarizeSpeech(days: List<SpeechBucket>, fromDay: Long): List<SpeechSummary> =
        days.filter { it.epochDay >= fromDay }.groupBy { it.providerId to it.model }.map { (key, buckets) ->
            val latest = buckets.maxBy { it.epochDay }
            SpeechSummary(key.first, latest.displayName, key.second, buckets.sumOf { it.requests },
                buckets.sumOf { it.seconds }, buckets.sumOf { it.unknownDurationRequests },
                buckets.sumOf { it.pricedCost }, buckets.sumOf { it.unpricedRequests })
        }.sortedWith(compareByDescending<SpeechSummary> { it.seconds }.thenBy { it.displayName }.thenBy { it.model })

    fun speechTotalsSummary(totals: List<SpeechTotal>): List<SpeechSummary> = totals.map {
        SpeechSummary(it.providerId, it.displayName, it.model, it.requests, it.seconds,
            it.unknownDurationRequests, it.pricedCost, it.unpricedRequests)
    }.sortedWith(compareByDescending<SpeechSummary> { it.seconds }.thenBy { it.displayName }.thenBy { it.model })

    /** 누적이 열린 첫 기록일 — 총계가 비어 있으면 null. "누적(…부터)" 라벨이 쓴다. */
    fun earliestSinceDay(totals: List<Total>): Long? = totals.minOfOrNull { it.sinceDay }

}
