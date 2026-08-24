package com.novelcharacter.app.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 사용량 원장의 **직렬화 규칙** — 순수 판정(JVM 테스트 대상).
 *
 * [AiUsageStore]에서 갈라 두는 이유는 [AiProviderCodec]과 같다(B-132): 저장소는 Context
 * 의존이라 어떤 로컬 검증도 왕복을 못 보고, 칸 하나가 직렬화에서 빠지면 증상 없이 그 수만
 * 사라진다. 여기 있으면 왕복 전체가 시험 대상이다.
 *
 * 수동 JsonObject 직렬화는 저장소 관행 그대로다 — R8 난독화에서 리플렉션 직렬화가 깨지는
 * 문제를 원천 차단한다. 손상된 항목은 전체를 버리지 않고 그 항목만 건너뛴다(변수 제어).
 */
object AiUsageCodec {

    fun encode(data: AiUsageLedger.Data): String = JsonObject().apply {
        add("days", JsonArray().apply {
            data.days.forEach { b ->
                add(JsonObject().apply {
                    addProperty("epochDay", b.epochDay)
                    addProperty("providerId", b.providerId)
                    addProperty("displayName", b.displayName)
                    addProperty("model", b.model)
                    addProperty("requests", b.requests)
                    addProperty("inputTokens", b.inputTokens)
                    addProperty("outputTokens", b.outputTokens)
                    addProperty("unmeteredRequests", b.unmeteredRequests)
                })
            }
        })
        add("totals", JsonArray().apply {
            data.totals.forEach { t ->
                add(JsonObject().apply {
                    addProperty("providerId", t.providerId)
                    addProperty("displayName", t.displayName)
                    addProperty("model", t.model)
                    addProperty("requests", t.requests)
                    addProperty("inputTokens", t.inputTokens)
                    addProperty("outputTokens", t.outputTokens)
                    addProperty("unmeteredRequests", t.unmeteredRequests)
                    addProperty("sinceDay", t.sinceDay)
                })
            }
        })
    }.toString()

    fun decode(raw: String?): AiUsageLedger.Data {
        if (raw.isNullOrBlank()) return AiUsageLedger.Data()
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            return AiUsageLedger.Data()
        }
        val days = root.getAsJsonArray("days")
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { o ->
                try {
                    AiUsageLedger.Bucket(
                        epochDay = o.get("epochDay").asLong,
                        providerId = o.get("providerId").asString,
                        displayName = o.get("displayName")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                        model = o.get("model")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                        requests = o.get("requests")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                        inputTokens = o.get("inputTokens")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                        outputTokens = o.get("outputTokens")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                        unmeteredRequests = o.get("unmeteredRequests")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .orEmpty()
        val totals = root.getAsJsonArray("totals")
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { o ->
                try {
                    AiUsageLedger.Total(
                        providerId = o.get("providerId").asString,
                        displayName = o.get("displayName")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                        model = o.get("model")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                        requests = o.get("requests")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                        inputTokens = o.get("inputTokens")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                        outputTokens = o.get("outputTokens")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                        unmeteredRequests = o.get("unmeteredRequests")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                        sinceDay = o.get("sinceDay").asLong
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .orEmpty()
        return AiUsageLedger.Data(days, totals)
    }
}
