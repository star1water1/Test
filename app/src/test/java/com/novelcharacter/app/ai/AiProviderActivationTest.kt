package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.AiProviderActivation.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 저장이 활성 프로바이더를 옮기는가 (B-150 ②, 2026.08.07 사용자 확정).
 *
 * **왜 이 시험이 필요했는가:** 종전 규칙은 *"첫 프로바이더일 때만 활성"* 한 줄이었고,
 * 한도에 걸린 프로바이더를 갈아 끼우려 둘째를 등록해도 활성은 옛 것에 남았다. 인앱 기능은
 * 전부 `active()`로 나가므로 **새로 등록한 키가 한 번도 쓰이지 않았고**, 편집 창의
 * [연결 테스트]는 방금 만든 설정을 명시적으로 넘겨 초록이라 결함을 되레 가렸다.
 *
 * **이 판의 방어선은 시험 수가 아니라 그 안의 셋이고, 셋 다 시험이 유일한 검출이다:**
 * ① *"기존 항목 편집은 활성을 건드리지 않는다"* — 무너지면 사용자가 이름 하나 고치려
 *    연 순간 활성이 말없이 그쪽으로 옮겨 간다. 자율성(원칙 03) 위반이고, 종전에 없던
 *    새 사고를 이 수정이 만들어 내는 자리다.
 * ② *"키가 없으면 쓸 수 있는 활성을 밀어내지 않는다"* — 편집 창은 키 없이도 저장되므로
 *    조건이 없으면 키 없는 항목이 활성이 되어 **모든 호출이 `NO_KEY`로 죽는다.**
 *    침묵을 다른 침묵으로 바꾸는 것이라 고친 것이 없다(확정 문서 10장의 논거).
 * ③ *"활성이 아예 없으면 키가 없어도 세운다"* — ②를 모든 경우로 넓히고 싶어지는 자리인데,
 *    그러면 **첫 등록의 종전 동작이 조용히 사라진다.** 밀어낼 활성이 없을 때는 밀어내는
 *    비용도 없고, `NO_KEY`(*"키를 등록해 주세요"*)가 `NO_PROVIDER`(*"프로바이더가
 *    없습니다"* — 방금 하나 만든 사용자에게는 거짓말이다)보다 정확한 안내다.
 *    **근거가 시험에 없으면 다음 사람이 ②와 ③을 통일한다.**
 */
class AiProviderActivationTest {

    // ── ① 기존 항목 편집은 활성을 건드리지 않는다 ─────────────────────────────

    @Test
    fun 기존_항목_편집은_키가_있어도_활성을_옮기지_않는다() {
        assertEquals(
            Outcome.KEEP,
            AiProviderActivation.decide(isNew = false, hasKey = true, hasActiveProvider = true)
        )
    }

    /** 활성이 없더라도 마찬가지다 — 편집은 활성을 정하는 행위가 아니다. */
    @Test
    fun 기존_항목_편집은_활성이_없어도_세우지_않는다() {
        assertEquals(
            Outcome.KEEP,
            AiProviderActivation.decide(isNew = false, hasKey = true, hasActiveProvider = false)
        )
    }

    // ── ② 키 없는 새 항목은 쓸 수 있는 활성을 밀어내지 않는다 ─────────────────

    @Test
    fun 키_없는_새_항목은_기존_활성을_밀어내지_않는다() {
        assertEquals(
            Outcome.KEEP,
            AiProviderActivation.decide(isNew = true, hasKey = false, hasActiveProvider = true)
        )
    }

    // ── ③ 새 항목 + 키 → 옮기고 고지한다 (B-150이 고치는 그 자리) ─────────────

    @Test
    fun 키를_등록한_새_항목은_활성을_넘겨받고_고지_대상이_된다() {
        assertEquals(
            Outcome.ACTIVATE_MOVED,
            AiProviderActivation.decide(isNew = true, hasKey = true, hasActiveProvider = true)
        )
    }

    // ── ③ 활성이 없으면 키가 없어도 세운다 (종전 동작 보존, 고지는 하지 않는다) ─

    @Test
    fun 첫_등록은_키가_없어도_활성이_된다() {
        assertEquals(
            Outcome.ACTIVATE_FIRST,
            AiProviderActivation.decide(isNew = true, hasKey = false, hasActiveProvider = false)
        )
    }

    @Test
    fun 첫_등록은_키가_있어도_고지_대상이_아니다() {
        assertEquals(
            Outcome.ACTIVATE_FIRST,
            AiProviderActivation.decide(isNew = true, hasKey = true, hasActiveProvider = false)
        )
    }

    /**
     * 고지는 **옮겨 갔을 때만** 한다. 첫 등록까지 토스트를 띄우면 종전에 조용하던 동작에
     * 새 소음이 붙는다 — 알릴 것은 *바뀌었다는 사실*이지 *지정됐다는 사실*이 아니다.
     */
    @Test
    fun 활성을_세우는_두_경우_중_고지_대상은_옮겨_갈_때_하나뿐이다() {
        val announced = listOf(
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(true, true, false),
            Triple(true, false, true),
            Triple(false, true, true)
        ).filter { (isNew, hasKey, hasActive) ->
            AiProviderActivation.decide(isNew, hasKey, hasActive) == Outcome.ACTIVATE_MOVED
        }
        assertEquals(listOf(Triple(true, true, true)), announced)
    }

    // ── ④ 활성을 지우면 남은 것이 이어받는다 (B-153, 2026.08.10 사용자 확정) ─────
    //
    // **이 넷의 방어선은 개수가 아니라 그 안의 둘이다:**
    // ⓐ *"키가 있는 첫째"* — 목록 맨 앞을 그냥 세우고 싶어지는 자리인데, 그러면 키 없는
    //    것이 활성이 되어 실패가 `NO_PROVIDER`에서 `NO_KEY`로 **자리만 옮긴다.** 사용자는
    //    여전히 쓸 수 있는 프로바이더를 두고 실패를 받고, 고친 것이 없다.
    // ⓑ *"여기서 정렬하지 않는다"* — 넘겨받은 순서가 곧 화면의 순서다. 판정 안에서 다시
    //    정렬하면 **보여 주는 순서와 이어받는 순서가 갈리고**, 그 어긋남은 사용자가
    //    활성 표식을 눈으로 좇기 전까지 아무 데도 드러나지 않는다.

    private fun config(id: String) = AiProviderConfig(
        id = id,
        protocol = AiProtocol.OPENAI_COMPAT,
        displayName = id,
        baseUrl = "https://example.test/v1",
        model = "m"
    )

    @Test
    fun 키가_있는_첫째가_이어받는다_맨_앞이라도_키가_없으면_건너뛴다() {
        val remaining = listOf(config("a"), config("b"), config("c"))
        val heir = AiProviderActivation.succeedAfterDelete(remaining) { it == "b" || it == "c" }
        assertEquals("b", heir?.id)
    }

    /**
     * 키 있는 것이 하나도 없으면 등록순 첫째다 — 그때 남는 `NO_KEY`(*"키를 등록해 주세요"*)는
     * 활성이 빈 채 뜨는 문구보다 정확한 안내다([decide]의 ③이 같은 근거로 택한 것).
     */
    @Test
    fun 키가_하나도_없으면_등록순_첫째가_이어받는다() {
        val remaining = listOf(config("a"), config("b"))
        val heir = AiProviderActivation.succeedAfterDelete(remaining) { false }
        assertEquals("a", heir?.id)
    }

    @Test
    fun 남은_것이_없으면_이어받을_것도_없다() {
        assertNull(AiProviderActivation.succeedAfterDelete(emptyList()) { true })
    }

    /** ⓑ — 넘겨받은 순서를 그대로 쓴다. 안에서 다시 정렬하면 화면과 갈린다. */
    @Test
    fun 넘겨받은_순서를_그대로_쓴다_안에서_다시_정렬하지_않는다() {
        val hasKey: (String) -> Boolean = { true }
        assertEquals(
            "c",
            AiProviderActivation.succeedAfterDelete(
                listOf(config("c"), config("a"), config("b")), hasKey
            )?.id
        )
        assertEquals(
            "a",
            AiProviderActivation.succeedAfterDelete(
                listOf(config("a"), config("b"), config("c")), hasKey
            )?.id
        )
    }
}
