package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.AiProviderFallback.Disposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프로바이더 자동 전환 (B-108, 2026.08.04 사용자 확정 7-1).
 *
 * **이 판의 방어선은 시험 수가 아니라 그 안의 넷이고, 넷 다 시험이 유일한 검출이다:**
 *
 * ① *"아무것도 실패하지 않으면 종전과 글자 그대로 같은 곳이 답한다"* — 순서가 활성을 앞에
 *    두지 않으면 **한도에 걸린 적도 없는 사용자의 요청이 다른 모델로 나간다.** 회귀가 새
 *    기능이 아니라 **옛 기능**에 나는 자리라, 화면에는 아무 표시도 안 나고 결과의 문체만
 *    조용히 달라진다(B-115가 *"둘일 때가 종전과 똑같은가"*를 못 박은 것과 같은 축).
 *
 * ② *"한도가 아닌 실패는 전환하지 않는다"* — [AiErrorKind.INVALID_KEY]는 *"그 키로는 영영
 *    안 되는 것"*이라는 점에서 크레딧 소진과 닮아 **한 갈래로 묶고 싶어지는 자리**다.
 *    묶으면 틀린 키가 둘째 프로바이더의 성공으로 덮여 **사용자가 영영 고치지 않는다** —
 *    고칠 곳이 있는 실패를 조용한 성공으로 바꾸는 것이라 변수 제어의 정반대다.
 *
 * ③ *"쿨다운은 빼는 것이 아니라 미루는 것이다"* — 빼면 전부 한도일 때 두드릴 곳이 없어
 *    *"프로바이더가 없습니다"*가 나가는데, 등록된 키는 멀쩡히 있다. **거짓말을 하는
 *    실패**이고 교정 경로도 틀린 곳을 가리킨다.
 *
 * ④ *"우선순위를 한 번도 손대지 않은 사용자는 옛 순서 그대로다"* — 전부 0이면 정렬이
 *    `createdAt`으로 떨어져야 한다. 무너지면 **마이그레이션 없이 칸을 늘린 전제**가 함께
 *    무너져, 구버전 설정을 읽은 순간 순서가 뒤집힌다.
 */
class AiProviderFallbackTest {

    private fun config(
        id: String,
        priority: Int = 0,
        createdAt: Long = 0L,
        cooldownUntilMillis: Long? = null
    ) = AiProviderConfig(
        id = id,
        protocol = AiProtocol.ANTHROPIC,
        displayName = id,
        baseUrl = "https://example.test",
        model = "model-$id",
        createdAt = createdAt,
        priority = priority,
        cooldownUntilMillis = cooldownUntilMillis
    )

    private val always: (String) -> Boolean = { true }

    // ── ① 평소에는 종전과 같은 곳이 답한다 ────────────────────────────────────

    @Test
    fun 활성이_언제나_맨_앞이다_우선순위가_더_낮은_것이_있어도() {
        val active = config("active", priority = 9, createdAt = 100)
        val cheaper = config("cheap", priority = 0, createdAt = 1)
        val order = AiProviderFallback.order(
            configs = listOf(cheaper, active), active = active, hasKey = always, nowMillis = 0
        )
        assertEquals(
            "우선순위는 *전환할 때 어디로 가는가*의 순서다 — 사용자가 고른 활성을 밀어내면 " +
                "한도에 걸린 적 없는 요청이 다른 모델로 나간다",
            "active", order.first().id
        )
    }

    @Test
    fun 활성_하나뿐이면_순서도_하나다() {
        val active = config("only")
        val order = AiProviderFallback.order(
            configs = listOf(active), active = active, hasKey = always, nowMillis = 0
        )
        assertEquals(listOf("only"), order.map { it.id })
    }

    // ── ② 방아쇠는 한도 둘뿐이다 ──────────────────────────────────────────────

    @Test
    fun 요청한도는_먼저_재시도하고_그_다음에_전환한다() {
        assertEquals(
            "429는 *잠시 후 되는 것*이라 곧바로 갈아치우면 일시적 혼잡에 키가 바뀐다",
            Disposition.RETRY_SAME,
            AiProviderFallback.dispositionOf(AiErrorKind.RATE_LIMITED, retriesUsed = 0)
        )
        assertEquals(
            "재시도까지 실패하면 옮긴다 — 그러지 않으면 멀쩡한 둘째 키를 두고 실패를 받는다",
            Disposition.SWITCH,
            AiProviderFallback.dispositionOf(
                AiErrorKind.RATE_LIMITED, retriesUsed = AiProviderFallback.RATE_LIMIT_RETRIES
            )
        )
    }

    @Test
    fun 크레딧_소진은_재시도_없이_곧바로_전환한다() {
        assertEquals(
            Disposition.SWITCH,
            AiProviderFallback.dispositionOf(AiErrorKind.QUOTA_EXCEEDED, retriesUsed = 0)
        )
    }

    /** ② — 이 시험이 없으면 다음 사람이 "영영 안 되는 것"이라는 닮은 점을 보고 통일한다. */
    @Test
    fun 잘못된_키는_전환하지_않는다_다른_곳의_성공으로_덮으면_영영_고치지_않는다() {
        assertEquals(
            Disposition.STOP,
            AiProviderFallback.dispositionOf(AiErrorKind.INVALID_KEY, retriesUsed = 0)
        )
    }

    @Test
    fun 한도가_아닌_실패는_전부_그대로_돌려준다() {
        val notLimits = listOf(
            AiErrorKind.NETWORK, AiErrorKind.TIMEOUT, AiErrorKind.SERVER,
            AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.BAD_REQUEST,
            AiErrorKind.EMPTY_RESPONSE, AiErrorKind.NO_KEY, AiErrorKind.NO_PROVIDER,
            AiErrorKind.UNKNOWN
        )
        for (kind in notLimits) {
            assertEquals(
                "$kind 는 전환이 답이 아니다",
                Disposition.STOP, AiProviderFallback.dispositionOf(kind, retriesUsed = 0)
            )
        }
    }

    /**
     * 한도 계열은 **전환과 쿨다운이 한 벌이다** — 전환하는데 쿨다운을 안 주면 다음 요청이
     * 그 키부터 다시 두드린다. 예외는 [AiErrorKind.IMAGES_UNSUPPORTED] 하나뿐이고
     * 아래 시험이 그것을 따로 못 박는다.
     */
    @Test
    fun 이미지_미지원을_뺀_전환_집합은_쿨다운_집합과_같다() {
        for (kind in AiErrorKind.values()) {
            if (kind == AiErrorKind.IMAGES_UNSUPPORTED) continue
            val switches = AiProviderFallback.dispositionOf(
                kind, retriesUsed = AiProviderFallback.RATE_LIMIT_RETRIES
            ) == Disposition.SWITCH
            assertEquals(
                "$kind — 전환하는데 쿨다운을 안 주면 다음 요청이 그 키부터 다시 두드린다",
                switches, AiProviderFallback.earnsCooldown(kind)
            )
        }
    }

    // ── ⑤ 이미지 미지원은 전환하되 쿨다운은 받지 않는다 (2026.08.25 사용자 요청) ──────

    /**
     * 사용자 요청: *"이미지 안 받는 api모델에 이미지 보내서 실패하면 바로 다음 모델로
     * 변경해서 재시도하고 그것도 안 되면 다음 모델로 가고."*
     *
     * 재시도 횟수를 안 보는 것이 요점이다 — 같은 프로바이더에 다시 물어도 그 모델은 여전히
     * 이미지를 거부한다. 429처럼 한 번 더 두드리면 왕복만 늘고 사용감은 더 뻑뻑해진다.
     */
    @Test
    fun 이미지_미지원은_재시도_없이_곧바로_다음_모델로_간다() {
        assertEquals(
            Disposition.SWITCH,
            AiProviderFallback.dispositionOf(AiErrorKind.IMAGES_UNSUPPORTED, retriesUsed = 0)
        )
    }

    // ── ⑥ 전환은 *얻을 것이 있을 때* 한다 (콜드 검토 2026.08.25) ──────────────────

    private fun noVision(id: String, priority: Int = 0) =
        config(id, priority = priority).copy(imagesUnsupported = true)

    /**
     * **이 시험이 막는 회귀가 이 판에서 실제로 났다.** 처음 구현은 `allowImageSwitch`를
     * *"마지막 후보가 아닌가"*로 물었는데, 등록된 곳이 **전부** 거부를 배운 상태에서는
     * 셋 다 똑같이 글만 보낼 수 있는데도 활성 A를 건너뛰고 B도 건너뛰어 **맨 뒤 C가 답했다.**
     * 사용자가 고르지 않은 곳이 답하는 것이고, 얻은 것은 없다.
     */
    @Test
    fun 전부_거부를_배웠으면_넘기지_않는다_활성이_답해야_한다() {
        val chain = listOf(noVision("active"), noVision("b", 1), noVision("c", 2))
        assertFalse(
            "넘겨도 결국 글만 보내게 된다 — 그러면 사용자가 고른 활성이 답해야 한다",
            AiProviderFallback.hasImageCapableCandidateAfter(chain, 0)
        )
    }

    /** 뒤에 받아 줄 여지가 있으면 넘긴다 — 이 판이 사용자 요청으로 산 동작이다. */
    @Test
    fun 뒤에_받아_줄_곳이_남았으면_넘긴다() {
        val chain = listOf(noVision("active"), config("vision", priority = 1))
        assertTrue(AiProviderFallback.hasImageCapableCandidateAfter(chain, 0))
    }

    /**
     * **아직 안 배운 곳(`null`)은 "받아 줄 여지"다.** 거부로 치면 첫 시도의 기회가 사라지고
     * 그 곳은 영영 못 배운다 — 배우는 유일한 경로가 실제로 보내 보는 것이다.
     */
    @Test
    fun 아직_안_배운_곳은_받아_줄_여지로_친다() {
        val chain = listOf(noVision("active"), config("unknown", priority = 1))
        assertNull("전제 — 아직 배운 것이 없다", chain[1].imagesUnsupported)
        assertTrue(AiProviderFallback.hasImageCapableCandidateAfter(chain, 0))
    }

    /** 마지막 후보 뒤에는 아무도 없다 — 여기서는 물러서서 글로라도 답해야 한다. */
    @Test
    fun 마지막_후보_뒤에는_넘길_곳이_없다() {
        val chain = listOf(config("a"), config("b", priority = 1))
        assertFalse(AiProviderFallback.hasImageCapableCandidateAfter(chain, chain.lastIndex))
    }

    /** 프로바이더가 하나뿐이면 전환 자체가 없다 — 종전과 글자 그대로 같아야 한다. */
    @Test
    fun 하나뿐이면_넘길_곳이_없다() {
        assertFalse(AiProviderFallback.hasImageCapableCandidateAfter(listOf(config("only")), 0))
    }

    /**
     * **[AiErrorKind.INVALID_KEY]와 갈리는 자리다.** 둘 다 *"같은 곳에 다시 물어도 영영 안
     * 되는 것"*이지만 틀린 키는 **사용자가 고쳐야 할 실수**라 다른 곳의 성공으로 덮으면
     * 영영 못 본다. 이미지 미지원에는 고칠 실수가 없다 — 키도 설정도 멀쩡하고, 그 모델이
     * 비전을 안 할 뿐이다. 이 시험이 없으면 다음 사람이 닮은 점을 보고 둘을 통일한다.
     */
    @Test
    fun 이미지_미지원은_전환하고_잘못된_키는_전환하지_않는다() {
        assertEquals(
            Disposition.SWITCH,
            AiProviderFallback.dispositionOf(AiErrorKind.IMAGES_UNSUPPORTED, retriesUsed = 0)
        )
        assertEquals(
            Disposition.STOP,
            AiProviderFallback.dispositionOf(AiErrorKind.INVALID_KEY, retriesUsed = 0)
        )
    }

    /**
     * 쿨다운은 *"이 프로바이더가 잠시 못 쓴다"*는 뜻인데, 이미지를 거부한 프로바이더는
     * **텍스트만 있는 다음 요청에는 여전히 멀쩡하다.** 주면 이미지 요청 하나 때문에 10분간
     * 텍스트 요청까지 그 프로바이더가 뒤로 밀린다 — `imagesUnsupported` 학습값이 이미
     * "이미지를 실을 때만 건너뛴다"는 더 좁은 회피를 맡고 있어 겹쳐 막을 이유가 없다.
     */
    @Test
    fun 이미지_미지원은_전환하되_쿨다운은_받지_않는다() {
        assertEquals(
            Disposition.SWITCH,
            AiProviderFallback.dispositionOf(AiErrorKind.IMAGES_UNSUPPORTED, retriesUsed = 0)
        )
        assertFalse(
            "텍스트 요청에는 이 프로바이더가 여전히 멀쩡하다 — 뒤로 미룰 이유가 없다",
            AiProviderFallback.earnsCooldown(AiErrorKind.IMAGES_UNSUPPORTED)
        )
    }

    // ── ③ 쿨다운은 미루는 것이지 빼는 것이 아니다 ────────────────────────────

    @Test
    fun 쿨다운_중인_것은_맨_뒤로_가되_목록에서_사라지지_않는다() {
        val now = 1_000_000L
        val active = config("active", cooldownUntilMillis = now + 60_000)
        val other = config("other", priority = 5, createdAt = 50)
        val order = AiProviderFallback.order(
            configs = listOf(active, other), active = active, hasKey = always, nowMillis = now
        )
        assertEquals(listOf("other", "active"), order.map { it.id })
    }

    @Test
    fun 전부_쿨다운이어도_두드릴_곳이_남는다() {
        val now = 1_000_000L
        val a = config("a", cooldownUntilMillis = now + 60_000)
        val b = config("b", priority = 1, cooldownUntilMillis = now + 60_000)
        val order = AiProviderFallback.order(
            configs = listOf(a, b), active = a, hasKey = always, nowMillis = now
        )
        assertEquals(
            "빼면 '프로바이더가 없습니다'가 나가는데 등록된 키는 멀쩡히 있다 — 거짓말하는 실패다",
            listOf("a", "b"), order.map { it.id }
        )
    }

    @Test
    fun 지난_쿨다운은_쿨다운이_아니다() {
        val now = 1_000_000L
        assertFalse(
            AiProviderFallback.isCoolingDown(config("a", cooldownUntilMillis = now - 1), now)
        )
        assertTrue(
            AiProviderFallback.isCoolingDown(config("a", cooldownUntilMillis = now + 1), now)
        )
    }

    /**
     * 저장값은 벽시계라 사용자가 시계를 뒤로 돌리면 *"10분"*이 며칠이 된다.
     * 화면 어디에도 "쿨다운"을 되돌리는 길이 없으므로, 굳으면 원인도 교정 경로도 없는 침묵이다.
     */
    @Test
    fun 상한을_넘는_잔여는_시계가_어긋난_것이지_쿨다운이_아니다() {
        val now = 1_000_000L
        val skewed = config("a", cooldownUntilMillis = now + AiProviderFallback.COOLDOWN_MILLIS + 1)
        assertFalse(AiProviderFallback.isCoolingDown(skewed, now))
        // 경계값은 여전히 쿨다운이다 — 방금 넣은 것이 곧바로 만료로 읽히면 기능이 죽는다.
        val fresh = config("a", cooldownUntilMillis = AiProviderFallback.cooldownUntil(now))
        assertTrue(AiProviderFallback.isCoolingDown(fresh, now))
    }

    // ── ⓓ 키 없는 프로바이더 · ⓔ 한 요청당 최대 3곳 ──────────────────────────

    @Test
    fun 키_없는_프로바이더는_순회_전에_걸러진다() {
        val active = config("active")
        val keyless = config("keyless", priority = 1)
        val order = AiProviderFallback.order(
            configs = listOf(active, keyless), active = active,
            hasKey = { it != "keyless" }, nowMillis = 0
        )
        assertEquals(listOf("active"), order.map { it.id })
    }

    @Test
    fun 한_요청은_최대_세_곳만_두드린다() {
        val active = config("p0")
        val configs = listOf(active) + (1..6).map { config("p$it", priority = it) }
        val order = AiProviderFallback.order(
            configs = configs, active = active, hasKey = always, nowMillis = 0
        )
        assertEquals(AiProviderFallback.MAX_PROVIDERS_PER_REQUEST, order.size)
        assertEquals(listOf("p0", "p1", "p2"), order.map { it.id })
    }

    /** 활성이 목록에서 사라진 순간(방금 삭제 등)에도 관문이 들고 온 그것이 앞자리다. */
    @Test
    fun 목록에_없는_활성도_앞자리를_지킨다() {
        val active = config("gone")
        val other = config("other")
        val order = AiProviderFallback.order(
            configs = listOf(other), active = active, hasKey = always, nowMillis = 0
        )
        assertEquals(listOf("gone", "other"), order.map { it.id })
    }

    // ── ④ 우선순위 — 손대지 않은 사용자는 옛 순서 그대로 ─────────────────────

    @Test
    fun 우선순위를_손대지_않으면_만든_순서_그대로다() {
        val first = config("first", createdAt = 10)
        val second = config("second", createdAt = 20)
        val third = config("third", createdAt = 30)
        assertEquals(
            "전부 0이면 정렬이 createdAt으로 떨어져야 한다 — 마이그레이션 없이 칸을 늘린 전제다",
            listOf("first", "second", "third"),
            AiProviderFallback.displayOrder(listOf(third, first, second)).map { it.id }
        )
    }

    @Test
    fun 우선순위가_만든_순서를_이긴다() {
        val old = config("old", priority = 2, createdAt = 1)
        val new = config("new", priority = 0, createdAt = 99)
        assertEquals(
            listOf("new", "old"),
            AiProviderFallback.displayOrder(listOf(old, new)).map { it.id }
        )
    }

    @Test
    fun 끌어_놓은_줄_순서가_그대로_우선순위가_된다() {
        val ordered = listOf(config("b", priority = 7), config("a", priority = 3))
        val stamped = AiProviderFallback.withPriorities(ordered)
        assertEquals(listOf(0, 1), stamped.map { it.priority })
        assertEquals(listOf("b", "a"), stamped.map { it.id })
    }

    /**
     * 안 바뀐 항목은 **같은 객체**여야 한다 — 호출측이 그것으로 저장 대상을 고른다.
     * 전부 저장하면 자리를 지킨 프로바이더까지 `updatedAt`이 갱신되어, 편집한 적 없는 것이
     * 방금 손댄 것처럼 보인다.
     */
    @Test
    fun 자리를_지킨_항목은_같은_객체로_돌아온다() {
        val kept = config("kept", priority = 0)
        val moved = config("moved", priority = 5)
        val stamped = AiProviderFallback.withPriorities(listOf(kept, moved))
        assertSame(kept, stamped[0])
        assertEquals(1, stamped[1].priority)
    }

    // ── ⓑ 고지 — 조용히 바꾸지 않는다 ────────────────────────────────────────

    @Test
    fun 전환이_없었으면_고지도_없다() {
        val plain = AiResult.Success(text = "ok", model = "m", provider = AiProviderRef("A", "m"))
        assertNull(AiProviderFallback.switchNoteOf(plain))
    }

    @Test
    fun 실패에는_경로_고지를_붙이지_않는다() {
        val failure = AiResult.Failure(AiErrorKind.QUOTA_EXCEEDED)
        assertNull(AiProviderFallback.switchNoteOf(failure))
    }

    @Test
    fun 전환하면_어디서_어디로_갔는지_말한다() {
        val switched = AiResult.Success(
            text = "ok",
            model = "claude-x",
            provider = AiProviderRef("백업 키", "claude-x"),
            switchedFrom = AiProviderRef("주 키", "gpt-y")
        )
        val note = AiProviderFallback.switchNoteOf(switched)
        assertTrue("어디서 밀렸는지", note!!.contains("주 키"))
        assertTrue("누가 이어받았는지", note.contains("백업 키"))
        assertTrue("무슨 모델이 썼는지 — 문체가 달라진 이유가 그것이다", note.contains("claude-x"))
    }

    /**
     * 이미지 때문에 밀린 전환에 *"한도로"*라고 적으면 **거짓말이다** — 그 프로바이더는 한도에
     * 걸린 적이 없고, 사용자는 멀쩡한 결제 상태를 확인하러 간다(변수 제어의 반대).
     */
    @Test
    fun 이미지_때문에_밀렸으면_한도라고_말하지_않는다() {
        val switched = AiResult.Success(
            text = "ok",
            model = "gpt-vision",
            provider = AiProviderRef("비전 되는 키", "gpt-vision"),
            switchedFrom = AiProviderRef("주 키", "text-only"),
            switchedFromReason = AiErrorKind.IMAGES_UNSUPPORTED
        )
        val note = AiProviderFallback.switchNoteOf(switched)!!
        assertFalse("한도에 걸린 적이 없는데 한도라고 말하면 엉뚱한 곳을 고치러 간다", note.contains("한도"))
        assertTrue("왜 밀렸는지", note.contains("이미지"))
        assertTrue("어디서 밀렸는지", note.contains("주 키"))
        assertTrue("누가 이어받았는지", note.contains("비전 되는 키"))
    }

    /** 사유가 없으면(한도 전환·종전 호출부) **글자 그대로 종전 문구**다 — 회귀가 없다. */
    @Test
    fun 사유를_안_적으면_종전_한도_문구_그대로다() {
        val from = AiProviderRef("주 키", "m1")
        val to = AiProviderRef("백업 키", "m2")
        assertEquals(
            AiProviderFallback.switchNote(from, to),
            AiProviderFallback.switchNote(from, to, AiErrorKind.RATE_LIMITED)
        )
        assertTrue(AiProviderFallback.switchNote(from, to).contains("한도"))
    }

    /**
     * A→B→C로 두 번 밀렸을 때 고지가 가리키는 곳은 **A**다.
     * 덮어쓰면 *"'B' 한도로 'C'가"*가 되는데 **사용자는 B를 고른 적도 본 적도 없다** —
     * 아는 이름은 '사용 중'으로 지정한 그것 하나뿐이다.
     */
    @Test
    fun 두_번_밀려도_고지는_사용자가_고른_곳을_가리킨다() {
        val a = AiProviderRef("주 키", "m1")
        val b = AiProviderRef("둘째 키", "m2")
        assertEquals(
            a,
            AiProviderFallback.firstSwitchOrigin(null, a, AiErrorKind.QUOTA_EXCEEDED).from
        )
        val first = AiProviderFallback.SwitchOrigin(a, AiErrorKind.QUOTA_EXCEEDED)
        assertEquals(
            "덮어쓰면 사용자가 본 적 없는 이름이 고지에 뜬다",
            a,
            AiProviderFallback.firstSwitchOrigin(first, b, AiErrorKind.IMAGES_UNSUPPORTED).from
        )
    }

    /**
     * **곳과 사유는 한 값이라 갈릴 수 없다.** A가 한도로 밀린 뒤 B가 이미지로 밀렸을 때
     * 사유까지 덮어쓰면 고지가 *"'A'이(가) 이미지를 지원하지 않아"*가 되는데, **A는 한도로
     * 밀린 것이라 멀쩡한 A의 모델을 비전 되는 것으로 바꾸러 간다.** 틀린 곳을 고치라고
     * 시키는 부류이고, 이 저장소가 반복해 이름 붙인 그 결함이다.
     */
    @Test
    fun 곳이_처음_것이면_사유도_처음_것이다() {
        val a = AiProviderRef("주 키", "m1")
        val b = AiProviderRef("둘째 키", "m2")
        val first = AiProviderFallback.firstSwitchOrigin(null, a, AiErrorKind.QUOTA_EXCEEDED)
        val after = AiProviderFallback.firstSwitchOrigin(first, b, AiErrorKind.IMAGES_UNSUPPORTED)
        assertEquals(a, after.from)
        assertEquals(
            "곳과 사유가 갈리면 고지가 멀쩡한 곳을 고치라고 시킨다",
            AiErrorKind.QUOTA_EXCEEDED, after.reason
        )
    }

    /**
     * **전환 방아쇠가 넷째로 늘 때 문구를 함께 안 고치면 여기서 실패한다.**
     * `switchNote`의 `else` 가지가 *"한도로"*라, 한도가 아닌 새 사유가 붙으면 **조용히
     * 거짓말하는 고지**가 된다(사용자는 멀쩡한 결제 상태를 확인하러 간다). 사람이 기억하는
     * 것에 기대지 않으려고 기계로 잠근다 — `tools/check_ai_switch_notice.sh`가 *부르는가*를
     * 보는 것과 같은 자리에서 이쪽은 *참인가*를 본다.
     */
    @Test
    fun 전환하는_모든_사유에_참인_문구가_붙는다() {
        val from = AiProviderRef("주 키", "m1")
        val to = AiProviderRef("백업 키", "m2")
        for (kind in AiErrorKind.values()) {
            val switches = AiProviderFallback.dispositionOf(
                kind, retriesUsed = AiProviderFallback.RATE_LIMIT_RETRIES
            ) == Disposition.SWITCH
            if (!switches) continue
            val isLimit =
                kind == AiErrorKind.RATE_LIMITED || kind == AiErrorKind.QUOTA_EXCEEDED
            assertEquals(
                "$kind — '한도로'는 한도 계열에서만 참이다. 새 전환 사유를 더했으면 " +
                    "switchNote의 가지도 함께 더할 것",
                isLimit, AiProviderFallback.switchNote(from, to, kind).contains("한도")
            )
        }
    }

    // ── 학습값 등재 (R-23) ────────────────────────────────────────────────────

    /**
     * *"이 키는 한도에 걸렸다"*도 학습한 사실이다(확정 7-1의 착수 지시).
     * 등재하지 않으면 R-23 초기화 고지에서 빠져, **모델을 바꿨는데도 옛 쿨다운 때문에 그
     * 프로바이더가 계속 뒤로 밀리는 이유**를 사용자가 볼 수 없다.
     */
    @Test
    fun 쿨다운은_학습한_사실이라_초기화_고지_대상이다() {
        val base = config("a")
        assertFalse(base.hasLearnedFacts())
        assertTrue(base.copy(cooldownUntilMillis = 1L).hasLearnedFacts())
    }

    /** 우선순위는 사용자가 정한 것이라 학습값이 아니다 — 모델을 바꿨다고 지우면 유실이다. */
    @Test
    fun 우선순위는_학습값이_아니다() {
        assertFalse(config("a", priority = 3).hasLearnedFacts())
    }

    /** 단가도 우선순위와 같은 성격이다 — 모델을 바꿨다고 적어 둔 단가를 지우면 유실이다. */
    @Test
    fun 단가는_학습값이_아니다() {
        val priced = config("a").copy(
            inputPricePerMillionTokens = 3.0, outputPricePerMillionTokens = 15.0
        )
        assertFalse(priced.hasLearnedFacts())
    }

    // ── 관문이 결과에 표식을 새긴다 ──────────────────────────────────────────

    @Test
    fun 성공에도_어느_프로바이더였는지_새긴다() {
        val ref = AiProviderRef("A", "m")
        val stamped = AiResult.Success(text = "t", model = "m").withProvider(ref)
        assertEquals(ref, (stamped as AiResult.Success).provider)
    }

    @Test
    fun 전환이_없으면_표식도_붙지_않는다() {
        val plain = AiResult.Success(text = "t", model = "m").withSwitchedFrom(null)
        assertNull((plain as AiResult.Success).switchedFrom)
    }
}
