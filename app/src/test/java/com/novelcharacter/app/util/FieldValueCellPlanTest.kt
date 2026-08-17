package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FieldValueCellPlan] — 필드값 한 칸의 처분 (B-187).
 *
 * **이 판정이 갈리면 조용히 틀린다.** 가져오기 여섯 자리와 미리보기 여섯 자리가 이것 하나를
 * 부르므로, 여기가 맞으면 *예고한 것과 실제로 하는 것*이 구조적으로 같아진다. 반대로 여기가
 * 틀리면 열두 자리가 **같은 방향으로 함께** 틀린다 — 그래서 갈래를 하나도 빼지 않고 잰다.
 *
 * 미리보기의 계수 자체는 DB에 묶여 순수 하네스가 닿지 못한다. 그러나 **그 계수의 근거인
 * 이 판정은 순수하다** — 방어선을 세울 수 있는 자리가 여기다.
 */
class FieldValueCellPlanTest {

    // ── ① 값이 있는 칸 — 기존이 없으면 신규, 다르면 변경, 같으면 동일 ──────────────

    @Test
    fun `값이 있고 기존이 없으면 신규다`() {
        assertEquals(
            FieldValueCellEffect.NEW,
            FieldValueCellPlan.effectOf("붉은색", existingValue = null)
        )
    }

    @Test
    fun `값이 있고 기존과 다르면 변경이다`() {
        assertEquals(
            FieldValueCellEffect.UPDATE,
            FieldValueCellPlan.effectOf("푸른색", existingValue = "붉은색")
        )
    }

    @Test
    fun `값이 있고 기존과 같으면 동일이다`() {
        assertEquals(
            FieldValueCellEffect.UNCHANGED,
            FieldValueCellPlan.effectOf("붉은색", existingValue = "붉은색")
        )
    }

    /**
     * **무편집 왕복이 걸리는 자리다.** 내보낸 파일을 그대로 되가져오면 모든 칸이 이 갈래이고,
     * 그때 미리보기는 '동일'이라 말해야 하며 가져오기는 **아무것도 쓰지 않아야** 한다.
     * 종전에는 같은 값을 그대로 `update`로 다시 썼다(목표 규모에서 필드값만 36,510행).
     */
    @Test
    fun `동일은 신규-변경과 반드시 갈린다`() {
        val same = FieldValueCellPlan.effectOf("값", existingValue = "값")
        assertEquals(FieldValueCellEffect.UNCHANGED, same)
    }

    // ── ② 빈 칸 — 열이 있으면 비움, 없으면 아무 일도 없다 ─────────────────────────
    // 빈 셀의 뜻은 **그 열이 파일에 있었는가**에 달렸다(F1-A). 열이 있는데 비었으면 *비움 의도*,
    // 열 자체가 없으면 *이 파일은 그 값에 대해 아무 말도 하지 않았다*이므로 기존 값을 지키다.

    @Test
    fun `열이 있고 셀이 비었고 기존이 있으면 비움이다`() {
        assertEquals(
            FieldValueCellEffect.CLEAR,
            FieldValueCellPlan.effectOf("", existingValue = "붉은색", columnPresent = true)
        )
    }

    @Test
    fun `열이 없으면 빈 셀이 기존 값을 지우지 않는다`() {
        assertEquals(
            FieldValueCellEffect.NONE,
            FieldValueCellPlan.effectOf("", existingValue = "붉은색", columnPresent = false)
        )
    }

    @Test
    fun `셀도 비고 기존도 없으면 아무 일도 없다`() {
        assertEquals(
            FieldValueCellEffect.NONE,
            FieldValueCellPlan.effectOf("", existingValue = null, columnPresent = true)
        )
    }

    // ── ③ 공백만 있는 칸은 빈 칸이다 ──────────────────────────────────────────────
    // 가져오기 여섯 자리가 전부 `isNotBlank()`로 갈랐다. `isEmpty()`로 바꾸면 공백 한 칸이
    // **값으로 저장되어** 통계·수식에 유령 값이 생기고, 비움 의도가 조용히 무시된다.

    @Test
    fun `공백만 있는 셀은 비움으로 읽는다`() {
        assertEquals(
            FieldValueCellEffect.CLEAR,
            FieldValueCellPlan.effectOf("   ", existingValue = "붉은색", columnPresent = true)
        )
    }

    @Test
    fun `공백만 있는 셀은 기존이 없으면 아무 일도 없다`() {
        assertEquals(
            FieldValueCellEffect.NONE,
            FieldValueCellPlan.effectOf("\t ", existingValue = null, columnPresent = true)
        )
    }

    // ── ④ `null`과 빈 문자열은 다르다 ─────────────────────────────────────────────
    // 전자는 **행이 없는 것**이고 후자는 **빈 값이 저장된 것**이다. 둘을 같게 다루면
    // 빈 값이 저장된 칸에 값을 넣을 때 `insert`를 쳐 유니크 색인을 깬다.

    @Test
    fun `기존이 빈 문자열이면 값 넣기는 신규가 아니라 변경이다`() {
        assertEquals(
            FieldValueCellEffect.UPDATE,
            FieldValueCellPlan.effectOf("붉은색", existingValue = "")
        )
    }

    @Test
    fun `기존이 빈 문자열이면 빈 셀은 비움이다`() {
        assertEquals(
            FieldValueCellEffect.CLEAR,
            FieldValueCellPlan.effectOf("", existingValue = "", columnPresent = true)
        )
    }

    // ── ⑤ 열 유무는 값이 있는 칸의 답을 바꾸지 않는다 ────────────────────────────
    // 값이 실려 있다는 것 자체가 열이 있다는 뜻이라, 이 인자가 그쪽까지 흔들면 안 된다.

    @Test
    fun `값이 있으면 열 유무와 무관하게 같은 답이다`() {
        for (present in listOf(true, false)) {
            assertEquals(
                FieldValueCellEffect.NEW,
                FieldValueCellPlan.effectOf("값", existingValue = null, columnPresent = present)
            )
            assertEquals(
                FieldValueCellEffect.UPDATE,
                FieldValueCellPlan.effectOf("새 값", existingValue = "옛 값", columnPresent = present)
            )
        }
    }
}
