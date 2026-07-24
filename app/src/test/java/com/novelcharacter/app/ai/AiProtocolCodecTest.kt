package com.novelcharacter.app.ai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프로토콜 조립·해석 회귀 가드 — 3개 프로토콜의 요청 형식, URL 결합 규칙,
 * 성공/오류 응답 해석, 오류 분류를 검증한다. (SDK 불필요, 순수 JVM)
 */
class AiProtocolCodecTest {

    private fun config(
        protocol: AiProtocol,
        baseUrl: String,
        model: String = "test-model"
    ) = AiProviderConfig(
        id = "cfg1", protocol = protocol, displayName = "테스트",
        baseUrl = baseUrl, model = model
    )

    private val simpleRequest = AiRequest(system = "너는 조수다", userText = "안녕", maxTokens = 512)

    // ── 요청 조립: Anthropic ──────────────────────────────────────────────────

    @Test
    fun `anthropic 요청은 messages 엔드포인트와 필수 헤더를 갖춘다`() {
        val spec = AiProtocolCodec.buildRequest(
            config(AiProtocol.ANTHROPIC, "https://api.anthropic.com"), "sk-ant-x", simpleRequest
        )
        assertEquals("https://api.anthropic.com/v1/messages", spec.url)
        assertEquals("sk-ant-x", spec.headers["x-api-key"])
        assertEquals(AiProtocolCodec.HEADER_ANTHROPIC_VERSION, spec.headers["anthropic-version"])

        val body = JsonParser.parseString(spec.bodyJson).asJsonObject
        assertEquals("test-model", body.get("model").asString)
        assertEquals(512, body.get("max_tokens").asInt)
        assertEquals("너는 조수다", body.get("system").asString)
        val messages = body.getAsJsonArray("messages")
        assertEquals(1, messages.size())
        assertEquals("user", messages[0].asJsonObject.get("role").asString)
        assertEquals("안녕", messages[0].asJsonObject.get("content").asString)
    }

    @Test
    fun `anthropic 시스템 없으면 system 필드 생략`() {
        val spec = AiProtocolCodec.buildRequest(
            config(AiProtocol.ANTHROPIC, "https://api.anthropic.com"), "k",
            AiRequest(userText = "hi")
        )
        val body = JsonParser.parseString(spec.bodyJson).asJsonObject
        assertFalse(body.has("system"))
    }

    // ── 요청 조립: OpenAI 호환 ────────────────────────────────────────────────

    @Test
    fun `openai 호환은 base가 v1로 끝나면 중복 없이 잇는다`() {
        val spec = AiProtocolCodec.buildRequest(
            config(AiProtocol.OPENAI_COMPAT, "https://api.openai.com/v1"), "sk-x", simpleRequest
        )
        assertEquals("https://api.openai.com/v1/chat/completions", spec.url)
        assertEquals("Bearer sk-x", spec.headers["Authorization"])
    }

    @Test
    fun `openai 호환은 base에 v1이 없으면 보충한다`() {
        val spec = AiProtocolCodec.buildRequest(
            config(AiProtocol.OPENAI_COMPAT, "https://api.example.com/"), "k", simpleRequest
        )
        assertEquals("https://api.example.com/v1/chat/completions", spec.url)
    }

    @Test
    fun `openai 호환 system은 messages 첫 항목으로 들어간다`() {
        val spec = AiProtocolCodec.buildRequest(
            config(AiProtocol.OPENAI_COMPAT, "https://api.openai.com/v1"), "k", simpleRequest
        )
        val messages = JsonParser.parseString(spec.bodyJson).asJsonObject.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        assertEquals("system", messages[0].asJsonObject.get("role").asString)
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
        assertTrue(JsonParser.parseString(spec.bodyJson).asJsonObject.has("max_tokens"))
    }

    @Test
    fun `openai 재시도 조립은 max_completion_tokens를 사용한다`() {
        val spec = AiProtocolCodec.buildOpenAiRetryWithMaxCompletionTokens(
            config(AiProtocol.OPENAI_COMPAT, "https://api.openai.com/v1"), "k", simpleRequest
        )
        val body = JsonParser.parseString(spec.bodyJson).asJsonObject
        assertTrue(body.has("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
    }

    @Test
    fun `max_tokens 파라미터 오류 판정은 400 본문 기준`() {
        val body = """{"error":{"message":"Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead."}}"""
        assertTrue(AiProtocolCodec.isMaxTokensParamError(400, body))
        assertFalse(AiProtocolCodec.isMaxTokensParamError(401, body))
        assertFalse(AiProtocolCodec.isMaxTokensParamError(400, """{"error":{"message":"other"}}"""))
    }

    // ── 요청 조립: Gemini ─────────────────────────────────────────────────────

    @Test
    fun `gemini 요청은 모델 경로와 키 헤더, role 변환을 갖춘다`() {
        val spec = AiProtocolCodec.buildRequest(
            config(AiProtocol.GEMINI, "https://generativelanguage.googleapis.com", "gemini-2.5-flash"),
            "AIza-x",
            AiRequest(
                system = "sys",
                messages = listOf(
                    AiMessage(AiRole.USER, "질문"),
                    AiMessage(AiRole.ASSISTANT, "답"),
                    AiMessage(AiRole.USER, "재질문")
                ),
                maxTokens = 512
            )
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            spec.url
        )
        assertEquals("AIza-x", spec.headers["x-goog-api-key"])
        val body = JsonParser.parseString(spec.bodyJson).asJsonObject
        assertTrue(body.has("system_instruction"))
        val contents = body.getAsJsonArray("contents")
        assertEquals("user", contents[0].asJsonObject.get("role").asString)
        // Gemini의 assistant 역할명은 "model"
        assertEquals("model", contents[1].asJsonObject.get("role").asString)
        assertEquals(
            512,
            body.getAsJsonObject("generationConfig").get("maxOutputTokens").asInt
        )
    }

    // ── 성공 응답 해석 ────────────────────────────────────────────────────────

    @Test
    fun `anthropic 성공 응답에서 텍스트 블록을 이어붙인다`() {
        val body = """
            {"model":"claude-x","stop_reason":"end_turn",
             "content":[{"type":"text","text":"안녕"},{"type":"text","text":"하세요"}],
             "usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent()
        val result = AiProtocolCodec.parseSuccess(AiProtocol.ANTHROPIC, body, "req-model")
        result as AiResult.Success
        assertEquals("안녕하세요", result.text)
        assertEquals("claude-x", result.model)
        assertEquals(10, result.inputTokens)
        assertEquals(5, result.outputTokens)
    }

    @Test
    fun `anthropic 빈 content는 EMPTY_RESPONSE로 표면화한다`() {
        val body = """{"model":"m","stop_reason":"refusal","content":[]}"""
        val result = AiProtocolCodec.parseSuccess(AiProtocol.ANTHROPIC, body, "m")
        result as AiResult.Failure
        assertEquals(AiErrorKind.EMPTY_RESPONSE, result.kind)
        assertTrue(result.detail!!.contains("refusal"))
    }

    @Test
    fun `openai 성공 응답 해석`() {
        val body = """
            {"model":"gpt-x",
             "choices":[{"message":{"role":"assistant","content":"응답"}}],
             "usage":{"prompt_tokens":7,"completion_tokens":3}}
        """.trimIndent()
        val result = AiProtocolCodec.parseSuccess(AiProtocol.OPENAI_COMPAT, body, "req")
        result as AiResult.Success
        assertEquals("응답", result.text)
        assertEquals("gpt-x", result.model)
        assertEquals(7, result.inputTokens)
        assertEquals(3, result.outputTokens)
    }

    @Test
    fun `openai content가 null이면 EMPTY_RESPONSE`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":null}}]}"""
        val result = AiProtocolCodec.parseSuccess(AiProtocol.OPENAI_COMPAT, body, "m")
        assertEquals(AiErrorKind.EMPTY_RESPONSE, (result as AiResult.Failure).kind)
    }

    @Test
    fun `gemini 성공 응답 해석`() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"파"},{"text":"트"}],"role":"model"},
              "finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2}}
        """.trimIndent()
        val result = AiProtocolCodec.parseSuccess(AiProtocol.GEMINI, body, "gemini-m")
        result as AiResult.Success
        assertEquals("파트", result.text)
        assertEquals("gemini-m", result.model)
        assertEquals(4, result.inputTokens)
        assertEquals(2, result.outputTokens)
    }

    @Test
    fun `gemini 텍스트 없는 candidate는 finishReason과 함께 EMPTY_RESPONSE`() {
        val body = """{"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}"""
        val result = AiProtocolCodec.parseSuccess(AiProtocol.GEMINI, body, "m")
        result as AiResult.Failure
        assertEquals(AiErrorKind.EMPTY_RESPONSE, result.kind)
        assertTrue(result.detail!!.contains("SAFETY"))
    }

    @Test
    fun `깨진 JSON 본문도 예외 없이 UNKNOWN 실패로 돌아온다`() {
        val result = AiProtocolCodec.parseSuccess(AiProtocol.ANTHROPIC, "not json", "m")
        assertEquals(AiErrorKind.UNKNOWN, (result as AiResult.Failure).kind)
    }

    // ── 오류 분류 ────────────────────────────────────────────────────────────

    @Test
    fun `HTTP 코드별 오류 분류`() {
        assertEquals(AiErrorKind.INVALID_KEY, AiProtocolCodec.parseError(401, null).kind)
        assertEquals(AiErrorKind.INVALID_KEY, AiProtocolCodec.parseError(403, null).kind)
        assertEquals(AiErrorKind.QUOTA_EXCEEDED, AiProtocolCodec.parseError(402, null).kind)
        assertEquals(AiErrorKind.MODEL_NOT_FOUND, AiProtocolCodec.parseError(404, null).kind)
        assertEquals(AiErrorKind.TIMEOUT, AiProtocolCodec.parseError(408, null).kind)
        assertEquals(AiErrorKind.RATE_LIMITED, AiProtocolCodec.parseError(429, null).kind)
        assertEquals(AiErrorKind.SERVER, AiProtocolCodec.parseError(500, null).kind)
        assertEquals(AiErrorKind.SERVER, AiProtocolCodec.parseError(529, null).kind)
        assertEquals(AiErrorKind.BAD_REQUEST, AiProtocolCodec.parseError(400, null).kind)
        assertEquals(AiErrorKind.UNKNOWN, AiProtocolCodec.parseError(418, null).kind)
    }

    @Test
    fun `openai 잔액 소진 429는 QUOTA로 구분한다`() {
        val body = """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}"""
        assertEquals(AiErrorKind.QUOTA_EXCEEDED, AiProtocolCodec.parseError(429, body).kind)
    }

    @Test
    fun `gemini 잘못된 키 400은 INVALID_KEY로 구분한다`() {
        val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        val result = AiProtocolCodec.parseError(400, body)
        assertEquals(AiErrorKind.INVALID_KEY, result.kind)
    }

    @Test
    fun `오류 봉투에서 message를 추출해 detail로 담는다`() {
        val body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        val result = AiProtocolCodec.parseError(401, body)
        assertEquals("invalid x-api-key", result.detail)
        assertEquals(401, result.httpCode)
    }

    @Test
    fun `봉투가 아닌 오류 본문은 원문 일부를 detail로 담는다`() {
        val result = AiProtocolCodec.parseError(500, "Internal Server Error")
        assertEquals("Internal Server Error", result.detail)
    }

    @Test
    fun `빈 오류 본문은 detail 없이 분류만 한다`() {
        assertNull(AiProtocolCodec.parseError(500, null).detail)
        assertNull(AiProtocolCodec.parseError(500, "").detail)
    }

    // ── 모델 목록 조회 요청 조립 ──────────────────────────────────────────────

    @Test
    fun `anthropic 모델목록 요청은 GET이며 인증 헤더를 갖춘다`() {
        val spec = AiProtocolCodec.buildModelListRequest(
            config(AiProtocol.ANTHROPIC, "https://api.anthropic.com"), "sk-ant-x"
        )
        assertEquals("GET", spec.method)
        assertEquals("https://api.anthropic.com/v1/models?limit=1000", spec.url)
        assertEquals("sk-ant-x", spec.headers["x-api-key"])
        assertEquals(AiProtocolCodec.HEADER_ANTHROPIC_VERSION, spec.headers["anthropic-version"])
    }

    @Test
    fun `openai 호환 모델목록 요청은 v1 경로 중복 없이 잇는다`() {
        val withV1 = AiProtocolCodec.buildModelListRequest(
            config(AiProtocol.OPENAI_COMPAT, "https://openrouter.ai/api/v1"), "k"
        )
        assertEquals("https://openrouter.ai/api/v1/models", withV1.url)
        assertEquals("GET", withV1.method)
        assertEquals("Bearer k", withV1.headers["Authorization"])

        val withoutV1 = AiProtocolCodec.buildModelListRequest(
            config(AiProtocol.OPENAI_COMPAT, "https://api.example.com"), "k"
        )
        assertEquals("https://api.example.com/v1/models", withoutV1.url)
    }

    @Test
    fun `gemini 모델목록 요청은 v1beta 경로와 키 헤더를 갖춘다`() {
        val spec = AiProtocolCodec.buildModelListRequest(
            config(AiProtocol.GEMINI, "https://generativelanguage.googleapis.com"), "AIza-x"
        )
        assertEquals("GET", spec.method)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", spec.url
        )
        assertEquals("AIza-x", spec.headers["x-goog-api-key"])
    }

    // ── 모델 목록 응답 해석 ──────────────────────────────────────────────────

    @Test
    fun `anthropic 모델목록은 순서를 보존한다`() {
        val body = """
            {"data":[{"id":"claude-opus-4-8"},{"id":"claude-sonnet-5"},{"id":"claude-haiku-4-5"}]}
        """.trimIndent()
        val models = AiProtocolCodec.parseModelList(AiProtocol.ANTHROPIC, body)
        assertEquals(listOf("claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5"), models)
    }

    @Test
    fun `openai 호환 모델목록은 알파벳 정렬한다`() {
        val body = """{"object":"list","data":[{"id":"zeta"},{"id":"alpha"},{"id":"beta"}]}"""
        val models = AiProtocolCodec.parseModelList(AiProtocol.OPENAI_COMPAT, body)
        assertEquals(listOf("alpha", "beta", "zeta"), models)
    }

    @Test
    fun `gemini 모델목록은 generateContent 지원 모델만, models 접두사를 제거해 정렬한다`() {
        val body = """
            {"models":[
              {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/embedding-001","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent","countTokens"]}
            ]}
        """.trimIndent()
        val models = AiProtocolCodec.parseModelList(AiProtocol.GEMINI, body)
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), models)
    }

    @Test
    fun `모델목록 응답이 깨져 있으면 빈 목록을 돌려준다(예외 없음)`() {
        assertTrue(AiProtocolCodec.parseModelList(AiProtocol.ANTHROPIC, "not json").isEmpty())
        assertTrue(AiProtocolCodec.parseModelList(AiProtocol.OPENAI_COMPAT, "{}").isEmpty())
        assertTrue(AiProtocolCodec.parseModelList(AiProtocol.GEMINI, "{}").isEmpty())
    }
}
