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

    /** HTTP 요청 명세 — 실행기(OkHttp)로 넘기는 중간 표현. GET은 bodyJson을 무시한다. */
    data class HttpSpec(
        val url: String,
        val headers: Map<String, String>,
        val bodyJson: String,
        val method: String = "POST"
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

    /**
     * 프로바이더가 지금 실제로 제공하는 모델 목록 조회(GET). 설정 화면의 '모델 선택'이
     * 앱에 박제된 하드코딩 추천값 대신 살아있는 목록을 보여주는 데 쓰인다(변수 제어).
     *
     * 페이지네이션은 구현하지 않는다 — 세 프로토콜 모두 한 페이지 상한을 넉넉히 잡으면
     * (1000) 현존하는 카탈로그 전체가 한 번에 들어온다. 그 이상 응답하는 프로바이더가
     * 나오면 그때 커서 기반 페이지네이션을 추가한다.
     */
    fun buildModelListRequest(config: AiProviderConfig, apiKey: String): HttpSpec =
        when (config.protocol) {
            AiProtocol.ANTHROPIC -> HttpSpec(
                url = joinUrl(config.baseUrl, "/v1/models?limit=1000"),
                headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to HEADER_ANTHROPIC_VERSION
                ),
                bodyJson = "",
                method = "GET"
            )
            AiProtocol.OPENAI_COMPAT -> HttpSpec(
                url = openAiPath(config.baseUrl, "/models"),
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                bodyJson = "",
                method = "GET"
            )
            AiProtocol.GEMINI -> HttpSpec(
                url = joinUrl(config.baseUrl, "/v1beta/models?pageSize=1000"),
                headers = mapOf("x-goog-api-key" to apiKey),
                bodyJson = "",
                method = "GET"
            )
        }

    fun isMaxTokensParamError(httpCode: Int, errorBody: String?): Boolean =
        httpCode == 400 && errorBody != null && errorBody.contains("max_completion_tokens")

    /**
     * 모델이 temperature 파라미터를 거부한 400인가 (A-4) — 일부 OpenAI 호환 추론 모델의
     * "Unsupported value: 'temperature' …" / "'temperature' is not supported …" 형태와
     * **Anthropic의 "`temperature` is deprecated for this model." 형태.**
     * 판정을 좁게 잡는다: 파라미터명과 거부 표현이 **함께** 있어야 참 — 무관한 400을
     * 온도 문제로 오인하면 잘못된 학습값(temperatureUnsupported)이 계속 남는다.
     *
     * **거부 표현 목록은 관찰로만 늘린다** (B-160, 2026.08.08 사용자 보고). 이 목록은
     * OpenAI 호환의 표현 넷으로 출발했는데, Anthropic이 *"deprecated"*라는 **다섯째 낱말**로
     * 거부하면서 판정이 false를 냈다. 그러면 아래 자동 교정이 통째로 안 돌고 400이 그대로
     * 사용자에게 나가며, 하필 그 문구가 *"모델명과 서버 주소를 확인해 주세요"*라 **멀쩡한
     * 곳을 고치라고 시킨다.** 새 표현을 만나면 여기 더할 것 — 다만 **짐작으로 넓히지 않는다**
     * (넓힐수록 오탐이 늘고, 오탐 하나가 그 모델에서 창작도를 영영 죽인다).
     */
    fun isTemperatureUnsupportedError(httpCode: Int, errorBody: String?): Boolean {
        if (httpCode != 400 || errorBody == null) return false
        val lower = errorBody.lowercase()
        if ("temperature" !in lower) return false
        return listOf("unsupported", "not supported", "does not support", "not allowed", "deprecated")
            .any { it in lower }
    }

    /**
     * 모델이 **이미지를 받지 않는다**고 말하는 400인가 (A-7).
     *
     * temperature 판정과 같은 태도로 좁게 잡는다: 이미지를 가리키는 낱말과 거부 표현이
     * **함께** 있어야 참이다. 무관한 400을 이미지 문제로 오인하면 잘못된 학습값이 남아
     * **그 모델에는 다시는 이미지를 보내지 않게 되고**, 사용자는 이유를 볼 수 없다.
     *
     * 세 프로토콜의 실제 표현이 제각각이라 낱말 쪽을 넓게 연다 — Anthropic은
     * `messages.0.content.0.image: ... does not support image`, OpenAI 호환은
     * `Invalid content type. image_url is only supported by certain models`,
     * Gemini는 `inline_data ... is not supported`로 온다.
     */
    fun isImagesUnsupportedError(httpCode: Int, errorBody: String?): Boolean {
        if (httpCode != 400 || errorBody == null) return false
        val lower = errorBody.lowercase()
        val mentionsImage = listOf("image", "image_url", "inline_data", "vision", "multimodal")
            .any { it in lower }
        if (!mentionsImage) return false
        return listOf(
            "unsupported", "not supported", "does not support", "only supported",
            "not allowed", "invalid content type", "cannot process"
        ).any { it in lower }
    }

    /**
     * 400이 **요청의 어떤 항목을 지목해** 거부했는가 (B-161).
     *
     * ## 왜 있는가 — 400의 일반 문구가 멀쩡한 곳을 고치라고 시킨다
     *
     * `BAD_REQUEST`의 안내는 *"모델명과 서버 주소를 확인해 주세요"*인데, 그것은 **모델명·주소가
     * 원인인 400에서만 참**이다. 본문이 파라미터를 지목하는 400에서는 거짓이고, 사용자는
     * 멀쩡한 두 칸을 들여다보며 시간을 쓴다(2026.08.08 화면 캡처로 확인된 실제 보고).
     *
     * 형제 둘([isTemperatureUnsupportedError]·[isImagesUnsupportedError])이 잡는 것은
     * **자동 교정 대상**이라 화면에 닿지 않는다. 여기가 맡는 것은 **교정 목록에 없는 나머지** —
     * 그쪽은 종전에 그대로 저 문구를 받았다. 저장소가 같은 판단을 한 선례가 있다:
     * `BatchFailKind.RESPONSE_TRUNCATED`(*"뭉뚱그리면 사용자가 엉뚱한 곳을 고치려 든다"*).
     *
     * ## 판정을 좁게 잡는다 — 형제 둘과 같은 두 겹 구조
     *
     * **항목을 지목하는 표현**과 **거부 표현**이 *함께* 있어야 참이다. 한 겹만 보면 무관한
     * 400이 휩쓸려 들어오고, 그러면 이 분류가 `BAD_REQUEST`보다 나을 것이 없다.
     *
     * **낱말 목록은 관찰된 표현에서만 늘린다** — 형제 둘의 KDoc이 규약으로 박아 둔 그대로다.
     * 지목 표현 넷은 세 프로토콜에서 실제로 관찰된 모양이다: OpenAI의
     * `Unsupported value: 'temperature' …` / `Unrecognized request argument supplied: …` /
     * `Unknown parameter: 'foo'.`, Anthropic의 `` `temperature` is deprecated for this model. ``
     * **짐작으로 넓히지 않는다** — 넓힐수록 오탐이 늘고, 오탐은 남은 청크를 통째로 접는다(아래).
     *
     * ## 종단 실패로 세는 이유
     *
     * [AiErrorPolicy.TERMINAL]의 기준은 *"다시 보내는 것으로는 절대 풀리지 않는다"*이고,
     * 같은 본문을 다시 보내면 같은 항목이 같은 이유로 거부된다 — 남은 청크는 **돈만 쓴다.**
     */
    fun isParameterRejectedError(httpCode: Int, errorBody: String?): Boolean {
        if (httpCode != 400 || errorBody == null) return false
        val lower = errorBody.lowercase()
        val pointsAtParam = "parameter" in lower ||
            "argument" in lower ||
            "unsupported value" in lower ||
            BACKTICKED_NAME_RE.containsMatchIn(lower)
        if (!pointsAtParam) return false
        return listOf(
            "unsupported", "not supported", "does not support",
            "not allowed", "deprecated", "unrecognized", "unknown", "invalid"
        ).any { it in lower }
    }

    /** Anthropic이 항목을 지목하는 모양 — `` `temperature` is deprecated … ``. */
    private val BACKTICKED_NAME_RE = Regex("`[a-z_][a-z0-9_.]*`\\s+is\\s")

    private fun buildAnthropic(config: AiProviderConfig, apiKey: String, request: AiRequest): HttpSpec {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("max_tokens", request.maxTokens)
            // null이면 키 자체를 싣지 않는다 — 프로바이더 기본값 유지 (A-4 창작도 '균형')
            request.temperature?.let { addProperty("temperature", it) }
            // `system`이 아니라 `effectiveSystem()`이다 — 이미지 절은 이미지가 실릴 때만
            // 붙는다 (B-139. 세 프로토콜 공통이며 `check_ai_image_rule.sh`가 잠근다).
            request.effectiveSystem()?.let { addProperty("system", it) }
            add("messages", JsonArray().apply {
                request.messages.forEach { m ->
                    add(JsonObject().apply {
                        addProperty("role", if (m.role == AiRole.USER) "user" else "assistant")
                        if (m.images.isEmpty()) {
                            // 이미지가 없으면 종전과 **글자 그대로 같은** 문자열 content다 (회귀 없음)
                            addProperty("content", m.text)
                        } else {
                            add("content", JsonArray().apply {
                                m.images.forEach { img ->
                                    add(JsonObject().apply {
                                        addProperty("type", "image")
                                        add("source", JsonObject().apply {
                                            addProperty("type", "base64")
                                            addProperty("media_type", img.mediaType)
                                            addProperty("data", img.base64)
                                        })
                                    })
                                }
                                add(JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", m.text)
                                })
                            })
                        }
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
            request.temperature?.let { addProperty("temperature", it) }
            add("messages", JsonArray().apply {
                request.effectiveSystem()?.let {
                    add(JsonObject().apply { addProperty("role", "system"); addProperty("content", it) })
                }
                request.messages.forEach { m ->
                    add(JsonObject().apply {
                        addProperty("role", if (m.role == AiRole.USER) "user" else "assistant")
                        if (m.images.isEmpty()) {
                            addProperty("content", m.text)
                        } else {
                            add("content", JsonArray().apply {
                                m.images.forEach { img ->
                                    add(JsonObject().apply {
                                        addProperty("type", "image_url")
                                        add("image_url", JsonObject().apply {
                                            addProperty("url", dataUri(img))
                                        })
                                    })
                                }
                                add(JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", m.text)
                                })
                            })
                        }
                    })
                }
            })
        }
        return HttpSpec(
            url = openAiPath(config.baseUrl, "/chat/completions"),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            bodyJson = body.toString()
        )
    }

    /**
     * OpenAI 호환 base 위에 경로를 잇는다. base가 이미 /v1(또는 유사 버전 경로)로 끝나면
     * 그대로 잇고, 아니면 /v1을 보충한다 — 사용자가 어느 형태로 입력해도 동작(유연한 수용).
     */
    private fun openAiPath(baseUrl: String, suffix: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1")) base + suffix else "$base/v1$suffix"
    }

    private fun buildGemini(config: AiProviderConfig, apiKey: String, request: AiRequest): HttpSpec {
        val body = JsonObject().apply {
            request.effectiveSystem()?.let {
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
                            m.images.forEach { img ->
                                add(JsonObject().apply {
                                    add("inline_data", JsonObject().apply {
                                        addProperty("mime_type", img.mediaType)
                                        addProperty("data", img.base64)
                                    })
                                })
                            }
                            add(JsonObject().apply { addProperty("text", m.text) })
                        })
                    })
                }
            })
            add("generationConfig", JsonObject().apply {
                addProperty("maxOutputTokens", request.maxTokens)
                request.temperature?.let { addProperty("temperature", it) }
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

    /**
     * OpenAI 호환이 이미지를 받는 형태 — `data:` URI. 원격 URL은 쓰지 않는다:
     * 앱의 이미지는 기기 안 파일이라 모델이 가져갈 주소가 없고, 만들려면 어딘가에
     * 올려야 하는데 그것은 보안 경계를 넘는다(계약: 키·데이터는 제공사 외 어디로도 안 간다).
     */
    private fun dataUri(image: AiImage): String = "data:${image.mediaType};base64,${image.base64}"

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
            outputTokens = usage?.get("output_tokens")?.takeIf { it.isJsonPrimitive }?.asInt,
            // 텍스트가 **있어도** 잘렸을 수 있다 — 이 신호를 빈 응답일 때만 읽던 것이 오진의 원인이었다.
            truncated = root.get("stop_reason")?.takeIf { it.isJsonPrimitive }?.asString == "max_tokens"
        )
    }

    private fun parseOpenAiSuccess(root: JsonObject, requestedModel: String): AiResult {
        val choice = root.getAsJsonArray("choices")
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull()
        val message = choice?.getAsJsonObject("message")
        val content = message?.get("content")
        val text = if (content != null && content.isJsonPrimitive) content.asString else ""
        if (text.isBlank()) {
            val finish = choice?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString
            return AiResult.Failure(AiErrorKind.EMPTY_RESPONSE, detail = finish?.let { "finish_reason=$it" })
        }
        val usage = root.getAsJsonObject("usage")
        return AiResult.Success(
            text = text,
            model = root.get("model")?.takeIf { it.isJsonPrimitive }?.asString ?: requestedModel,
            inputTokens = usage?.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt,
            outputTokens = usage?.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt,
            truncated = choice?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString == "length"
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
            outputTokens = usage?.get("candidatesTokenCount")?.takeIf { it.isJsonPrimitive }?.asInt,
            truncated = candidate?.get("finishReason")?.takeIf { it.isJsonPrimitive }?.asString == "MAX_TOKENS"
        )
    }

    /**
     * 모델 목록 응답 → id 문자열 목록. 해석 실패나 예상 밖 형태는 예외를 던지지 않고
     * 빈 목록으로 돌아온다 — 호출측(AiService)이 빈 목록을 EMPTY_RESPONSE로 승격해
     * 정적 추천값 폴백을 트리거한다.
     */
    fun parseModelList(protocol: AiProtocol, body: String): List<String> =
        parseModelInfos(protocol, body).map { it.id }

    /**
     * 모델 목록 응답 → [AiModelInfo] 목록(id + 알려진 출력 상한).
     *
     * Gemini는 같은 응답에 `outputTokenLimit`을 실어 보내는데 종전 파서가 id만 뽑고 버렸다 —
     * 모델별 상한을 **조회로 알 수 있는 유일한 프로토콜**이므로 되살린다.
     * Anthropic·OpenAI 목록에는 상한이 없어 null이고, 그쪽은 상한 초과 오류에서 학습한다
     * ([parseMaxTokensLimitFromError]).
     */
    fun parseModelInfos(protocol: AiProtocol, body: String): List<AiModelInfo> = try {
        val root = JsonParser.parseString(body).asJsonObject
        when (protocol) {
            // Anthropic은 최신순으로 내려주므로 정렬하지 않고 그대로 보존한다.
            AiProtocol.ANTHROPIC -> root.getAsJsonArray("data")
                ?.filterIsInstance<JsonObject>()
                ?.mapNotNull { it.get("id")?.takeIf { v -> v.isJsonPrimitive }?.asString }
                ?.map { AiModelInfo(it) }
                .orEmpty()

            AiProtocol.OPENAI_COMPAT -> root.getAsJsonArray("data")
                ?.filterIsInstance<JsonObject>()
                ?.mapNotNull { it.get("id")?.takeIf { v -> v.isJsonPrimitive }?.asString }
                ?.sorted()
                ?.map { AiModelInfo(it) }
                .orEmpty()

            // generateContent를 지원하지 않는 모델(임베딩 전용 등)은 채팅 용도가 아니므로 제외.
            AiProtocol.GEMINI -> root.getAsJsonArray("models")
                ?.filterIsInstance<JsonObject>()
                ?.filter { m ->
                    m.getAsJsonArray("supportedGenerationMethods")
                        ?.any { it.isJsonPrimitive && it.asString == "generateContent" } == true
                }
                ?.mapNotNull { m ->
                    val id = m.get("name")?.takeIf { v -> v.isJsonPrimitive }?.asString
                        ?.removePrefix("models/") ?: return@mapNotNull null
                    val limit = m.get("outputTokenLimit")?.takeIf { it.isJsonPrimitive }
                        ?.runCatching { asInt }?.getOrNull()?.takeIf { it > 0 }
                    AiModelInfo(id, limit)
                }
                ?.sortedBy { it.id }
                .orEmpty()
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 목록에서 [model]의 출력 상한을 찾는다. 못 찾거나 알려지지 않았으면 null(기존 탐지값 유지). */
    fun detectedLimitFor(models: List<AiModelInfo>, model: String): Int? =
        models.firstOrNull { it.id == model }?.outputTokenLimit

    /**
     * 상한 **초과** 오류에서 그 모델이 실제로 허용하는 값을 읽어낸다 — 오류가 정답을 알려주는
     * 경우가 많다(예: Anthropic `max_tokens: 100000 > 8192, which is the maximum allowed
     * number of output tokens for ...`, OpenAI `max_tokens is too large: ... supports at most 16384`).
     *
     * **정적 표를 두지 않는 이유가 이것이다** — 표는 새 모델이 나올 때마다 낡지만 오류는 늘 최신이다.
     *
     * 오판 방지(잘못된 상한을 학습하면 이후 모든 요청이 좁아진다):
     * - [requested]보다 **작은** 값만 후보다. 초과 오류이므로 정답은 반드시 요청값보다 작다.
     * - [AiTokenPolicy.FLOOR] 미만은 버린다.
     * - 후보가 여럿이면 **가장 큰 것**을 고른다(모델 이름의 숫자 등 잡음은 대개 더 작거나 걸러진다).
     * - 확신할 수 없으면 null — 그러면 재시도하지 않고 오류를 그대로 사용자에게 보고한다.
     */
    fun parseMaxTokensLimitFromError(body: String?, requested: Int): Int? {
        val text = extractErrorMessage(body) ?: body ?: return null
        val lower = text.lowercase()
        // 상한 초과를 말하는 오류인지 먼저 확인 — 다른 400을 상한 문제로 오인하지 않는다.
        val looksLikeLimit = listOf(
            "maximum allowed", "at most", "too large", "must be less than",
            "maximum number of output tokens", "exceeds"
        ).any { it in lower }
        if (!looksLikeLimit) return null
        val candidates = NUMBER_RE.findAll(text)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in AiTokenPolicy.FLOOR until requested }
            .toList()
        return candidates.maxOrNull()
    }

    private val NUMBER_RE = Regex("""\d{3,7}""")

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
                // **이 갈래가 먼저다** — 그 본문에도 `argument`가 들어 있어 순서를 바꾸면
                // 키 문제가 파라미터 문제로 읽히고, 고칠 곳을 다시 놓친다.
                if (detail?.contains("API key not valid", ignoreCase = true) == true) AiErrorKind.INVALID_KEY
                // 본문이 요청 항목을 지목하면 모델명·주소를 확인하라는 일반 문구는 거짓이다 (B-161).
                else if (isParameterRejectedError(httpCode, detail)) AiErrorKind.UNSUPPORTED_PARAM
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
