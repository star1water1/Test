package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 출력 토큰 상한 정책·탐지·절단 감지의 회귀 테스트.
 *
 * 배경: 종전에는 `maxTokens = 4096`이 코드에 박혀 있었고 **모델을 전혀 몰랐다**.
 * 상한은 아예 없앨 수 없다(Anthropic Messages API는 `max_tokens`가 필수) — 그래서
 * "무엇을 근거로 정하는가"를 세 축으로 고정한다: 사용자 설정 · 탐지된 모델 상한 · 기본값.
 *
 * 그리고 상한에 걸려 **잘린 응답을 성공으로 처리**하던 것이 오진("형식 오류 — 다시 시도해
 * 주세요")의 원인이었다. 재시도해도 결정적으로 같은 결과이므로 안내가 사용자를 헛수고로
 * 보냈다. 절단 신호는 텍스트가 **있어도** 읽어야 한다.
 */
class AiTokenPolicyTest {

    private fun config(
        userSet: Int? = null,
        detected: Int? = null,
        protocol: AiProtocol = AiProtocol.ANTHROPIC
    ) = AiProviderConfig(
        id = "c1", protocol = protocol, displayName = "p",
        baseUrl = "https://example.com", model = "m",
        maxOutputTokens = userSet, detectedOutputLimit = detected
    )

    // ===== 정책 합성 =====

    @Test
    fun 미설정이면_종전과_같은_기본값() {
        // 회귀 방지의 핵심 — 이 값이 흔들리면 기존 사용자의 동작이 바뀐다.
        assertEquals(AiTokenPolicy.DEFAULT_REQUEST, AiTokenPolicy.effective(config()))
        assertEquals(4096, AiTokenPolicy.DEFAULT_REQUEST)
    }

    @Test
    fun 사용자_설정이_기본값을_이긴다() {
        assertEquals(8192, AiTokenPolicy.effective(config(userSet = 8192)))
        assertEquals(1024, AiTokenPolicy.effective(config(userSet = 1024)))
    }

    @Test
    fun 탐지된_모델_상한을_절대_넘지_않는다() {
        // 넘기면 400이고, 그 400은 사용자가 고칠 수 없는 실패다(슬라이더가 허용한 값이므로).
        assertEquals(2048, AiTokenPolicy.effective(config(userSet = 32768, detected = 2048)))
        assertEquals(2048, AiTokenPolicy.effective(config(detected = 2048)))
    }

    @Test
    fun 탐지값이_커도_미설정이면_기본값을_유지한다() {
        // 자동으로 크게 쓰지 않는다 — 크게 쓰려면 사용자가 슬라이더로 올려야 한다(보수적 기본).
        assertEquals(AiTokenPolicy.DEFAULT_REQUEST, AiTokenPolicy.effective(config(detected = 65536)))
    }

    @Test
    fun 하한_아래로는_내려가지_않는다() {
        assertEquals(AiTokenPolicy.FLOOR, AiTokenPolicy.effective(config(userSet = 1)))
        assertEquals(AiTokenPolicy.FLOOR, AiTokenPolicy.effective(config(userSet = 8192, detected = 1)))
    }

    @Test
    fun 슬라이더_최대값은_탐지된_상한이다() {
        assertEquals(AiTokenPolicy.FALLBACK_MAX, AiTokenPolicy.sliderMax(config()))
        assertEquals(16384, AiTokenPolicy.sliderMax(config(detected = 16384)))
        // 탐지값이 비정상적으로 작아도 슬라이더가 붕괴하지 않는다(valueFrom >= valueTo 크래시 방지)
        assertTrue(AiTokenPolicy.sliderMax(config(detected = 1)) > AiTokenPolicy.FLOOR)
    }

    @Test
    fun 슬라이더_최대값은_항상_눈금에_맞는다() {
        // Material Slider는 (valueTo - valueFrom) % stepSize != 0 이면 **예외로 죽는다.**
        // 탐지값은 프로바이더가 주는 임의의 수(오류 본문 학습 포함)라 256의 배수 보장이 없다.
        for (detected in listOf(1, 255, 257, 999, 4000, 8191, 8193, 16383, 100_001)) {
            val max = AiTokenPolicy.sliderMax(config(detected = detected))
            assertEquals(
                "detected=$detected 에서 눈금이 어긋나면 설정 화면이 크래시한다",
                0, (max - AiTokenPolicy.FLOOR) % AiTokenPolicy.STEP
            )
            assertTrue("valueTo는 valueFrom보다 커야 한다", max > AiTokenPolicy.FLOOR)
            if (detected >= AiTokenPolicy.FLOOR + AiTokenPolicy.STEP) {
                assertTrue("모델 상한을 넘겨 제안하지 않는다", max <= detected)
            }
        }
    }

    @Test
    fun 슬라이더_현재값도_눈금에_맞는다() {
        val max = AiTokenPolicy.sliderMax(config(detected = 4000)) // 3840
        for (v in listOf(1, 300, 4000, 100_000, AiTokenPolicy.DEFAULT_REQUEST)) {
            val snapped = AiTokenPolicy.snapToStep(v, max)
            assertEquals(0, (snapped - AiTokenPolicy.FLOOR) % AiTokenPolicy.STEP)
            assertTrue(snapped in AiTokenPolicy.FLOOR..max)
        }
        // 이미 눈금 위인 값은 그대로 둔다
        assertEquals(4096, AiTokenPolicy.snapToStep(4096, 8192))
    }

    @Test
    fun 항목당_요청수는_상한에_비례한다() {
        assertEquals(1, AiTokenPolicy.itemsPerRequest(100, 270, 60))
        assertEquals(15, AiTokenPolicy.itemsPerRequest(4096, 270, 60))
        assertEquals(60, AiTokenPolicy.itemsPerRequest(1_000_000, 270, 60))
    }

    // ===== 절단 감지 =====

    @Test
    fun anthropic_텍스트가_있어도_잘림을_알린다() {
        val body = """
            {"content":[{"type":"text","text":"{\"suggestions\":[{\"key\":\"a\""}],
             "stop_reason":"max_tokens","model":"claude-x",
             "usage":{"input_tokens":10,"output_tokens":20}}
        """.trimIndent()
        val r = AiProtocolCodec.parseSuccess(AiProtocol.ANTHROPIC, body, "claude-x") as AiResult.Success
        assertTrue("잘린 응답을 성공으로만 처리하면 오진이 난다", r.truncated)
    }

    @Test
    fun anthropic_정상_종료는_잘림이_아니다() {
        val body = """
            {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","model":"claude-x"}
        """.trimIndent()
        val r = AiProtocolCodec.parseSuccess(AiProtocol.ANTHROPIC, body, "claude-x") as AiResult.Success
        assertFalse(r.truncated)
    }

    @Test
    fun openai_finish_reason_length가_잘림이다() {
        val cut = """{"choices":[{"message":{"content":"부분"},"finish_reason":"length"}]}"""
        val ok = """{"choices":[{"message":{"content":"부분"},"finish_reason":"stop"}]}"""
        assertTrue((AiProtocolCodec.parseSuccess(AiProtocol.OPENAI_COMPAT, cut, "m") as AiResult.Success).truncated)
        assertFalse((AiProtocolCodec.parseSuccess(AiProtocol.OPENAI_COMPAT, ok, "m") as AiResult.Success).truncated)
    }

    @Test
    fun gemini_MAX_TOKENS가_잘림이다() {
        val cut = """{"candidates":[{"content":{"parts":[{"text":"부분"}]},"finishReason":"MAX_TOKENS"}]}"""
        val ok = """{"candidates":[{"content":{"parts":[{"text":"부분"}]},"finishReason":"STOP"}]}"""
        assertTrue((AiProtocolCodec.parseSuccess(AiProtocol.GEMINI, cut, "m") as AiResult.Success).truncated)
        assertFalse((AiProtocolCodec.parseSuccess(AiProtocol.GEMINI, ok, "m") as AiResult.Success).truncated)
    }

    @Test
    fun 빈_응답의_사유는_종전대로_실패로_보고한다() {
        val body = """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}"""
        val r = AiProtocolCodec.parseSuccess(AiProtocol.OPENAI_COMPAT, body, "m") as AiResult.Failure
        assertEquals(AiErrorKind.EMPTY_RESPONSE, r.kind)
        assertTrue("사유를 담아야 한다", r.detail?.contains("length") == true)
    }

    // ===== 모델 목록에서 상한 학습 (Gemini) =====

    @Test
    fun gemini_목록의_outputTokenLimit을_읽는다() {
        val body = """
            {"models":[
              {"name":"models/gemini-x","supportedGenerationMethods":["generateContent"],
               "inputTokenLimit":1048576,"outputTokenLimit":8192},
              {"name":"models/embed-only","supportedGenerationMethods":["embedContent"],
               "outputTokenLimit":1}
            ]}
        """.trimIndent()
        val infos = AiProtocolCodec.parseModelInfos(AiProtocol.GEMINI, body)
        assertEquals("generateContent 미지원 모델은 제외한다", 1, infos.size)
        assertEquals("gemini-x", infos[0].id)
        assertEquals(8192, infos[0].outputTokenLimit)
        assertEquals(8192, AiProtocolCodec.detectedLimitFor(infos, "gemini-x"))
        assertNull(AiProtocolCodec.detectedLimitFor(infos, "없는모델"))
    }

    @Test
    fun 목록에_상한이_없는_프로토콜은_null이다() {
        val body = """{"data":[{"id":"gpt-x"},{"id":"gpt-y"}]}"""
        val infos = AiProtocolCodec.parseModelInfos(AiProtocol.OPENAI_COMPAT, body)
        assertEquals(2, infos.size)
        assertNull(infos[0].outputTokenLimit)
    }

    @Test
    fun parseModelList는_기존_계약대로_id만_돌려준다() {
        val body = """{"data":[{"id":"b"},{"id":"a"}]}"""
        assertEquals(listOf("a", "b"), AiProtocolCodec.parseModelList(AiProtocol.OPENAI_COMPAT, body))
    }

    // ===== 오류에서 상한 학습 (Anthropic·OpenAI) =====

    @Test
    fun anthropic_상한초과_오류에서_실제_상한을_읽는다() {
        val body = """
            {"type":"error","error":{"type":"invalid_request_error",
             "message":"max_tokens: 100000 > 8192, which is the maximum allowed number of output tokens for claude-x"}}
        """.trimIndent()
        assertEquals(8192, AiProtocolCodec.parseMaxTokensLimitFromError(body, 100000))
    }

    @Test
    fun openai_상한초과_오류에서_실제_상한을_읽는다() {
        val body = """
            {"error":{"message":"max_tokens is too large: 100000. This model supports at most 16384 completion tokens"}}
        """.trimIndent()
        assertEquals(16384, AiProtocolCodec.parseMaxTokensLimitFromError(body, 100000))
    }

    @Test
    fun 요청값보다_크거나_같은_수는_후보가_아니다() {
        // 초과 오류이므로 정답은 반드시 요청값보다 작다 — 요청값 자신을 학습하면 무한 반복이 된다.
        val body = """{"error":{"message":"max_tokens: 4096 > 4096, which is the maximum allowed"}}"""
        assertNull(AiProtocolCodec.parseMaxTokensLimitFromError(body, 4096))
    }

    @Test
    fun 상한과_무관한_오류는_학습하지_않는다() {
        // 다른 400을 상한 문제로 오인해 재시도하면 요청만 낭비하고 원인도 가려진다.
        val body = """{"error":{"message":"model 1234 not found"}}"""
        assertNull(AiProtocolCodec.parseMaxTokensLimitFromError(body, 100000))
        assertNull(AiProtocolCodec.parseMaxTokensLimitFromError(null, 100000))
    }

    @Test
    fun 하한_미만은_학습하지_않는다() {
        // "at most 12 items" 같은 무관한 숫자를 상한으로 오인하면 이후 모든 요청이 무력해진다.
        val body = """{"error":{"message":"you may specify at most 12 tools"}}"""
        assertNull(AiProtocolCodec.parseMaxTokensLimitFromError(body, 100000))
    }

    // ===== 근거 강도 설정의 저장 표기 =====

    @Test
    fun 근거강도_저장표기_왕복() {
        // SharedPreferences에 null을 담을 수 없어 빈 문자열이 '전부 받기'다 —
        // 왕복이 깨지면 설정이 조용히 다른 값으로 바뀐다.
        for (value in listOf(
            null,
            CharacterFieldAiSuggester.Confidence.LOW,
            CharacterFieldAiSuggester.Confidence.MEDIUM,
            CharacterFieldAiSuggester.Confidence.HIGH
        )) {
            assertEquals(value, AiPromptPolicy.confidenceFromWire(AiPromptPolicy.confidenceToWire(value)))
        }
        assertNull("기본값은 전부 받기", AiPromptPolicy.CONFIDENCE_DEFAULT)
        assertNull("낡은/깨진 저장값은 전부 받기로 되돌아간다", AiPromptPolicy.confidenceFromWire("garbage"))
    }

    @Test
    fun 근거강도_비교는_하한이_없으면_언제나_통과한다() {
        assertTrue(CharacterFieldAiSuggester.Confidence.LOW.meets(null))
        assertTrue(CharacterFieldAiSuggester.Confidence.HIGH.meets(CharacterFieldAiSuggester.Confidence.MEDIUM))
        assertFalse(CharacterFieldAiSuggester.Confidence.LOW.meets(CharacterFieldAiSuggester.Confidence.MEDIUM))
    }
}
