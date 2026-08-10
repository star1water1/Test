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

    /**
     * **활성 프로바이더를 지웠을 때 누가 이어받는가** — 순수 판정 (B-153).
     *
     * 종전에는 이어받는 것이 없었다: `delete`의 마지막 줄이 활성 id를 지우고 끝이라
     * **남은 프로바이더가 몇 개든 활성이 비었다.** 그러면 인앱 AI가 전부 `NO_PROVIDER`로 죽는데
     * 목록에는 키까지 등록된 프로바이더가 그대로 서 있다 — 화면이 사실과 다른 말을 한다.
     * 한도에 걸린 프로바이더를 *지우고* 다른 것을 쓰려는 동선이라 사용자가 밟기 쉬운 길이다.
     *
     * 판정은 2026.08.10 사용자 확정이다(`docs/judgment_confirmations_2026-08.md` 13-1):
     * **키가 있는 첫째, 없으면 등록순 첫째.**
     *
     * **'키가 있는 첫째'인 이유:** 키 없는 것을 세우면 실패가 `NO_PROVIDER`에서 `NO_KEY`로
     * **자리만 옮긴다** — 사용자는 여전히 쓸 수 있는 프로바이더를 두고 실패를 받는다.
     * 그래도 키 있는 것이 하나도 없으면 등록순 첫째를 세운다: 그 경우 `NO_KEY`(*"키를 등록해
     * 주세요"*)가 남는데, 그것은 [decide]의 ③이 이미 같은 근거로 택한 안내다.
     *
     * **기각한 쪽(최근 수정순):** *"방금 만진 것"*이 곧 *"쓰려는 것"*이라는 전제가 약하다 —
     * 한도에 걸린 프로바이더를 지우려고 방금 연 자리가 정확히 이 동선이다.
     *
     * @param remaining 지운 뒤 **남은** 프로바이더. 순서는 화면에 보이는 그 순서여야 한다
     *   (`AiProviderStore.list()`가 이미 `AiProviderFallback.displayOrder`를 지난다) —
     *   '등록순 첫째'는 사용자가 목록 맨 위에서 보는 그것이라는 뜻이고, 여기서 따로 정렬하면
     *   **보여 주는 순서와 이어받는 순서가 갈린다.**
     * @return 활성을 이어받을 프로바이더. [remaining]이 비어 있으면 null(이어받을 것이 없다).
     */
    fun succeedAfterDelete(
        remaining: List<AiProviderConfig>,
        hasKey: (String) -> Boolean
    ): AiProviderConfig? = remaining.firstOrNull { hasKey(it.id) } ?: remaining.firstOrNull()
}
