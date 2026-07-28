package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-4 창작도 — 단계→온도 매핑(프로토콜별 상한), 지시 문구, temperature 직렬화(null=키 없음),
 * temperature 거부 오류 판정. 핵심 계약: '균형'은 현행과 완전히 동일해야 한다(회귀 없음).
 */
class AiCreativityTest {

    private fun config(protocol: AiProtocol) = AiProviderConfig(
        id = "t", protocol = protocol, displayName = "테스트",
        baseUrl = "https://api.example.com", model = "test-model"
    )

    // ===== 단계 → 온도 =====

    @Test
    fun balanced_sendsNoTemperature_everyProtocol() {
        for (protocol in AiProtocol.entries) {
            assertNull(AiCreativity.BALANCED.temperatureFor(protocol))
        }
    }

    @Test
    fun temperatureScalesWithProtocolCeiling() {
        // Anthropic 상한 1.0
        assertEquals(0.1, AiCreativity.PRECISE.temperatureFor(AiProtocol.ANTHROPIC)!!, 1e-9)
        assertEquals(0.85, AiCreativity.FREE.temperatureFor(AiProtocol.ANTHROPIC)!!, 1e-9)
        assertEquals(1.0, AiCreativity.BOLD.temperatureFor(AiProtocol.ANTHROPIC)!!, 1e-9)
        // OpenAI 호환·Gemini 상한 1.4 — 규격 최대(2.0)가 아니라 형식이 무너지지 않는 상한
        for (p in listOf(AiProtocol.OPENAI_COMPAT, AiProtocol.GEMINI)) {
            assertEquals(0.14, AiCreativity.PRECISE.temperatureFor(p)!!, 1e-9)
            assertEquals(1.19, AiCreativity.FREE.temperatureFor(p)!!, 1e-9)
            assertEquals(1.4, AiCreativity.BOLD.temperatureFor(p)!!, 1e-9)
        }
    }

    @Test
    fun narrowTopRange_onlyWhereCeilingIsOne() {
        assertTrue(AiCreativity.narrowTopRange(AiProtocol.ANTHROPIC))
        assertFalse(AiCreativity.narrowTopRange(AiProtocol.OPENAI_COMPAT))
        assertFalse(AiCreativity.narrowTopRange(AiProtocol.GEMINI))
    }

    @Test
    fun fromWire_unknownFallsBackToDefault() {
        assertEquals(AiCreativity.BALANCED, AiCreativity.fromWire(null))
        assertEquals(AiCreativity.BALANCED, AiCreativity.fromWire("garbage"))
        assertEquals(AiCreativity.BOLD, AiCreativity.fromWire("bold"))
    }

    // ===== 단계별 지시 문구 =====

    @Test
    fun promptRules_existExceptBalanced() {
        assertEquals("", AiCreativity.BALANCED.promptRule())
        assertTrue(AiCreativity.PRECISE.promptRule().contains("직접 도출"))
        assertTrue(AiCreativity.FREE.promptRule().contains("울타리가 아니다"))
        assertTrue(AiCreativity.BOLD.promptRule().contains("뻔한 답"))
        // restricted 허용 목록은 어느 단계에서도 완화하지 않는다 (§6-6)
        assertTrue(AiCreativity.FREE.promptRule().contains("이 목록의 값만 허용"))
        assertTrue(AiCreativity.BOLD.promptRule().contains("이 목록의 값만 허용"))
    }

    @Test
    fun systemPrompts_appendCreativityTail() {
        val short = CharacterFieldAiSuggester.buildSystemPrompt(creativity = AiCreativity.BOLD)
        assertTrue(short.contains("[창작도: 실험]"))
        // 균형은 현행 프롬프트 그대로 — 꼬리가 붙지 않는다
        assertEquals(
            CharacterFieldAiSuggester.buildSystemPrompt(),
            CharacterFieldAiSuggester.buildSystemPrompt(creativity = AiCreativity.BALANCED)
        )
        val narrative = NarrativeFieldAiWriter.buildSystemPrompt(AiCreativity.FREE)
        assertTrue(narrative.contains("[창작도: 자유]"))
        assertEquals(
            NarrativeFieldAiWriter.buildSystemPrompt(),
            NarrativeFieldAiWriter.buildSystemPrompt(AiCreativity.BALANCED)
        )
    }

    // ===== 직렬화 — null이면 키 자체가 없어야 한다 =====

    @Test
    fun codec_omitsTemperatureWhenNull() {
        for (protocol in AiProtocol.entries) {
            val spec = AiProtocolCodec.buildRequest(
                config(protocol), "key", AiRequest(userText = "hi", temperature = null)
            )
            assertFalse("$protocol 본문에 temperature가 실리면 안 된다", "temperature" in spec.bodyJson)
        }
    }

    @Test
    fun codec_serializesTemperaturePerProtocol() {
        val anthropic = AiProtocolCodec.buildRequest(
            config(AiProtocol.ANTHROPIC), "key", AiRequest(userText = "hi", temperature = 0.85)
        )
        assertTrue(anthropic.bodyJson.contains("\"temperature\":0.85"))

        val openAi = AiProtocolCodec.buildRequest(
            config(AiProtocol.OPENAI_COMPAT), "key", AiRequest(userText = "hi", temperature = 1.19)
        )
        assertTrue(openAi.bodyJson.contains("\"temperature\":1.19"))

        val gemini = AiProtocolCodec.buildRequest(
            config(AiProtocol.GEMINI), "key", AiRequest(userText = "hi", temperature = 1.4)
        )
        // Gemini는 generationConfig 안에 실린다
        val genConfig = gemini.bodyJson.substringAfter("generationConfig")
        assertTrue(genConfig.contains("\"temperature\":1.4"))
    }

    @Test
    fun codec_maxCompletionTokensRetry_keepsTemperature() {
        val retry = AiProtocolCodec.buildOpenAiRetryWithMaxCompletionTokens(
            config(AiProtocol.OPENAI_COMPAT), "key", AiRequest(userText = "hi", temperature = 0.5)
        )
        assertTrue(retry.bodyJson.contains("\"temperature\":0.5"))
        assertTrue(retry.bodyJson.contains("max_completion_tokens"))
    }

    // ===== temperature 거부 오류 판정 =====

    @Test
    fun temperatureError_realSamples() {
        // OpenAI 추론 모델의 실제 표본 형태
        assertTrue(
            AiProtocolCodec.isTemperatureUnsupportedError(
                400,
                """Unsupported value: 'temperature' does not support 0.5 with this model. Only the default (1) value is supported."""
            )
        )
        assertTrue(
            AiProtocolCodec.isTemperatureUnsupportedError(
                400, """Unsupported parameter: 'temperature' is not supported with this model."""
            )
        )
    }

    @Test
    fun temperatureError_noFalsePositives() {
        // 무관한 400 — 상한 초과·형식 오류를 온도 문제로 오인하면 잘못된 학습값이 남는다
        assertFalse(
            AiProtocolCodec.isTemperatureUnsupportedError(
                400, "max_tokens: 100000 > 8192, which is the maximum allowed"
            )
        )
        assertFalse(AiProtocolCodec.isTemperatureUnsupportedError(400, "Invalid JSON in request body"))
        // temperature가 언급돼도 거부 표현이 없으면 아니다
        assertFalse(AiProtocolCodec.isTemperatureUnsupportedError(400, "temperature must be a number"))
        // 400이 아니면 아니다
        assertFalse(
            AiProtocolCodec.isTemperatureUnsupportedError(
                429, "'temperature' is not supported with this model."
            )
        )
        assertFalse(AiProtocolCodec.isTemperatureUnsupportedError(400, null))
    }

    // ===== R-23 — 학습값 등재 =====

    @Test
    fun hasLearnedFacts_coversTemperatureUnsupported() {
        val base = config(AiProtocol.OPENAI_COMPAT)
        assertFalse(base.hasLearnedFacts())
        assertTrue(base.copy(detectedOutputLimit = 8192).hasLearnedFacts())
        assertTrue(base.copy(temperatureUnsupported = true).hasLearnedFacts())
    }
}
