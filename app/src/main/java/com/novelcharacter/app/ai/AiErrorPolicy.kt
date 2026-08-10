package com.novelcharacter.app.ai

/**
 * 실패 분류의 **처분 정책** — 순수 판정(JVM 테스트 대상).
 *
 * 지금 담는 것은 하나다: *"남은 청크를 마저 보내 볼 만한가."* 인앱 AI 기능은 대상이 많으면
 * 요청을 여러 번 나눠 보내는데(필드 추천·값 정리·이미지 태깅), 그 루프가 실패를 만났을 때
 * **다시 보내도 같은 결과인 부류**면 나머지를 보내는 것은 시간과 돈만 쓰는 일이다.
 *
 * ── 왜 한곳에 모았는가 (B-153) ──
 * 종전에는 같은 집합이 소비자 네 곳에 **각자 적혀** 있었고, 셋은 주석으로
 * *"형제 서제스터와 **같은 집합**이다"*라고 선언까지 하고 있었다 — **그 선언을 지키는 것은
 * 아무것도 없었다.** 분류가 하나 늘 때 네 벌을 모두 고쳐야 하는데, 빠뜨린 벌은 컴파일도 되고
 * 시험도 초록이며 증상은 *"그 기능에서만 남은 청크를 헛되이 마저 보낸다"*라 눈에 띄지 않는다.
 * 실제로 [AiErrorKind.ACTIVE_NOT_SET]을 더하면서 네 벌을 동시에 고쳐야 했고, 그 자리에서 걷었다.
 *
 * 이것은 [AiProviderFallback.dispositionOf]와 **다른 축이다.** 그쪽은 *"다른 프로바이더로
 * 넘길 것인가"*를 보고 여기는 *"이 실행을 계속할 것인가"*를 본다 — 두 집합은 겹치지 않는다:
 * 한도(`RATE_LIMITED`·`QUOTA_EXCEEDED`)는 넘길 대상이지만 여기서는 종단이 아니고,
 * 네트워크·시간 초과는 넘기지 않지만 여기서도 종단이 아니다(다시 보내면 될 수 있다).
 */
object AiErrorPolicy {

    /**
     * **재시도해도 같은 결과인 실패** — 잔여 청크·배치를 중단하는 기준.
     *
     * 공통점은 *"요청을 다시 보내는 것으로는 절대 풀리지 않고, 사용자가 설정에서 무언가를
     * 고쳐야 풀린다"*는 것이다. 그래서 네트워크·시간 초과·서버 오류·빈 응답은 여기 없다 —
     * 그쪽은 다음 청크가 성공할 수 있으므로 중단하면 **될 일을 안 하는 것**이 된다.
     *
     * [AiErrorKind.ACTIVE_NOT_SET]이 여기 있는 이유는 [AiErrorKind.NO_PROVIDER]와 같다:
     * 쓸 프로바이더가 정해지지 않은 채로는 **몇 번을 보내도 관문을 넘지 못한다.** 둘을 가른
     * 것은 사용자에게 할 말이지 처분이 아니다(B-153).
     */
    val TERMINAL: Set<AiErrorKind> = setOf(
        AiErrorKind.NO_PROVIDER,
        AiErrorKind.ACTIVE_NOT_SET,
        AiErrorKind.NO_KEY,
        AiErrorKind.INVALID_KEY,
        AiErrorKind.QUOTA_EXCEEDED,
        AiErrorKind.MODEL_NOT_FOUND
    )

    /** 이 실패를 만나면 남은 청크를 보내지 않는다. */
    fun isTerminal(kind: AiErrorKind): Boolean = kind in TERMINAL
}
