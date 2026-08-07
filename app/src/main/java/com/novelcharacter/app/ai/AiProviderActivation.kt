package com.novelcharacter.app.ai

/**
 * 저장이 활성 프로바이더를 옮기는가 — **순수 판정**(JVM 테스트 대상). B-150.
 *
 * 종전 규칙은 `if (!exists && current.isEmpty()) setActiveId(...)` 한 줄, 곧 **첫 프로바이더일
 * 때만** 활성이 됐다. 그래서 한도에 걸린 프로바이더를 갈아 끼우려고 둘째를 등록해도 활성은
 * 옛 것에 남았고, 인앱 기능은 전부 `active()`로 나가므로 **새 키가 한 번도 쓰이지 않았다.**
 * 편집 창의 [연결 테스트]는 방금 만든 설정을 명시적으로 넘겨 초록이라 결함을 되레 가렸다.
 *
 * 판정은 2026.08.07 사용자 확정이다(`docs/judgment_confirmations_2026-08.md` 10장):
 * **자동 활성 — 단 새로 만든 것이고 키가 등록된 때만**, 묻지 않고 **하고 나서 토스트로 말한다.**
 */
object AiProviderActivation {

    enum class Outcome {
        /** 활성을 건드리지 않는다. */
        KEEP,

        /** 활성이 없어 이 항목을 세운다 — 종전 동작 그대로이므로 고지하지 않는다. */
        ACTIVATE_FIRST,

        /** 이미 다른 활성이 있는데 이 항목으로 옮긴다 — **고지 대상**이다. */
        ACTIVATE_MOVED
    }

    /**
     * [isNew] 새로 만든 항목인가(기존 항목 편집은 활성을 건드리지 않는다 — 확정).
     * [hasKey] 이 항목에 API 키가 등록돼 있는가.
     * [hasActiveProvider] 지금 실제로 풀리는 활성 프로바이더가 있는가
     *   (활성 id가 삭제된 항목을 가리키는 경우는 **없는 것으로** 본다 — 그래야 매달린
     *   포인터가 다음 저장에서 저절로 낫는다).
     *
     * **키 조건이 [ACTIVATE_MOVED]에만 걸리는 것이 이 판정의 핵심이다.**
     * 확정이 키 조건을 둔 이유는 *"키 없는 프로바이더가 활성이 되면 모든 호출이 `NO_KEY`로
     * 죽어 침묵을 다른 침묵으로 바꾼다"*인데, 그 논거는 **쓸 수 있는 활성을 밀어낼 때**의
     * 이야기다. 활성이 아예 없을 때는 밀어낼 것이 없고, `NO_KEY`(*"키를 등록해 주세요"*)가
     * `NO_PROVIDER`(*"프로바이더가 없습니다"* — 방금 하나 만든 사용자에게는 거짓말이다)보다
     * **정확한 안내**다. 그래서 첫 등록의 종전 동작을 그대로 둔다 — 없애면 조용한 퇴행이다.
     */
    fun decide(isNew: Boolean, hasKey: Boolean, hasActiveProvider: Boolean): Outcome = when {
        !isNew -> Outcome.KEEP
        !hasActiveProvider -> Outcome.ACTIVATE_FIRST
        hasKey -> Outcome.ACTIVATE_MOVED
        else -> Outcome.KEEP
    }
}
