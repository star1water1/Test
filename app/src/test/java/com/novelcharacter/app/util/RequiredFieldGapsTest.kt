package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RequiredFieldGaps] — 어시스턴트가 세는 '필수인데 비어 있는 칸'(B-99 · B-90 소비처 ②).
 *
 * 이 하네스가 지키는 것은 넷이다:
 * 1. **세는 규칙이 폼과 같다** — 필수이면서 계산 필드가 아닌 것. 카드가 말한 칸 수와
 *    열어서 보는 칸 수가 갈리면 그 카드는 없느니만 못하다.
 * 2. **세계관 경계를 넘지 않는다** — 다른 세계관의 필수 필드를 남의 항목에 물리면
 *    영원히 안 사라지는 카드가 된다(사용자가 손댈 수 없는 것을 계속 알리는 것은 소음이다).
 * 3. **소속을 모르면 세지 않는다** — 작품 미배정 캐릭터처럼 어느 필드가 적용되는지
 *    알 수 없는 항목은 건너뛴다. 완성도 계산이 같은 이유로 같은 자리에서 건너뛴다.
 * 4. **빈 칸이 없으면 항목 자체가 빠진다** — 카드는 할 일이 있을 때만 서야 한다.
 */
class RequiredFieldGapsTest {

    private var nextId = 1L

    private fun field(
        name: String,
        universeId: Long = 1L,
        required: Boolean = true,
        type: String = "TEXT",
        order: Int = 0
    ) = FieldDefinition(
        id = nextId++,
        universeId = universeId,
        key = name,
        name = name,
        type = type,
        displayOrder = order,
        isRequired = required
    )

    // ── 1. 세는 규칙 ──────────────────────────────────────────────────────────

    @Test
    fun `필수가 아닌 필드는 세지 않는다`() {
        assertTrue(RequiredFieldGaps.countsAsSlot(field("나이")))
        assertTrue(!RequiredFieldGaps.countsAsSlot(field("취미", required = false)))
    }

    @Test
    fun `계산 필드는 필수여도 세지 않는다`() {
        // 사람이 값을 넣는 자리가 아니므로 폼도 세지 않는다 — 여기서 세면 두 소비처가 갈린다.
        assertTrue(!RequiredFieldGaps.countsAsSlot(field("총합", type = "CALCULATED")))
    }

    @Test
    fun `필수 필드가 하나도 없으면 결과가 빈다`() {
        val gaps = RequiredFieldGaps.compute(
            definitions = listOf(field("취미", required = false)),
            entities = listOf(GapEntity(10L, "가온", 1L)),
            filledDefIdsByEntity = emptyMap()
        )
        assertTrue(gaps.isEmpty())
    }

    // ── 2. 세계관 경계 ────────────────────────────────────────────────────────

    @Test
    fun `다른 세계관의 필수 필드는 물리지 않는다`() {
        val mine = field("나이", universeId = 1L)
        val theirs = field("마나", universeId = 2L)
        val gaps = RequiredFieldGaps.compute(
            definitions = listOf(mine, theirs),
            entities = listOf(GapEntity(10L, "가온", 1L)),
            filledDefIdsByEntity = emptyMap()
        )
        assertEquals(1, gaps.size)
        assertEquals(listOf("나이"), gaps[0].missingFieldNames)
    }

    @Test
    fun `그 세계관에 필수 필드가 없으면 항목이 빠진다`() {
        val gaps = RequiredFieldGaps.compute(
            definitions = listOf(field("마나", universeId = 2L)),
            entities = listOf(GapEntity(10L, "가온", 1L)),
            filledDefIdsByEntity = emptyMap()
        )
        assertTrue(gaps.isEmpty())
    }

    // ── 3. 소속을 모르는 항목 ─────────────────────────────────────────────────

    @Test
    fun `세계관을 모르는 항목은 건너뛴다`() {
        val gaps = RequiredFieldGaps.compute(
            definitions = listOf(field("나이")),
            entities = listOf(GapEntity(10L, "작품 미배정", null)),
            filledDefIdsByEntity = emptyMap()
        )
        assertTrue("소속을 모르면 어느 필드가 적용되는지 알 수 없다", gaps.isEmpty())
    }

    // ── 4. 채움 판정 ──────────────────────────────────────────────────────────

    @Test
    fun `채운 칸은 빠지고 빈 칸만 남는다`() {
        val age = field("나이", order = 0)
        val home = field("거주지", order = 1)
        val gaps = RequiredFieldGaps.compute(
            definitions = listOf(age, home),
            entities = listOf(GapEntity(10L, "가온", 1L)),
            filledDefIdsByEntity = mapOf(10L to setOf(age.id))
        )
        assertEquals(listOf("거주지"), gaps.single().missingFieldNames)
    }

    @Test
    fun `다 채운 항목은 결과에서 빠진다`() {
        val age = field("나이")
        val gaps = RequiredFieldGaps.compute(
            definitions = listOf(age),
            entities = listOf(GapEntity(10L, "가온", 1L)),
            filledDefIdsByEntity = mapOf(10L to setOf(age.id))
        )
        assertTrue("할 일이 없으면 카드가 서지 않아야 한다", gaps.isEmpty())
    }

    // ── 5. 차례 ───────────────────────────────────────────────────────────────

    @Test
    fun `빈 칸은 폼에 보이는 차례로 든다`() {
        val late = field("거주지", order = 5)
        val early = field("나이", order = 1)
        val gaps = RequiredFieldGaps.compute(
            definitions = listOf(late, early),
            entities = listOf(GapEntity(10L, "가온", 1L)),
            filledDefIdsByEntity = emptyMap()
        )
        assertEquals(listOf("나이", "거주지"), gaps.single().missingFieldNames)
    }

    @Test
    fun `항목은 이름순으로 든다`() {
        val gaps = RequiredFieldGaps.compute(
            definitions = listOf(field("나이")),
            entities = listOf(
                GapEntity(10L, "하늘", 1L),
                GapEntity(11L, "가온", 1L)
            ),
            filledDefIdsByEntity = emptyMap()
        )
        assertEquals(listOf("가온", "하늘"), gaps.map { it.entityName })
    }

    // ── 6. 총합 ───────────────────────────────────────────────────────────────

    @Test
    fun `칸 총합은 항목 수가 아니라 빈 칸 수다`() {
        val gaps = RequiredFieldGaps.compute(
            definitions = listOf(field("나이", order = 0), field("거주지", order = 1)),
            entities = listOf(
                GapEntity(10L, "가온", 1L),
                GapEntity(11L, "하늘", 1L)
            ),
            filledDefIdsByEntity = emptyMap()
        )
        assertEquals(2, gaps.size)
        assertEquals(4, RequiredFieldGaps.slotCount(gaps))
    }

    @Test
    fun `할 일이 없으면 총합은 0이다`() {
        assertEquals(0, RequiredFieldGaps.slotCount(emptyList()))
    }
}
