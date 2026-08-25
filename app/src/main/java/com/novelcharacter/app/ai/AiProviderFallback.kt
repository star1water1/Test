package com.novelcharacter.app.ai

/**
 * 프로바이더 자동 전환 — **순수 판정**(JVM 테스트 대상). B-108.
 *
 * 사용자 요청(2026.08.03): *"사용중에 사용량 한도 걸리면 자동으로 다른 api로 전환해서 이어나가도록
 * 하게. 그리고 그 전환 우선순위도 지정할 수 있게."*
 *
 * **바탕은 이미 서 있었다** — [AiProviderConfig]가 목록이고 프로토콜이 추상화돼 있어 갈아 끼우는
 * 것 자체는 값 교체이며, 오류 갈래도 [AiErrorKind.RATE_LIMITED]·[AiErrorKind.QUOTA_EXCEEDED]로
 * 이미 갈려 있었다. 그래서 새로 만드는 것은 '전환'이 아니라 **'언제·어디로·무엇을 알리며'**다.
 *
 * 판정은 2026.08.04 사용자 확정이다(`docs/judgment_confirmations_2026-08.md` **7-1**):
 * ⓐ 429는 재시도 / 크레딧 소진은 전환(**가른다**) · ⓑ 결과에 한 줄 고지 · ⓒ 우선순위는 전역 하나 ·
 * ⓓ 키 없는 프로바이더는 건너뜀 · ⓔ 한 요청당 최대 3곳 + 한도로 밀린 키는 10분 쿨다운.
 *
 * **왜 순수로 갈라 두는가:** 이 판정이 [AiService] 안에 인라인으로 있으면 어떤 로컬 검증도 보지
 * 못한다 — 저장소·네트워크가 Context를 쥐고 있어 하네스를 세울 수 없기 때문이다. B-132가 바로
 * 그 자리에서 났다(영속 계층에만 빠진 학습값을 어떤 검사도 못 봤다). 갈라 두면 **순서·방아쇠·
 * 쿨다운 전부가 시험 대상**이 되고, 조용히 어긋날 자리가 없어진다.
 */
object AiProviderFallback {

    /**
     * 한 요청이 두드릴 프로바이더 수의 상한 (확정 ⓔ).
     *
     * 상한이 없으면 전부 한도일 때 등록한 수만큼 요청이 나가고, 그동안 사용자는 아무 표시 없이
     * 기다린다 — 요청 하나가 최악의 경우 `등록 수 × 타임아웃`이 된다.
     */
    const val MAX_PROVIDERS_PER_REQUEST = 3

    /**
     * 429로 **같은 곳에** 다시 물어보는 횟수 (확정 ⓐ).
     *
     * 429는 *잠시 후 되는 것*이라 곧바로 갈아치우면 일시적 혼잡에 키가 바뀐다. 그래서 전환보다
     * 재시도가 먼저다. 재시도까지 실패하면 그때는 옮긴다 — 그러지 않으면 멀쩡한 둘째 키를 두고
     * 사용자가 실패를 받는데, 그것이 이 요청이 없애 달라고 한 바로 그 상황이다.
     * 쿨다운이 있으므로 **옮긴 뒤 10분이면 돌아온다**(그 점이 ⓐ가 경계한 *"갈아치우고 돌아오지
     * 않는다"*와 갈리는 자리다).
     */
    const val RATE_LIMIT_RETRIES = 1

    /** 한도로 밀린 프로바이더를 뒤로 미루는 기간 (확정 ⓔ). */
    const val COOLDOWN_MILLIS = 10 * 60 * 1000L

    /**
     * 429 재시도 전 대기. 0으로 두면 같은 순간에 다시 물어 같은 답을 받는다 —
     * 재시도라는 이름만 있고 실질이 없어진다.
     */
    const val RATE_LIMIT_BACKOFF_MILLIS = 1_500L

    /** 실패 하나를 만난 관문이 다음에 할 일. */
    enum class Disposition {
        /** 같은 프로바이더에 다시 묻는다 (429 — 잠시 후 되는 것). */
        RETRY_SAME,

        /** 이 프로바이더를 쿨다운에 넣고 다음 프로바이더로 넘어간다 (한도). */
        SWITCH,

        /** 전환해도 같은 결과이거나 전환이 답이 아닌 실패 — 그대로 돌려준다. */
        STOP
    }

    /**
     * [kind]와 지금까지 쓴 재시도 횟수로 처분을 정한다.
     *
     * **한도가 아닌 실패는 전환하지 않는다**는 것이 이 함수의 요점이다. [AiErrorKind.INVALID_KEY]는
     * *"그 키로는 영영 안 되는 것"*이라는 점에서 크레딧 소진과 닮았지만 전환 대상이 아니다 —
     * 키가 틀렸다는 사실을 다른 프로바이더의 성공으로 덮으면 사용자는 **틀린 키를 영영 고치지
     * 않는다**(고칠 곳이 있는 실패를 조용한 성공으로 바꾸는 것은 변수 제어의 반대다).
     * 확정(7-1)이 방아쇠로 든 것은 한도 둘이다.
     *
     * **[AiErrorKind.IMAGES_UNSUPPORTED]가 셋째 방아쇠다** (사용자 요청 2026.08.25). 이것도
     * [AiErrorKind.INVALID_KEY]처럼 *"같은 프로바이더에 다시 물어도 영영 안 되는 것"*이지만,
     * 가른 이유는 **고칠 실수가 있는가**다 — 틀린 키는 사용자가 고쳐야 할 실수이고 다른 곳의
     * 성공으로 덮으면 그 실수를 영영 못 본다. 반면 "이 모델이 비전을 안 한다"는 고칠 실수가
     * 아니다(키도 설정도 멀쩡하다) — 등록해 둔 다른 프로바이더 중 비전이 되는 곳으로 넘겨
     * 원래 요청(이미지 포함)을 그대로 이어가는 것이 자율성 우선(개발 의도)이 요구하는 동작이다.
     * 재시도 횟수를 안 보는 이유도 [AiErrorKind.QUOTA_EXCEEDED]와 같다 — 같은 프로바이더에
     * 다시 물어도 이미지를 여전히 거부하므로 재시도할 이유가 없다.
     */
    fun dispositionOf(kind: AiErrorKind, retriesUsed: Int): Disposition = when (kind) {
        AiErrorKind.RATE_LIMITED ->
            if (retriesUsed < RATE_LIMIT_RETRIES) Disposition.RETRY_SAME else Disposition.SWITCH
        AiErrorKind.QUOTA_EXCEEDED -> Disposition.SWITCH
        AiErrorKind.IMAGES_UNSUPPORTED -> Disposition.SWITCH
        else -> Disposition.STOP
    }

    /**
     * 이 실패가 프로바이더를 쿨다운에 넣는 부류인가.
     *
     * **[Disposition.SWITCH]의 집합과 더는 같지 않다** (2026.08.25 —
     * [AiErrorKind.IMAGES_UNSUPPORTED]가 전환은 하되 쿨다운은 받지 않는 유일한 예외다).
     * 쿨다운은 *"이 프로바이더가 잠시/영영 못 쓴다"*는 뜻인데, 이미지를 거부한 프로바이더는
     * **텍스트만 있는 다음 요청에는 여전히 완전히 멀쩡하다.** 쿨다운을 주면 이미지 요청
     * 하나 때문에 10분 동안 텍스트 요청까지 그 프로바이더를 뒤로 미루는 부작용이 생긴다 —
     * [AiProviderConfig.imagesUnsupported] 학습값이 이미 "이미지를 실을 때만 건너뛴다"는
     * 더 좁고 정확한 회피를 담당하므로 쿨다운으로 겹쳐 막을 이유가 없다.
     */
    fun earnsCooldown(kind: AiErrorKind): Boolean =
        kind == AiErrorKind.RATE_LIMITED || kind == AiErrorKind.QUOTA_EXCEEDED

    /** 지금 시각 기준으로 쿨다운이 끝나는 시각. */
    fun cooldownUntil(nowMillis: Long): Long = nowMillis + COOLDOWN_MILLIS

    /**
     * 이 프로바이더가 지금 쿨다운 중인가.
     *
     * **남은 시간이 상한을 넘으면 만료로 본다** — 저장값은 벽시계 시각이라 사용자가 시계를 뒤로
     * 돌리거나 기기를 옮기면 *"10분"*이 며칠이 될 수 있다. 그렇게 굳으면 사용자에게는 원인도
     * 교정 경로도 보이지 않는 침묵이 된다(설정 어디에도 "쿨다운"이라는 개념이 없으므로).
     * 상한을 넘는 잔여는 시계가 어긋난 것이지 쿨다운이 아니다.
     */
    fun isCoolingDown(config: AiProviderConfig, nowMillis: Long): Boolean {
        val until = config.cooldownUntilMillis ?: return false
        if (until <= nowMillis) return false
        return until - nowMillis <= COOLDOWN_MILLIS
    }

    /**
     * 이번 요청이 두드릴 순서.
     *
     * 규칙 넷:
     * 1. **[active]가 맨 앞이다** — 사용자가 "이것으로 하라"고 고른 것이고, 우선순위는 *전환할 때
     *    어디로 가는가*의 순서다. 그래서 아무것도 실패하지 않는 평소에는 **종전과 글자 그대로
     *    같은 프로바이더**가 답한다(회귀 없음).
     * 2. **쿨다운 중인 것은 맨 뒤로** — 빼지 않고 미룬다. 빼면 전부 쿨다운일 때 두드릴 곳이 없어
     *    *"프로바이더가 없습니다"*라는 거짓말이 나가는데, 등록된 키는 멀쩡히 있다. 뒤로 미루면
     *    ⓔ의 취지(*"매 요청이 죽은 키부터 다시 두드린다"*를 막는 것)를 채우면서, 살아 있는 곳이
     *    하나도 없을 때는 마지막 수단으로 실제 오류를 받아 온다.
     * 3. **키 없는 것은 뺀다** (확정 ⓓ) — 시도 자체가 낭비이고, `NO_KEY`는 전환으로 고쳐지지 않는다.
     * 4. 나머지는 [AiProviderConfig.priority] 오름차순 → [AiProviderConfig.createdAt] 오름차순.
     *    만든 순서를 마지막 기준으로 두는 것은 우선순위를 한 번도 손대지 않은 사용자(전부 0)에게
     *    **종전 목록 순서 그대로**를 주기 위해서다.
     *
     * [hasKey]를 함수로 받는 것은 키 저장소가 Context를 쥐고 있어서다 — 그것을 들이면 이 판정이
     * 순수를 잃고 시험 대상에서 빠진다.
     */
    fun order(
        configs: List<AiProviderConfig>,
        active: AiProviderConfig,
        hasKey: (String) -> Boolean,
        nowMillis: Long,
        limit: Int = MAX_PROVIDERS_PER_REQUEST
    ): List<AiProviderConfig> {
        if (limit <= 0) return emptyList()
        val usable = configs.filter { hasKey(it.id) }
        // 활성이 목록에 없는 경우(방금 지워진 등)에도 앞자리는 활성의 몫이다 — 관문이 이미
        // 키를 확인하고 들어왔으므로 여기서 그것을 떨어뜨리면 종전 동작이 조용히 달라진다.
        val pool = if (usable.any { it.id == active.id }) usable else listOf(active) + usable
        return pool
            .sortedWith(
                compareBy<AiProviderConfig> { if (isCoolingDown(it, nowMillis)) 1 else 0 }
                    .thenBy { if (it.id == active.id) 0 else 1 }
                    .thenBy { it.priority }
                    .thenBy { it.createdAt }
            )
            .take(limit)
    }

    /**
     * [index]번째 후보가 이미지를 거부했을 때 **넘겨서 얻을 것이 있는가** — 뒤에 이미지를
     * 받아 줄 여지가 있는 후보가 남았는가 (2026.08.25).
     *
     * **"마지막 후보인가"로 물으면 안 되는 이유가 이 함수의 존재 이유다.** 등록된 곳이 전부
     * 이미지 거부를 이미 학습한 상태에서 그렇게 물으면, 사용자가 고른 활성 A를 건너뛰고
     * B도 건너뛰어 **맨 뒤 C가 답한다** — 셋 다 똑같이 글만 보낼 수 있는데 하필 사용자가
     * 고르지 않은 곳이 답하는 것이다. 전환은 *얻을 것이 있을 때* 하는 것이고, 얻을 것이
     * 없으면 **종전 그대로 활성이 답해야 한다**(회귀 없음). 그래서 뒤에 *아직 거부를 배우지
     * 않았거나 받아 준다고 아는* 후보가 하나라도 있을 때만 참이다.
     *
     * `imagesUnsupported != true`로 보는 것이 요점이다 — `null`(아직 안 배웠다)은 **받아 줄
     * 여지**이므로 참이다. 배우는 유일한 경로가 실제로 보내 보는 것이라, 여기서 `null`을
     * 거부로 치면 첫 시도의 기회가 사라지고 그 곳은 영영 못 배운다.
     */
    fun hasImageCapableCandidateAfter(chain: List<AiProviderConfig>, index: Int): Boolean =
        (index + 1 until chain.size).any { chain[it].imagesUnsupported != true }

    /**
     * **저장된 목록이 서는 순서** — [AiProviderCodec.decode]가 읽어 들이는 자리에서 부르므로
     * 화면·전환·엑셀 어디서 보든 같은 줄 순서다.
     *
     * [order]와 **같은 우선순위 규칙**이되 활성·쿨다운·키 유무로 자리를 바꾸지 않는다.
     * 사용자가 끌어 놓은 그 줄 순서가 곧 우선순위이므로, 읽는 쪽이 각자 정렬하면 **화면이 보여
     * 주는 순서와 실제 전환 순서가 갈리고**, 그 어긋남은 한도에 걸리기 전까지 드러나지 않는다.
     */
    fun displayOrder(configs: List<AiProviderConfig>): List<AiProviderConfig> =
        configs.sortedWith(compareBy({ it.priority }, { it.createdAt }))

    /**
     * 끌어 놓은 결과를 우선순위 값으로 굳힌다 — 화면의 줄 순서가 그대로 0,1,2…가 된다.
     * 값이 이미 그러하면 같은 객체를 돌려주므로 호출측이 **바뀐 것만** 저장할 수 있다.
     */
    fun withPriorities(ordered: List<AiProviderConfig>): List<AiProviderConfig> =
        ordered.mapIndexed { index, config ->
            if (config.priority == index) config else config.copy(priority = index)
        }

    /**
     * 고지가 가리킬 **밀린 곳과 그 사유** — 한 값이다.
     *
     * **둘을 한 값으로 묶은 것이 요점이다**(2026.08.25). 종전에는 곳만 순수 계층이 정했는데,
     * 사유가 붙으면서 *"곳은 처음 것, 사유도 그 처음 것"*이라는 짝이 생겼다. 따로 두면
     * [AiService]의 루프가 둘을 각각 갱신하게 되고, 그 순간 **곳은 A인데 사유는 C의 것**인
     * 조합이 만들어진다 — 그러면 고지가 *"'A'이(가) 이미지를 지원하지 않아"*라고 적는데
     * 정작 A는 한도로 밀린 것이라 **사용자가 멀쩡한 A의 모델을 비전 되는 것으로 바꾸러 간다.**
     * 한 값이면 그 조합이 만들어질 자리가 없다.
     */
    data class SwitchOrigin(
        val from: AiProviderRef,
        /**
         * 왜 밀렸는가. `null`은 **한도 계열**이다(종전 문구를 그대로 낸다) — 쿨다운으로 이미
         * 뒤로 밀려 있던 활성을 건너뛴 경우처럼 *어느* 한도였는지까지는 남기지 않는 자리가
         * 있어서다. [switchNote]가 가르는 것은 한도인가 아닌가뿐이라 그것으로 충분하다.
         */
        val reason: AiErrorKind? = null
    )

    /**
     * 전환이 두 번 이상 일어났을 때 **고지가 가리킬 곳**을 정한다 — 처음 밀린 곳이다.
     *
     * 덮어쓰면 A→B→C에서 고지가 *"'B' 한도로 'C'가"*가 되는데, **사용자는 B를 고른 적도 본 적도
     * 없다.** 사용자가 아는 이름은 '사용 중'으로 지정한 그것 하나이고, 한 줄짜리 고지가 가리켜야
     * 할 것도 그것이다(중간 경로는 한 줄에 담을 것이 아니다).
     *
     * **사유도 함께 처음 것이 이긴다** — A가 한도로 밀린 뒤 B가 이미지로 밀렸어도 고지가 가리킬
     * 것은 여전히 *"A가 한도로"*다. 사용자가 아는 이름이 A이므로 사유도 A가 밀린 사유여야 짝이 맞다.
     *
     * **판정을 여기 두는 이유:** 이 규칙이 사는 자리는 [AiService]의 루프인데 그곳은 Context·
     * 네트워크에 매여 **어떤 로컬 검증도 못 본다.** 한 줄짜리 규칙이라도 순수로 빼야 시험이 닿고,
     * 그러지 않으면 *"덮어쓰는 편이 최신이라 낫지 않나"*로 되돌릴 때 아무도 막지 못한다.
     */
    fun firstSwitchOrigin(
        current: SwitchOrigin?, candidate: AiProviderRef, reason: AiErrorKind?
    ): SwitchOrigin = current ?: SwitchOrigin(candidate, reason)

    /**
     * 전환 고지 한 줄 (확정 ⓑ) — **단일 소스**다.
     *
     * 조용히 바꾸면 사용자는 자기가 고른 모델의 답인 줄 알고 **다른 회사 다른 모델이 쓴 글**을
     * 받는다(개발 의도 2번 — 변수 제어). 문구를 여기 하나로 두는 이유는 B-150이 실증한 것이다:
     * 호출부에 맡기면 자리가 여섯이 되고, **빠뜨린 자리는 종전과 똑같이 조용하다.**
     *
     * [reason]으로 문구를 가른다(2026.08.25) — *"한도로 이어서 처리했습니다"*는 이미지 미지원
     * 전환에서는 거짓말이다(그 프로바이더는 한도에 걸린 적이 없다). `null`은 한도 전환이거나
     * (구분할 이유가 없던) 종전 호출부와 같아 **글자 그대로 종전 문구**를 낸다 — 회귀가 없다.
     */
    fun switchNote(from: AiProviderRef, to: AiProviderRef, reason: AiErrorKind? = null): String =
        when (reason) {
            AiErrorKind.IMAGES_UNSUPPORTED ->
                "'${from.displayName}'이(가) 이미지를 지원하지 않아 " +
                    "'${to.displayName}'(${to.model})가 이어서 처리했습니다"
            else ->
                "'${from.displayName}' 한도로 '${to.displayName}'(${to.model})가 이어서 처리했습니다"
        }

    /**
     * 결과에서 전환 고지를 뽑는다 — 전환이 없었으면 null.
     *
     * 인앱 기능 여섯이 각자 자기 고지 채널(`failures`·`notes`)에 이 **한 줄**만 얹으면 되도록
     * 판정과 문구를 함께 여기 둔다. `tools/check_ai_switch_notice.sh`가 그 등재를 기계로 본다.
     */
    fun switchNoteOf(result: AiResult): String? {
        val success = result as? AiResult.Success ?: return null
        val from = success.switchedFrom ?: return null
        val to = success.provider ?: return null
        return switchNote(from, to, success.switchedFromReason)
    }
}
