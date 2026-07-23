package com.novelcharacter.app.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 프로토콜별 요청 조립·응답 해석을 전담하는 **순수 함수 계층**(Android 의존성 없음 → JVM 단위
 * 테스트 가능). HTTP 실행은 [AiService]가 맡는다.
 *
 * Gson 트리 API만 사용한다 — 리플렉션 직렬화는 R8 난독화에서 필드명이 깨질 수 있어 금지.
 */
object AiProtocolCodec {

    const val HEADER_ANTHROPIC_VERSION = "2023-06-01"

    /** HTTP 요청 명세 — 실행기(OkHttp)로 넘기는 중간 표현. */
    data class HttpSpec(
        val url: String,
        val headers: Map<String, String>,
        val bodyJson: String
    )

    // ── 요청 조립 ──────────────────────────────────────────────────────────────

    fun buildRequest(config: AiProviderConfig, apiKey: String, request: AiRequest): HttpSpec =
        when (config.protocol) {
            AiProtocol.ANTHROPIC -> buildAnthropic(config, apiKey, request)
            AiProtocol.OPENAI_COMPAT -> buildOpenAiCompat(config, apiKey, request, useMaxCompletionTokens = false)
            AiProtocol.GEMINI -> buildGemini(config, apiKey, request)
        }

    /**
     * OpenAI 호환에서 400이 max_tokens 파라미터 문제를 지목하면 1회에 한해
     * max_completion_tokens 로 바꿔 재시도한다(신형 OpenAI 모델 대응 — 유연한 수용·교정).
     */
    fun buildOpenAiRetryWithMaxCompletionTokens(
        config: AiProviderConfig, apiKey: String, request: AiRequest
    ): HttpSpec = buildOpenAiCompat(config, apiKey, request, useMaxCompletionTokens = true)

    fun isMaxTokensParamError(httpCode: Int, errorBody: String?): Boolean =
        httpCode == 400 && errorBody != null && errorBody.contains("max_completion_tokens")

    private fun buildAnthropic(config: AiProviderConfig, apiKey: String, request: AiRequest): HttpSpec {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("max_tokens", request.maxTokens)
            request.system?.let { addProperty("system", it) }
            add("messages", JsonArray().apply {
                request.messages.forEach { m ->
                    add(JsonObject().apply {
                        addProperty("role", if (m.role == AiRole.USER) "user" else "assistant")
                        addProperty("content", m.text)
                    })
                }
            })
        }
        return HttpSpec(
            url = joinUrl(config.baseUrl, "/v1/messages"),
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to HEADER_ANTHROPIC_VERSION
            ),
            bodyJson = body.toString()
        )
    }

    private fun buildOpenAiCompat(
        config: AiProviderConfig, apiKey: String, request: AiRequest, useMaxCompletionTokens: Boolean
    ): HttpSpec {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            addProperty(if (useMaxCompletionTokens) "max_completion_tokens" else "max_tokens", request.maxTokens)
            add("messages", JsonArray().apply {
                request.system?.let {
                    add(JsonObject().apply { addProperty("role", "system"); addProperty("content", it) })
                }
                request.messages.forEach { m ->
                    add(JsonObject().apply {
                        addProperty("role", if (m.role == AiRole.USER) "user" else "assistant")
                        addProperty("content", m.text)
                    })
                }
            })
        }
        // base가 이미 /v1(또는 유사 버전 경로)로 끝나면 그대로 잇고, 아니면 /v1을 보충한다 —
        // 사용자가 어느 형태로 입력해도 동작(유연한 수용, 변수 제어).
        val base = config.baseUrl.trimEnd('/')
        val path = if (base.endsWith("/v1")) "/chat/completions" else "/v1/chat/completions"
        return HttpSpec(
            url = base + path,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            bodyJson = body.toString()
        )
    }

    private fun buildGemini(config: AiProviderConfig, apiKey: String, request: AiRequest): HttpSpec {
        val body = JsonObject().apply {
            request.system?.let {
                add("system_instruction", JsonObject().apply {
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", it) })
                    })
                })
            }
            add("contents", JsonArray().apply {
                request.messages.forEach { m ->
                    add(JsonObject().apply {
                        addProperty("role", if (m.role == AiRole.USER) "user" else "model")
                        add("parts", JsonArray().apply {
                            add(JsonObject().apply { addProperty("text", m.text) })
                        })
                    })
                }
            })
            add("generationConfig", JsonObject().apply {
                addProperty("maxOutputTokens", request.maxTokens)
            })
        }
        return HttpSpec(
            url = joinUrl(config.baseUrl, "/v1beta/models/${config.model}:generateContent"),
            // 키를 URL 쿼리가 아닌 헤더로 보내 로그·히스토리에 남지 않게 한다.
            headers = mapOf("x-goog-api-key" to apiKey),
            bodyJson = body.toString()
        )
    }

    private fun joinUrl(base: String, path: String): String = base.trimEnd('/') + path

    // ── 응답 해석 ──────────────────────────────────────────────────────────────

    /** 2xx 본문 → 결과. 텍스트가 비면 [AiErrorKind.EMPTY_RESPONSE]로 표면화한다(조용한 실패 금지). */
    fun parseSuccess(protocol: AiProtocol, body: String, requestedModel: String): AiResult {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            when (protocol) {
                AiProtocol.ANTHROPIC -> parseAnthropicSuccess(root, requestedModel)
                AiProtocol.OPENAI_COMPAT -> parseOpenAiSuccess(root, requestedModel)
                AiProtocol.GEMINI -> parseGeminiSuccess(root, requestedModel)
            }
        } catch (e: Exception) {
            AiResult.Failure(AiErrorKind.UNKNOWN, detail = "응답 해석 실패: ${e.message}")
        }
    }

    private fun parseAnthropicSuccess(root: JsonObject, requestedModel: String): AiResult {
        val text = root.getAsJsonArray("content")
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.get("type")?.asString == "text" }
            ?.joinToString("") { it.get("text")?.asString.orEmpty() }
            .orEmpty()
        if (text.isBlank()) {
            val stop = root.get("stop_reason")?.takeIf { it.isJsonPrimitive }?.asString
            return AiResult.Failure(AiErrorKind.EMPTY_RESPONSE, detail = stop?.let { "stop_reason=$it" })
        }
        val usage = root.getAsJsonObject("usage")
        return AiResult.Success(
            text = text,
            model = root.get("model")?.takeIf { it.isJsonPrimitive }?.asString ?: requestedModel,
            inputTokens = usage?.get("input_tokens")?.takeIf { it.isJsonPrimitive }?.asInt,
            outputTokens = usage?.get("output_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
        )
    }

    private fun parseOpenAiSuccess(root: JsonObject, requestedModel: String): AiResult {
        val message = root.getAsJsonArray("choices")
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull()
            ?.getAsJsonObject("message")
        val content = message?.get("content")
        val text = if (content != null && content.isJsonPrimitive) content.asString else ""
        if (text.isBlank()) return AiResult.Failure(AiErrorKind.EMPTY_RESPONSE)
        val usage = root.getAsJsonObject("usage")
        return AiResult.Success(
            text = text,
            model = root.get("model")?.takeIf { it.isJsonPrimitive }?.asString ?: requestedModel,
            inputTokens = usage?.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt,
            outputTokens = usage?.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
        )
    }

    private fun parseGeminiSuccess(root: JsonObject, requestedModel: String): AiResult {
        val candidate = root.getAsJsonArray("candidates")
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull()
        val text = candidate
            ?.getAsJsonObject("content")
            ?.getAsJsonArray("parts")
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { it.get("text")?.takeIf { t -> t.isJsonPrimitive }?.asString }
            ?.joinToString("")
            .orEmpty()
        if (text.isBlank()) {
            val finish = candidate?.get("finishReason")?.takeIf { it.isJsonPrimitive }?.asString
            return AiResult.Failure(AiErrorKind.EMPTY_RESPONSE, detail = finish?.let { "finishReason=$it" })
        }
        val usage = root.getAsJsonObject("usageMetadata")
        return AiResult.Success(
            text = text,
            model = requestedModel,
            inputTokens = usage?.get("promptTokenCount")?.takeIf { it.isJsonPrimitive }?.asInt,
            outputTokens = usage?.get("candidatesTokenCount")?.takeIf { it.isJsonPrimitive }?.asInt
        )
    }

    // ── 오류 해석 ──────────────────────────────────────────────────────────────

    /** 비 2xx 응답 → 분류 + 제공사 원문 메시지. */
    fun parseError(httpCode: Int, body: String?): AiResult.Failure {
        val detail = extractErrorMessage(body)
        val kind = when (httpCode) {
            401 -> AiErrorKind.INVALID_KEY
            403 -> AiErrorKind.INVALID_KEY
            402 -> AiErrorKind.QUOTA_EXCEEDED
            404 -> AiErrorKind.MODEL_NOT_FOUND
            408 -> AiErrorKind.TIMEOUT
            429 ->
                // OpenAI는 잔액 소진도 429로 돌려준다 — 안내가 달라야 하므로 구분(변수 제어).
                if (body?.contains("insufficient_quota") == true) AiErrorKind.QUOTA_EXCEEDED
                else AiErrorKind.RATE_LIMITED
            in 500..599 -> AiErrorKind.SERVER
            400 ->
                // Gemini는 잘못된 키를 400 INVALID_ARGUMENT("API key not valid")로 돌려준다.
                if (detail?.contains("API key not valid", ignoreCase = true) == true) AiErrorKind.INVALID_KEY
                else AiErrorKind.BAD_REQUEST
            else -> AiErrorKind.UNKNOWN
        }
        return AiResult.Failure(kind, detail = detail, httpCode = httpCode)
    }

    /** 3사 오류 봉투( {"error":{...}} / Anthropic {"error":{"message"}} ) 공통 해석. 실패 시 원문 일부. */
    private fun extractErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) return body.take(MAX_DETAIL)
            val error = root.asJsonObject.get("error")
            when {
                error == null -> body.take(MAX_DETAIL)
                error.isJsonObject -> error.asJsonObject.get("message")
                    ?.takeIf { it.isJsonPrimitive }?.asString?.take(MAX_DETAIL)
                    ?: error.toString().take(MAX_DETAIL)
                error.isJsonPrimitive -> error.asString.take(MAX_DETAIL)
                else -> body.take(MAX_DETAIL)
            }
        } catch (_: Exception) {
            body.take(MAX_DETAIL)
        }
    }

    private const val MAX_DETAIL = 300
}
