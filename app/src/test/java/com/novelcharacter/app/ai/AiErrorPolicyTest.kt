package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 잔여 청크를 마저 보낼 것인가 — 종단 실패 집합 (B-153에서 단일 소스로 걷었다).
 *
 * **왜 이 시험이 필요했는가:** 같은 집합이 소비자 네 곳에 각자 적혀 있었고, 셋은 주석으로
 * *"형제 서제스터와 **같은 집합**이다"*라고 선언까지 했는데 **그 선언을 지키는 것은 없었다.**
 * 분류가 하나 늘면 네 벌을 모두 고쳐야 하는데, 빠뜨린 벌은 컴파일도 되고 시험도 초록이며
 * 증상은 *"그 기능에서만 남은 청크를 헛되이 마저 보낸다"*라 눈으로는 보이지 않는다.
 *
 * **이 시험의 방어선은 셋이다:**
 * ① *"모든 분류가 판정을 받는다"* — **이것 하나가 이 파일의 이유다.** 분류가 늘 때
 *    아래 표를 함께 고치지 않으면 실패한다. 표를 손으로 적고 `values()`와 대조하는 것이
 *    요점이다: 종단 목록만 세는 시험은 **새 분류를 빠뜨린 채 그대로 통과한다**(빠뜨린 것을
 *    시험도 함께 빠뜨리므로 — `AiProviderCodec` 시험이 배운 것과 같은 함정이다).
 * ② *"다시 보내면 될 수 있는 것은 종단이 아니다"* — 네트워크·시간 초과·서버·빈 응답을
 *    종단에 넣으면 **될 일을 안 하게 된다.** 한 청크가 흔들렸다고 남은 전부를 버리는 셈이라,
 *    증상은 *"AI 기능이 자꾸 절반만 처리한다"*가 된다.
 * ③ *"[AiErrorKind.ACTIVE_NOT_SET]은 종단이다"* — B-153이 더한 분류다. 문구만 갈랐지
 *    처분은 [AiErrorKind.NO_PROVIDER]와 같아야 한다. 빠뜨리면 **쓸 프로바이더가 정해지지도
 *    않은 채 남은 청크를 전부 보내고 전부 같은 실패를 받는다.**
 */
class AiErrorPolicyTest {

    /**
     * 분류별 기대 처분 — **손으로 적는다.** 여기 없는 분류가 생기면 ①이 잡는다.
     * `true`는 *"다시 보내도 같은 결과이니 남은 것을 중단한다"*.
     */
    private val expected: Map<AiErrorKind, Boolean> = mapOf(
        // 사용자가 설정에서 무언가 고쳐야 풀리는 것 — 재시도로는 절대 안 풀린다
        AiErrorKind.NO_PROVIDER to true,
        AiErrorKind.ACTIVE_NOT_SET to true,
        AiErrorKind.NO_KEY to true,
        AiErrorKind.INVALID_KEY to true,
        AiErrorKind.QUOTA_EXCEEDED to true,
        AiErrorKind.MODEL_NOT_FOUND to true,
        // 모델이 받지 않는 항목은 청크가 바뀐다고 받아 주지 않는다 (B-161) — 남은 요청은
        // 전부 같은 항목을 실어 같은 400을 받으므로 돈만 쓴다.
        AiErrorKind.UNSUPPORTED_PARAM to true,
        // 같은 기준이다 — 다만 이 값이 관문 밖으로 나오는 경로는 사실상 없다(전환 후보가
        // 남았으면 넘기고, 소진되면 이미지를 빼고 텍스트로 답한다). 그래도 판정은 받아 둔다.
        AiErrorKind.IMAGES_UNSUPPORTED to true,
        // 다음 청크가 성공할 수 있는 것 — 중단하면 될 일을 안 하는 것이 된다
        AiErrorKind.RATE_LIMITED to false,
        // 뭉뚱그려진 400은 여기 남는다 — 본문이 원인을 지목하지 않은 나머지라 재시도로
        // 풀릴 여지가 섞여 있다. 좁은 판정(UNSUPPORTED_PARAM) 위에서만 중단이 정당하다.
        AiErrorKind.BAD_REQUEST to false,
        AiErrorKind.NETWORK to false,
        AiErrorKind.TIMEOUT to false,
        AiErrorKind.SERVER to false,
        AiErrorKind.EMPTY_RESPONSE to false,
        AiErrorKind.UNKNOWN to false
    )

    // ── ① 모든 분류가 판정을 받는다 ────────────────────────────────────────────

    @Test
    fun 모든_오류_분류가_종단_여부를_배정받는다() {
        assertEquals(
            "새 AiErrorKind가 늘었으면 이 시험의 표에도 처분을 적을 것 — " +
                "적지 않으면 그 분류는 조용히 '종단 아님'이 되어 남은 청크를 헛되이 보낸다",
            AiErrorKind.values().toSet(),
            expected.keys
        )
    }

    @Test
    fun 표에_적은_처분과_실제_판정이_일치한다() {
        for ((kind, terminal) in expected) {
            assertEquals("$kind 의 처분", terminal, AiErrorPolicy.isTerminal(kind))
        }
    }

    /** 집합과 술어가 갈리면 소비자마다 다른 답을 본다 — 둘은 한 벌이어야 한다. */
    @Test
    fun 집합과_술어가_같은_답을_낸다() {
        for (kind in AiErrorKind.values()) {
            assertEquals(kind in AiErrorPolicy.TERMINAL, AiErrorPolicy.isTerminal(kind))
        }
    }

    // ── ② 다시 보내면 될 수 있는 것은 종단이 아니다 ─────────────────────────────

    @Test
    fun 일시적_실패는_남은_청크를_중단시키지_않는다() {
        for (kind in listOf(
            AiErrorKind.NETWORK, AiErrorKind.TIMEOUT,
            AiErrorKind.SERVER, AiErrorKind.EMPTY_RESPONSE
        )) {
            assertFalse(
                "$kind 는 다음 청크가 성공할 수 있다 — 중단하면 될 일을 안 하는 것이다",
                AiErrorPolicy.isTerminal(kind)
            )
        }
    }

    // ── ③ B-153이 더한 분류는 NO_PROVIDER와 같은 처분이다 ──────────────────────

    @Test
    fun 활성_미지정은_프로바이더_없음과_같은_처분이다_문구만_갈렸다() {
        assertTrue(AiErrorPolicy.isTerminal(AiErrorKind.ACTIVE_NOT_SET))
        assertEquals(
            AiErrorPolicy.isTerminal(AiErrorKind.NO_PROVIDER),
            AiErrorPolicy.isTerminal(AiErrorKind.ACTIVE_NOT_SET)
        )
    }

    /**
     * **전환 집합과는 다른 축이다.** 닮아 보여 통일하고 싶어지는 자리인데, 통일하면
     * 한도(전환 대상)가 종단이 되어 **자동 전환이 통째로 죽는다** — 그 순간 남은
     * 프로바이더를 두고도 첫 한도에서 실행이 끝난다.
     */
    @Test
    fun 종단_집합은_전환_집합과_다르다() {
        assertFalse(AiErrorPolicy.isTerminal(AiErrorKind.RATE_LIMITED))
        assertEquals(
            AiProviderFallback.Disposition.SWITCH,
            AiProviderFallback.dispositionOf(AiErrorKind.RATE_LIMITED, retriesUsed = 99)
        )
    }
}
