package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CompletionRate] — 필드 완성도의 단일 소스 (B-100 · B-90 소비처 ③).
 *
 * 이 하네스가 지키는 것은 넷이다:
 * 1. **필수가 가중된다** — 그것이 이 항목의 본래 몫이다(원칙 02: 입력량이 아니라 인사이트).
 * 2. **필수가 0개면 종전 값과 정확히 같다** — 사용자 확정 ㄴ1이 요구한 것이고,
 *    이것이 깨지면 필수를 쓰지 않는 사용자의 숫자가 이유 없이 움직인다.
 * 3. **분자는 언제나 교집합이다** — 보존 값(다른 세계관·사건 정의를 가리키는 값)이
 *    분자에 섞이면 완성도가 부풀고 100%를 넘는다. 착수 대조가 실제로 찾은 결함이다.
 * 4. **셀 칸이 없으면 산출 불가(null)다** — 0%로 내면 "필드를 안 만든 세계관"과
 *    "다 비운 캐릭터"가 같은 값이 된다.
 */
class CompletionRateTest {

    private var nextId = 1L

    private fun field(
        name: String,
        required: Boolean = false,
        type: String = "TEXT"
    ) = FieldDefinition(
        id = nextId++,
        universeId = 1L,
        key = name,
        name = name,
        type = type,
        isRequired = required
    )

    // ── 1. 필수 가중 ─────────────────────────────────────────────────────────

    @Test
    fun `필수 칸은 배수만큼 셈해진다`() {
        val required = field("이름", required = true)
        val optional = field("취미")
        val defs = listOf(required, optional)

        // 필수만 채움: 가중 없으면 1/2 = 50%, ×2면 2/3 ≈ 66.7%
        val onlyRequired = CompletionRate.of(defs, setOf(required.id), CompletionWeights(2f))!!
        assertEquals(50f, onlyRequired.plainPercent, 0.01f)
        assertEquals(200f / 3f, onlyRequired.percent, 0.01f)

        // 일반만 채움: 가중 없으면 50%, ×2면 1/3 ≈ 33.3% — 가중은 양방향이다.
        val onlyOptional = CompletionRate.of(defs, setOf(optional.id), CompletionWeights(2f))!!
        assertEquals(50f, onlyOptional.plainPercent, 0.01f)
        assertEquals(100f / 3f, onlyOptional.percent, 0.01f)
    }

    @Test
    fun `전부 채우면 배수와 무관하게 100퍼센트다`() {
        val a = field("이름", required = true)
        val b = field("취미")
        val filled = setOf(a.id, b.id)
        assertEquals(100f, CompletionRate.of(listOf(a, b), filled, CompletionWeights(1f))!!.percent, 0.01f)
        assertEquals(100f, CompletionRate.of(listOf(a, b), filled, CompletionWeights(5f))!!.percent, 0.01f)
    }

    @Test
    fun `아무것도 안 채우면 배수와 무관하게 0퍼센트다`() {
        val defs = listOf(field("이름", required = true), field("취미"))
        assertEquals(0f, CompletionRate.of(defs, emptySet(), CompletionWeights(3f))!!.percent, 0.01f)
    }

    // ── 2. 필수 0개 = 종전 동작 (사용자 확정 ㄴ1) ────────────────────────────

    @Test
    fun `필수가 하나도 없으면 가중이 값을 바꾸지 않는다`() {
        val a = field("취미")
        val b = field("특징")
        val c = field("메모")
        val defs = listOf(a, b, c)
        val filled = setOf(a.id)

        // 배수를 아무리 올려도 곱해질 칸이 없다 — 세 값이 모두 같아야 한다.
        val plain = CompletionRate.of(defs, filled, CompletionWeights.NONE)!!
        val weighted = CompletionRate.of(defs, filled, CompletionWeights(5f))!!
        assertEquals(plain.percent, weighted.percent, 0.0001f)
        assertEquals(weighted.plainPercent, weighted.percent, 0.0001f)
        assertEquals(100f / 3f, weighted.percent, 0.01f)
        assertFalse("필수가 없으면 가중이 걸리지 않는다", weighted.isWeighted)
    }

    @Test
    fun `배수가 1이면 필수가 있어도 종전 값과 같다`() {
        val defs = listOf(field("이름", required = true), field("취미"))
        val breakdown = CompletionRate.of(defs, setOf(defs[0].id), CompletionWeights(1f))!!
        assertEquals(breakdown.plainPercent, breakdown.percent, 0.0001f)
        assertFalse(breakdown.isWeighted)
    }

    // ── 3. 분자는 교집합이다 ─────────────────────────────────────────────────

    @Test
    fun `이 세계관 정의를 가리키지 않는 보존 값은 분자에 들어가지 않는다`() {
        // CharacterFieldValueMerge가 일부러 남기는 값(다른 세계관·사건 정의)이 섞인 상황.
        // 종전 여섯 자리는 이것을 그냥 세어 완성도를 부풀렸다.
        val mine = field("이름", required = true)
        val alsoMine = field("나이")
        val strayIds = setOf(9001L, 9002L, 9003L)

        val breakdown = CompletionRate.of(
            listOf(mine, alsoMine),
            strayIds + mine.id,
            CompletionWeights.NONE
        )!!
        assertEquals(1, breakdown.slotFilled)
        assertEquals(2, breakdown.slotTotal)
        assertEquals(50f, breakdown.percent, 0.01f)
    }

    @Test
    fun `보존 값이 아무리 많아도 100퍼센트를 넘지 않는다`() {
        val defs = listOf(field("이름"), field("나이"))
        val filled = defs.map { it.id }.toSet() + (100L..200L).toSet()
        val breakdown = CompletionRate.of(defs, filled, CompletionWeights(2f))!!
        assertEquals(100f, breakdown.percent, 0.01f)
        assertTrue(breakdown.percent <= 100f)
    }

    // ── 4. 세는 칸 — 계산 필드 제외 ─────────────────────────────────────────

    @Test
    fun `계산 필드는 분모에도 분자에도 들어가지 않는다`() {
        val text = field("이름")
        val calculated = field("총점", type = "CALCULATED")
        assertFalse(CompletionRate.countsAsSlot(calculated))

        // 값이 있어도(계산 필드는 저장 행이 없지만 방어적으로) 칸으로 세지 않는다.
        val breakdown = CompletionRate.of(
            listOf(text, calculated),
            setOf(text.id, calculated.id),
            CompletionWeights.NONE
        )!!
        assertEquals(1, breakdown.slotTotal)
        assertEquals(1, breakdown.slotFilled)
        assertEquals(100f, breakdown.percent, 0.01f)
    }

    @Test
    fun `필수로 켜 둔 계산 필드도 세지 않는다`() {
        // 소비처 ①②(폼·어시스턴트)와 같은 규칙이어야 한다 — 여기서만 세면 셋이 갈린다.
        val calculated = field("총점", required = true, type = "CALCULATED")
        assertNull(CompletionRate.of(listOf(calculated), emptySet(), CompletionWeights(2f)))
    }

    // ── 5. 셀 칸이 없으면 산출 불가 ──────────────────────────────────────────

    @Test
    fun `정의가 없으면 null이다`() {
        assertNull(CompletionRate.of(emptyList(), setOf(1L), CompletionWeights.DEFAULT))
        assertNull(CompletionRate.percentOf(emptyList(), emptySet(), CompletionWeights.DEFAULT))
    }

    @Test
    fun `칸이 하나라도 있으면 산출된다`() {
        assertNotNull(CompletionRate.of(listOf(field("이름")), emptySet(), CompletionWeights.DEFAULT))
    }

    // ── 6. 배수의 범위 접기 ──────────────────────────────────────────────────

    @Test
    fun `범위 밖 배수는 접힌다`() {
        assertEquals(
            CompletionWeights.MIN_REQUIRED_WEIGHT,
            CompletionWeights.clamp(0f).requiredWeight, 0.0001f
        )
        assertEquals(
            CompletionWeights.MIN_REQUIRED_WEIGHT,
            CompletionWeights.clamp(-3f).requiredWeight, 0.0001f
        )
        assertEquals(
            CompletionWeights.MAX_REQUIRED_WEIGHT,
            CompletionWeights.clamp(99f).requiredWeight, 0.0001f
        )
    }

    @Test
    fun `NaN은 기본값으로 접힌다`() {
        // 저장소·엑셀·복원 어디서 와도 계산이 깨지지 않아야 한다.
        assertEquals(
            CompletionWeights.DEFAULT_REQUIRED_WEIGHT,
            CompletionWeights.clamp(Float.NaN).requiredWeight, 0.0001f
        )
    }

    @Test
    fun `기본 배수는 1이 아니다`() {
        // 1이면 "기본 배수를 둔다"(사용자 확정 ㄱ1)가 아무 일도 하지 않는 설정이 된다.
        assertTrue(
            CompletionWeights.DEFAULT_REQUIRED_WEIGHT > CompletionWeights.MIN_REQUIRED_WEIGHT
        )
    }

    // ── 7. 내역 ─────────────────────────────────────────────────────────────

    @Test
    fun `내역이 필수와 일반을 나눠 든다`() {
        val r1 = field("이름", required = true)
        val r2 = field("나이", required = true)
        val o1 = field("취미")
        val breakdown = CompletionRate.of(
            listOf(r1, r2, o1), setOf(r1.id, o1.id), CompletionWeights(2f)
        )!!
        assertEquals(2, breakdown.requiredTotal)
        assertEquals(1, breakdown.requiredFilled)
        assertEquals(1, breakdown.optionalTotal)
        assertEquals(1, breakdown.optionalFilled)
        assertTrue("필수가 있고 배수가 1이 아니면 가중이 걸린다", breakdown.isWeighted)
        // (1×2 + 1) / (2×2 + 1) = 3/5
        assertEquals(60f, breakdown.percent, 0.01f)
    }
}
