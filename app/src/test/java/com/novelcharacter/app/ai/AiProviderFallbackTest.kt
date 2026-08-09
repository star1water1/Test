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

    @Test
    fun 쿨다운을_받는_집합은_전환하는_집합과_같다() {
        for (kind in AiErrorKind.values()) {
            val switches = AiProviderFallback.dispositionOf(
                kind, retriesUsed = AiProviderFallback.RATE_LIMIT_RETRIES
            ) == Disposition.SWITCH
            assertEquals(
                "$kind — 전환하는데 쿨다운을 안 주면 다음 요청이 그 키부터 다시 두드린다",
                switches, AiProviderFallback.earnsCooldown(kind)
            )
        }
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
     * A→B→C로 두 번 밀렸을 때 고지가 가리키는 곳은 **A**다.
     * 덮어쓰면 *"'B' 한도로 'C'가"*가 되는데 **사용자는 B를 고른 적도 본 적도 없다** —
     * 아는 이름은 '사용 중'으로 지정한 그것 하나뿐이다.
     */
    @Test
    fun 두_번_밀려도_고지는_사용자가_고른_곳을_가리킨다() {
        val a = AiProviderRef("주 키", "m1")
        val b = AiProviderRef("둘째 키", "m2")
        assertEquals(a, AiProviderFallback.firstSwitchSource(null, a))
        assertEquals(
            "덮어쓰면 사용자가 본 적 없는 이름이 고지에 뜬다",
            a, AiProviderFallback.firstSwitchSource(a, b)
        )
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
