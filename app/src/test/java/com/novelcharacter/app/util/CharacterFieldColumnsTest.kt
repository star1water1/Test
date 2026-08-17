package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CharacterFieldColumns] — 캐릭터 시트의 열 머리 → 필드 해석 사다리 (B-187).
 *
 * **이 사다리는 `ExcelImportService.buildColumnFieldMap` 안에 있었고 그 함수는 해석하면서
 * 동시에 DB에 필드를 만든다.** 그래서 미리보기가 부를 수 없었고, 필드값을 세려면 같은 사다리를
 * 손으로 한 벌 더 짜야 했다 — R-33이 없애려는 그 모양이다. 갈라 내리니 **순수 시험이 닿는다.**
 */
class CharacterFieldColumnsTest {

    private val FIXED = setOf("이름", "코드", "메모")
    private val MULTI = " (쉼표 구분)"

    private fun field(id: Long, key: String, name: String) =
        FieldDefinition(id = id, universeId = 1L, key = key, name = name, type = FieldType.TEXT.name)

    private fun plan(
        headers: Map<Int, String>,
        fields: List<FieldDefinition>,
        fixedCols: Set<Int> = emptySet(),
        hasUniverse: Boolean = true
    ) = CharacterFieldColumns.plan(headers, fields, fixedCols, FIXED, hasUniverse, MULTI)

    // ── ① 사다리의 순서 — 안정 식별자 우선, 자연키 폴백 ──────────────────────────

    @Test
    fun `이름(필드키) 병기 머리는 키로 확정한다`() {
        val target = field(7L, "hair", "머리색")
        val decoy = field(8L, "eye", "머리색")
        val out = plan(mapOf(3 to "머리색(hair)"), listOf(decoy, target))
        assertEquals(target, (out[3] as ColumnFieldOutcome.Matched).field)
    }

    @Test
    fun `키 완전 일치가 이름 일치보다 앞선다`() {
        val byKey = field(7L, "메모2", "다른 이름")
        val byName = field(8L, "other", "메모2")
        val out = plan(mapOf(1 to "메모2"), listOf(byName, byKey))
        assertEquals(byKey, (out[1] as ColumnFieldOutcome.Matched).field)
    }

    @Test
    fun `키가 대소문자만 다르면 잡는다`() {
        val f = field(7L, "Hair", "머리색")
        val out = plan(mapOf(1 to "hair"), listOf(f))
        assertEquals(f, (out[1] as ColumnFieldOutcome.Matched).field)
    }

    @Test
    fun `이름으로 잡는 것은 후보가 유일할 때뿐이다`() {
        val f = field(7L, "hair", "머리색")
        val out = plan(mapOf(1 to "머리색"), listOf(f))
        assertEquals(f, (out[1] as ColumnFieldOutcome.Matched).field)
    }

    /** 동명 필드가 둘이면 **근거 없이 고르지 않는다** — 가져오기는 그 열을 버리고 경고한다. */
    @Test
    fun `동명 필드가 둘이면 모호로 남고 자동 생성하지 않는다`() {
        val out = plan(
            mapOf(1 to "머리색"),
            listOf(field(7L, "hair", "머리색"), field(8L, "hair2", "머리색"))
        )
        val outcome = out[1] as ColumnFieldOutcome.Ambiguous
        assertEquals(2, outcome.candidates)
    }

    // ── ② 다중값 접미사는 떼고 대조한다 ──────────────────────────────────────────

    @Test
    fun `쉼표 구분 접미사가 붙은 머리도 같은 필드에 붙는다`() {
        val f = field(7L, "tags", "특성")
        val out = plan(mapOf(1 to "특성$MULTI"), listOf(f))
        assertEquals(f, (out[1] as ColumnFieldOutcome.Matched).field)
    }

    // ── ③ 고정 열은 두 겹으로 막는다 ─────────────────────────────────────────────
    // 열 번호로 한 겹, 글자로 한 겹. 둘째 겹이 없으면 **같은 고정 머리가 두 번 든 파일**에서
    // 잔존 열이 커스텀 필드로 오인되어 가짜 필드를 만든다.

    @Test
    fun `고정 열 번호는 아예 보지 않는다`() {
        val out = plan(mapOf(0 to "이름", 1 to "머리색"), listOf(field(7L, "hair", "머리색")), fixedCols = setOf(0))
        assertTrue(0 !in out)
        assertTrue(1 in out)
    }

    @Test
    fun `번호에 안 잡힌 고정 머리도 글자로 걸러진다`() {
        val out = plan(mapOf(5 to "메모"), listOf(field(7L, "hair", "머리색")))
        assertTrue(5 !in out)
    }

    // ── ④ 자동 생성 — 세계관이 있을 때만 ────────────────────────────────────────

    @Test
    fun `매칭 실패에 세계관이 있으면 자동 생성 대상이다`() {
        val out = plan(mapOf(1 to "새 열"), emptyList())
        assertEquals("새 열", (out[1] as ColumnFieldOutcome.AutoCreate).header)
    }

    /** 미분류 시트는 세계관이 없어 자동 생성을 못 한다 — 가져오기가 그 열을 무시한다. */
    @Test
    fun `세계관이 없으면 자동 생성이 아니라 미해석이다`() {
        val out = plan(mapOf(1 to "새 열"), emptyList(), hasUniverse = false)
        assertEquals("새 열", (out[1] as ColumnFieldOutcome.Unresolved).header)
    }

    // ── ⑤ 단사(injective) 보장 — 한 필드에 두 열이 붙으면 앞 열만 남는다 ─────────
    // 두 열이 같은 필드에 쓰이면 **앞 열 값이 뒤 열 값으로 조용히 덮인다.**

    @Test
    fun `같은 필드를 가리키는 뒤 열은 버려진다`() {
        val f = field(7L, "hair", "머리색")
        val out = plan(mapOf(2 to "머리색", 5 to "hair"), listOf(f))
        assertEquals(f, (out[2] as ColumnFieldOutcome.Matched).field)
        val dup = out[5] as ColumnFieldOutcome.Duplicate
        assertEquals(2, dup.keptColumn)
        assertEquals(f, dup.field)
    }

    /** 남는 것은 **열 번호가 작은 쪽**이다 — 넣은 순서가 아니라 시트의 자리가 기준이다. */
    @Test
    fun `머리를 뒤죽박죽 넣어도 남는 것은 앞 열이다`() {
        val f = field(7L, "hair", "머리색")
        val out = plan(linkedMapOf(9 to "hair", 4 to "머리색"), listOf(f))
        assertTrue(out[4] is ColumnFieldOutcome.Matched)
        assertEquals(4, (out[9] as ColumnFieldOutcome.Duplicate).keptColumn)
    }

    /** 자동 생성 열은 매번 다른 필드가 되므로 단사 보장에 걸리지 않는다. */
    @Test
    fun `자동 생성 열 둘은 서로 중복이 아니다`() {
        val out = plan(mapOf(1 to "새 열 가", 2 to "새 열 나"), emptyList())
        assertTrue(out[1] is ColumnFieldOutcome.AutoCreate)
        assertTrue(out[2] is ColumnFieldOutcome.AutoCreate)
    }

    // ── ⑥ 빈 머리는 열이 아니다 ──────────────────────────────────────────────────

    @Test
    fun `빈 머리 열은 계획에 들어가지 않는다`() {
        val out = plan(mapOf(1 to "", 2 to "   "), emptyList())
        assertTrue(out.isEmpty())
    }
}
