package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오류 문구가 **어느 프로바이더였는지**를 말하는가 (B-150 ①, 2026.08.07 사용자 확정).
 *
 * **왜 이것이 셋 중 먼저인가:** 활성이 옮겨 가지 않은 것은 *사고*이고, 그 사고를 30초 만에
 * 알아챌 수 없게 만든 것이 **이 침묵**이다. 자동 활성은 *추가하는 순간*만 고치지만 이쪽은
 * 프로바이더가 셋일 때 나중에 엉뚱한 것을 눌렀어도 듣는다(확정 문서 10장).
 *
 * **이 판의 방어선은 시험 수가 아니라 그 안의 둘이고, 둘 다 시험이 유일한 검출이다:**
 * ① *"프로바이더 줄이 맨 앞에 선다"* — 뒤로 밀면 길이가 들쭉날쭉한 제공사 원문에 가려
 *    사실상 안 보인다. 코드는 멀쩡해 보이고 문구도 다 들어 있어 **눈으로는 통과한다.**
 * ② *"표식은 관문이 새긴다"* — 호출부가 넘기게 하면 자리가 8곳이 되고, 빠뜨린 자리는
 *    **종전과 글자 그대로 같은 문구**를 낸다(고쳤다고 믿는 채로 안 고쳐진다).
 *    새 인앱 기능이 늘 때 조용히 빠지는 자리가 바로 이 부류다.
 */
class AiErrorTextTest {

    private val ref = AiProviderRef("앤트로픽", "claude-sonnet-4")
    private val line = "호출한 프로바이더: 앤트로픽 · claude-sonnet-4"

    // ── ① 프로바이더 줄이 맨 앞이다 ───────────────────────────────────────────

    @Test
    fun 프로바이더_줄이_맨_앞에_선다() {
        val text = AiErrorText.compose(
            base = "요청 한도를 초과했습니다.", httpCode = 429,
            detail = "rate_limit_error", providerLine = line
        )
        assertEquals(line, text.lineSequence().first())
    }

    @Test
    fun 분류_문구와_HTTP_코드와_원문이_순서대로_붙는다() {
        val text = AiErrorText.compose(
            base = "요청 한도를 초과했습니다.", httpCode = 429,
            detail = "rate_limit_error", providerLine = line
        )
        assertEquals(
            "$line\n요청 한도를 초과했습니다. (HTTP 429)\n\nrate_limit_error",
            text
        )
    }

    /** 표식이 없으면 종전 문구와 **글자 그대로 같다** — 회귀가 없다는 것이 이 시험의 내용이다. */
    @Test
    fun 표식이_없으면_종전_문구와_같다() {
        assertEquals(
            "요청 한도를 초과했습니다. (HTTP 429)\n\nrate_limit_error",
            AiErrorText.compose("요청 한도를 초과했습니다.", 429, "rate_limit_error", null)
        )
    }

    @Test
    fun HTTP_코드가_없으면_괄호도_없다() {
        assertEquals("$line\n연결할 수 없습니다.", AiErrorText.compose("연결할 수 없습니다.", null, null, line))
    }

    @Test
    fun 원문이_없으면_빈_줄을_남기지_않는다() {
        assertEquals("$line\n알 수 없는 오류입니다.", AiErrorText.compose("알 수 없는 오류입니다.", null, "  ", line))
    }

    @Test
    fun 빈_표식은_없는_것으로_본다() {
        assertEquals("알 수 없는 오류입니다.", AiErrorText.compose("알 수 없는 오류입니다.", null, null, "   "))
    }

    // ── ② 표식은 관문이 새긴다 ────────────────────────────────────────────────

    @Test
    fun 실패에_표식이_새겨진다() {
        val stamped = AiResult.Failure(AiErrorKind.QUOTA_EXCEEDED, detail = "no credit", httpCode = 402)
            .withProvider(ref) as AiResult.Failure
        assertEquals(ref, stamped.provider)
        // 새기는 것 말고는 아무것도 바꾸지 않는다
        assertEquals(AiErrorKind.QUOTA_EXCEEDED, stamped.kind)
        assertEquals("no credit", stamped.detail)
        assertEquals(402, stamped.httpCode)
    }

    /**
     * **B-108이 이 계약을 넓혔다.** 종전에는 *"성공은 그대로 지나간다"*였고 그때는 그것이 맞았다 —
     * 성공은 늘 사용자가 고른 그곳에서 왔으므로 *누가 답했는가*를 물을 이유가 없었다.
     * 자동 전환이 들어오면서 그 전제가 깨졌다: 한도로 밀려 **다른 곳**이 답했을 수 있고,
     * 그 사실을 새기지 않으면 고지 한 줄(확정 ⓑ)이 성립하지 않는다.
     *
     * 새기는 것 **말고는** 아무것도 바꾸지 않는다는 성질은 그대로다 — 그것까지 놓으면
     * 관문이 결과를 손대는 자리가 되어 회귀의 근원이 된다.
     */
    @Test
    fun 성공에도_표식이_새겨지되_나머지는_그대로다() {
        val success = AiResult.Success(text = "ok", model = "m", truncated = true)
        val stamped = success.withProvider(ref) as AiResult.Success
        assertEquals(ref, stamped.provider)
        assertEquals(success, stamped.copy(provider = null))
    }

    @Test
    fun 설정이_그대로_표식이_된다() {
        val config = AiProviderConfig(
            id = "p1", protocol = AiProtocol.ANTHROPIC, displayName = "앤트로픽",
            baseUrl = "https://api.anthropic.com", model = "claude-sonnet-4"
        )
        assertEquals(ref, config.ref())
    }

    /**
     * 프로바이더가 아직 정해지지 않은 실패에는 새길 것이 없다 — 그 하나만 null이다.
     * (표식 없는 실패가 이것뿐이라는 사실이 *"어디선가 새기는 것을 빠뜨렸다"*의 판별 기준이 된다.)
     */
    @Test
    fun 프로바이더가_없어_난_실패만_표식이_없다() {
        assertNull(AiResult.Failure(AiErrorKind.NO_PROVIDER).provider)
    }

    /** 두 프로바이더의 같은 분류 오류가 **서로 다른 문구**가 된다 — 이것이 B-150이 산 것이다. */
    @Test
    fun 프로바이더가_다르면_같은_오류도_문구가_갈린다() {
        val a = AiErrorText.compose("요청 한도를 초과했습니다.", 429, null, line)
        val b = AiErrorText.compose(
            "요청 한도를 초과했습니다.", 429, null, "호출한 프로바이더: 제미나이 · gemini-2.5-pro"
        )
        assertTrue("두 프로바이더의 문구가 같으면 진단 공백이 그대로다", a != b)
    }
}
