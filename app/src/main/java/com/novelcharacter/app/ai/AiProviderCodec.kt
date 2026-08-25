package com.novelcharacter.app.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 프로바이더 설정 목록의 **직렬화 규칙** — 순수 판정(JVM 테스트 대상).
 *
 * [AiProviderStore]에서 갈라 둔 이유는 B-132가 실증한 것이다: `imagesUnsupported`는
 * 모델·`hasLearnedFacts()`·설정 화면의 R-23 초기화까지 전부 배선됐는데 **영속 계층에만
 * 빠져 있었고**, 저장소가 Context 의존이라 **어떤 로컬 검증도 그 누락을 볼 수 없었다.**
 * 결과는 조용했다 — 비전 미지원 모델에서 청크마다 400→재시도가 되풀이되고,
 * 사전 고지 코드는 도달하지 않는 죽은 길이 됐다.
 *
 * 그래서 고침을 "빠진 두 줄"로 하지 않는다. 규칙을 여기로 빼면 **왕복 전체가 시험 대상**이 되어,
 * 다음에 학습값이 하나 더 늘 때 같은 사고가 나면 그 자리에서 잡힌다.
 *
 * 수동 JsonObject 직렬화를 유지하는 것은 종전 그대로다 — R8 난독화에서 리플렉션 직렬화가
 * 깨지는 문제를 원천 차단한다. API 키는 여기 절대 들어오지 않는다([AiKeyStore] 전담).
 */
object AiProviderCodec {

    /**
     * 해석 결과. 손상된 항목은 **전체를 버리지 않고 그 항목만** 건너뛴다(데이터 유실 최소화).
     * 건너뛴 사실을 조용히 삼키지 않도록 수를 함께 돌려준다 — 로그는 Context를 쥔 저장소가 남긴다.
     */
    data class DecodeResult(
        val configs: List<AiProviderConfig>,
        /** 해석에 실패해 건너뛴 항목 수. */
        val skipped: Int = 0,
        /** 목록 자체(배열)를 읽지 못했는가 — 이 경우 [configs]는 비어 있다. */
        val unreadable: Boolean = false
    )

    fun encode(configs: List<AiProviderConfig>): String =
        JsonArray().apply { configs.forEach { add(toJson(it)) } }.toString()

    /**
     * **기기 밖으로 나가는 목록** — 엑셀 '앱 설정' 시트·월드패키지가 쓴다.
     *
     * [encode]와 갈라 두는 것이 요점이다. 저장소가 쓰는 [encode]는 *"이 기기의 지금 상태를
     * 그대로 적는다"*이고, 여기는 *"다른 기기에서도 참인 것만 적는다"*라 **물음이 다르다.**
     * 같은 함수로 두면 그 갈림이 없어져, 저장에 새 칸이 늘 때마다 그것이 이식 가능한지 아무도
     * 묻지 않은 채 파일에 실린다 — [AiProviderConfig.cooldownUntilMillis]가 실제로 그렇게 실렸다.
     *
     * ## 무엇을 빼는가 — 쿨다운 하나다
     *
     * 학습값 다섯 중 넷(`detectedOutputLimit`·`temperatureUnsupported`·
     * `maxTokensParamUnsupported`·`imagesUnsupported`)은 **그 모델·그 주소에 대한 사실**이라
     * 기기를 옮겨도 참이다. 싣는 편이 낫다 — 새 기기가 같은 400을 다시 겪으며 배우지 않는다.
     *
     * [AiProviderConfig.cooldownUntilMillis]만 다르다. 그것은 *"이 키가 **언제까지** 뒤로
     * 밀린다"*는 **벽시계 한 점**이라 파일이 옮기는 순간 뜻을 잃는다. 실측(2026.08.25 사용자
     * 파일): 내보낸 시점 기준 아직 지나지 않은 쿨다운 둘이 그대로 실려 있었고, 그 파일을
     * 곧바로 다른 기기에 들이면 **멀쩡한 프로바이더 둘이 잠긴 채로 시작한다** — 사용자는
     * 이유를 볼 길이 없다(그 화면은 "N분 뒤"만 말한다).
     */
    fun encodeForTransfer(configs: List<AiProviderConfig>): String =
        encode(configs.map(::forTransfer))

    /** 이식용으로 다듬은 한 항목 — 기기에 매인 칸을 비운다([encodeForTransfer]가 사유를 든다). */
    fun forTransfer(c: AiProviderConfig): AiProviderConfig = c.copy(cooldownUntilMillis = null)

    /**
     * 들여온 항목에 **이 기기의** 쿨다운을 도로 얹는다 — 파일은 그 칸을 말하지 않으므로
     * 없는 것을 "없음"으로 읽어 지우면 안 된다(옛 파일이 든 값도 여기서 함께 막힌다).
     *
     * [current]가 null이면(이 기기에 없던 프로바이더) 쿨다운도 없다 — 처음 보는 키를
     * 밀어 둘 근거가 없다.
     */
    fun keepDeviceState(incoming: AiProviderConfig, current: AiProviderConfig?): AiProviderConfig =
        incoming.copy(cooldownUntilMillis = current?.cooldownUntilMillis)

    fun decode(raw: String?): DecodeResult {
        if (raw == null) return DecodeResult(emptyList())
        val array = try {
            JsonParser.parseString(raw).asJsonArray
        } catch (e: Exception) {
            return DecodeResult(emptyList(), unreadable = true)
        }
        var skipped = 0
        val configs = array.mapNotNull { element ->
            try {
                fromJson(element.asJsonObject)
            } catch (e: Exception) {
                skipped++
                null
            }
        }
        // 정렬은 **우선순위 → 만든 순서**다 (B-108). 우선순위를 한 번도 손대지 않으면 전부 0이라
        // 종전과 글자 그대로 같은 순서가 나온다 — 그것이 이 칸에 마이그레이션이 없는 이유다.
        //
        // 규칙을 여기 다시 적지 않고 [AiProviderFallback.displayOrder]를 부르는 것이 요점이다 —
        // 화면의 줄 순서가 곧 전환 우선순위인데, 두 곳이 각자 정렬하면 **보여 주는 순서와 실제
        // 전환 순서가 갈리고** 그 어긋남은 한도에 걸리기 전까지 아무 데도 드러나지 않는다.
        return DecodeResult(AiProviderFallback.displayOrder(configs), skipped = skipped)
    }

    fun toJson(c: AiProviderConfig): JsonObject = JsonObject().apply {
        addProperty("id", c.id)
        addProperty("protocol", c.protocol.name)
        addProperty("displayName", c.displayName)
        addProperty("baseUrl", c.baseUrl)
        addProperty("model", c.model)
        c.presetId?.let { addProperty("presetId", it) }
        addProperty("createdAt", c.createdAt)
        addProperty("updatedAt", c.updatedAt)
        // null은 키 자체를 쓰지 않는다 — "미설정(자동)"과 "0으로 설정"은 다른 상태다.
        c.maxOutputTokens?.let { addProperty("maxOutputTokens", it) }
        // 우선순위는 **학습값이 아니라 사용자 설정**이다 (B-108) — 0도 뜻이 있는 값이라
        // null 생략 규칙을 쓰지 않고 언제나 적는다.
        addProperty("priority", c.priority)
        // ── 학습값(R-23) — 넷이 함께 쓰이고 함께 읽힌다.
        //    새 학습값을 더하면 여기·[fromJson]·`hasLearnedFacts()` 세 자리가 같이 는다.
        //    B-132는 그중 이 두 자리만 빠뜨린 사고였다.
        c.detectedOutputLimit?.let { addProperty("detectedOutputLimit", it) }
        c.temperatureUnsupported?.let { addProperty("temperatureUnsupported", it) }
        c.maxTokensParamUnsupported?.let { addProperty("maxTokensParamUnsupported", it) }
        c.imagesUnsupported?.let { addProperty("imagesUnsupported", it) }
        c.cooldownUntilMillis?.let { addProperty("cooldownUntilMillis", it) }
        // 단가는 학습값이 아니라 우선순위와 같은 사용자 설정이다 — 그래도 null 생략 규칙은
        // 그대로 따른다: "입력하지 않음"과 "0원으로 입력함"은 다른 상태다.
        c.inputPricePerMillionTokens?.let { addProperty("inputPricePerMillionTokens", it) }
        c.outputPricePerMillionTokens?.let { addProperty("outputPricePerMillionTokens", it) }
    }

    fun fromJson(o: JsonObject): AiProviderConfig = AiProviderConfig(
        id = o.get("id").asString,
        protocol = AiProtocol.valueOf(o.get("protocol").asString),
        displayName = o.get("displayName").asString,
        baseUrl = o.get("baseUrl").asString,
        model = o.get("model").asString,
        presetId = o.get("presetId")?.takeIf { it.isJsonPrimitive }?.asString,
        createdAt = o.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
        updatedAt = o.get("updatedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
        // 구버전 설정에는 이 키들이 없다 — 없으면 null(자동)이고 종전과 동일하게 동작한다.
        maxOutputTokens = o.get("maxOutputTokens")?.takeIf { it.isJsonPrimitive }?.asInt,
        // 우선순위가 없는 구버전은 전부 0 — 정렬이 createdAt으로 떨어져 옛 순서 그대로다.
        priority = o.get("priority")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
        detectedOutputLimit = o.get("detectedOutputLimit")?.takeIf { it.isJsonPrimitive }?.asInt,
        temperatureUnsupported = o.get("temperatureUnsupported")?.takeIf { it.isJsonPrimitive }?.asBoolean,
        maxTokensParamUnsupported = o.get("maxTokensParamUnsupported")?.takeIf { it.isJsonPrimitive }?.asBoolean,
        imagesUnsupported = o.get("imagesUnsupported")?.takeIf { it.isJsonPrimitive }?.asBoolean,
        cooldownUntilMillis = o.get("cooldownUntilMillis")?.takeIf { it.isJsonPrimitive }?.asLong,
        inputPricePerMillionTokens =
            o.get("inputPricePerMillionTokens")?.takeIf { it.isJsonPrimitive }?.asDouble,
        outputPricePerMillionTokens =
            o.get("outputPricePerMillionTokens")?.takeIf { it.isJsonPrimitive }?.asDouble
    )
}
