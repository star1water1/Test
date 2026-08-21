package com.novelcharacter.app.util

import com.novelcharacter.app.util.GraphFactionNarrow.EmptyCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [GraphFactionNarrow] — 관계도의 세력 **좁힘 축** (2026.08.21).
 *
 * 이 하네스가 잠그는 것은 **순서와 경계**다. `RelationshipGraphFragment`에는 로컬 검사가
 * 하나도 닿지 않아(순수 시험·프로브가 `ui/` 아래를 통째로 뺀다) 이 판정이 화면에 남아 있으면
 * 다음 판이 조용히 되돌려도 아무도 모른다.
 */
class GraphFactionNarrowTest {

    private data class Rel(val a: Long, val b: Long)

    private val ends: (Rel) -> Pair<Long, Long> = { it.a to it.b }

    // ── 1. 언제 좁히는가 ──────────────────────────────────────────────────

    @Test
    fun `강조 축이면 아무도 줄지 않는다`() {
        assertNull(
            GraphFactionNarrow.narrowedIds(
                narrows = false, selected = setOf(1L),
                membership = mapOf(10L to listOf(1L)), allCharacterIds = listOf(10L, 11L)
            )
        )
    }

    @Test
    fun `빈 셋은 전체다 — 좁히기를 켜도 줄지 않는다`() {
        // 이 축의 근본 계약이다. 빈 셋에서 좁히면 화면이 통째로 빈다.
        assertNull(
            GraphFactionNarrow.narrowedIds(
                narrows = true, selected = emptySet(),
                membership = mapOf(10L to listOf(1L)), allCharacterIds = listOf(10L, 11L)
            )
        )
    }

    @Test
    fun `고른 세력 중 하나라도 속하면 남는다`() {
        val ids = GraphFactionNarrow.narrowedIds(
            narrows = true, selected = setOf(1L, 2L),
            membership = mapOf(10L to listOf(1L), 11L to listOf(3L), 12L to listOf(3L, 2L)),
            allCharacterIds = listOf(10L, 11L, 12L, 13L)
        )
        assertEquals(setOf(10L, 12L), ids)
    }

    @Test
    fun `미소속 sentinel 은 소속이 없는 인물을 고른다`() {
        val ids = GraphFactionNarrow.narrowedIds(
            narrows = true, selected = setOf(UnassignedFilter.NO_FACTION_ID),
            membership = mapOf(10L to listOf(1L), 11L to emptyList()),
            allCharacterIds = listOf(10L, 11L, 12L)
        )
        // 11은 빈 목록, 12는 맵에 아예 없다 — 둘 다 '소속 없음'이다.
        assertEquals(setOf(11L, 12L), ids)
    }

    // ── 2. 무엇이 남는가 ──────────────────────────────────────────────────

    @Test
    fun `양 끝이 모두 남아야 그린다`() {
        val rels = listOf(Rel(10, 12), Rel(10, 11), Rel(11, 13))
        val kept = GraphFactionNarrow.apply(rels, setOf(10L, 12L), ends)
        assertEquals("한쪽만 남은 관계를 그리면 없는 노드로 선이 뻗는다", listOf(Rel(10, 12)), kept)
    }

    @Test
    fun `거를 것이 없으면 같은 목록을 그대로 돌려준다`() {
        val rels = listOf(Rel(1, 2))
        assertSame("사본을 만들면 상한 앞뒤로 두 벌이 돈다", rels, GraphFactionNarrow.apply(rels, null, ends))
    }

    // ── 3. 왜 비었는가 ────────────────────────────────────────────────────

    @Test
    fun `관계가 하나도 없으면 필터 탓을 하지 않는다`() {
        assertEquals(
            EmptyCause.NO_DATA,
            GraphFactionNarrow.emptyCause(
                anyRelationships = false, afterTypeFilter = 0, narrowing = true, typeFiltering = true
            )
        )
    }

    @Test
    fun `유형까지는 남아 있었는데 비었다면 걷어낸 것은 좁힘이다`() {
        assertEquals(
            EmptyCause.FACTION,
            GraphFactionNarrow.emptyCause(
                anyRelationships = true, afterTypeFilter = 7, narrowing = true, typeFiltering = true
            )
        )
    }

    @Test
    fun `좁힘이 켜져 있어도 유형에서 이미 비었으면 유형 탓이다`() {
        assertEquals(
            "세력을 지목하면 사용자가 엉뚱한 칩을 만진다",
            EmptyCause.REL_TYPE,
            GraphFactionNarrow.emptyCause(
                anyRelationships = true, afterTypeFilter = 0, narrowing = true, typeFiltering = true
            )
        )
    }

    @Test
    fun `필터를 아무것도 안 걸었는데 비었으면 범위 탓이다`() {
        assertEquals(
            EmptyCause.OTHER,
            GraphFactionNarrow.emptyCause(
                anyRelationships = true, afterTypeFilter = 0, narrowing = false, typeFiltering = false
            )
        )
    }
}
