package com.novelcharacter.app.data

import com.novelcharacter.app.data.dao.TrashSnapshotSummary
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.repository.TrashFilter
import com.novelcharacter.app.data.repository.TrashGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 휴지통 종류·기간 필터와 이름 검색 (B-50).
 *
 * **이 시험이 지키는 것은 '거짓 고지'다.** 필터는 항목에 걸리지만 '전체 복원'·'묶음 영구 삭제'는
 * 언제나 작업 전체에 작용한다(R-9). 걸린 수만 세어 머리글에 적으면 사용자는 3개인 줄 알고 눌러
 * 214개를 움직인다 — 그래서 [TrashFilter.Match]가 걸린 수와 전체 수를 **둘 다** 든다.
 */
class TrashFilterTest {

    private var nextId = 1L

    private fun snap(
        type: String,
        name: String,
        opId: String?,
        at: Long = 1_000L,
        kind: String? = null
    ) = TrashSnapshotSummary(
        id = nextId++, entityType = type, entityName = name,
        deletedAt = at, operationId = opId, operationKind = kind
    )

    private fun groupsOf(vararg items: TrashSnapshotSummary) = TrashGrouping.group(items.toList())

    @Test
    fun `조건이 없으면 아무것도 걸러지지 않는다`() {
        val groups = groupsOf(
            snap(TrashSnapshot.TYPE_UNIVERSE, "아스테리아", "op1"),
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1")
        )
        val out = TrashFilter.apply(groups, TrashFilter.Criteria())
        assertEquals(1, out.size)
        assertEquals(2, out.single().matched.size)
        assertFalse("가린 것이 없으면 부분이 아니다", out.single().isPartial)
    }

    @Test
    fun `종류로 거르면 그 종류만 남고 전체 수는 그대로다`() {
        val groups = groupsOf(
            snap(TrashSnapshot.TYPE_UNIVERSE, "아스테리아", "op1"),
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1"),
            snap(TrashSnapshot.TYPE_CHARACTER, "라온", "op1")
        )
        val out = TrashFilter.apply(
            groups, TrashFilter.Criteria(types = setOf(TrashSnapshot.TYPE_CHARACTER))
        ).single()
        assertEquals(listOf("가온", "라온"), out.matched.map { it.entityName })
        // **여기가 이 시험의 요점이다** — 일괄 동작이 건드리는 수는 걸러지지 않는다.
        assertEquals("전체 수는 묶음 그대로여야 한다", 3, out.totalCount)
        assertTrue(out.isPartial)
    }

    @Test
    fun `빈 종류 집합은 전체다 — 아무것도 안 고른 상태에서 목록이 비면 안 된다`() {
        val groups = groupsOf(snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1"))
        val out = TrashFilter.apply(groups, TrashFilter.Criteria(types = emptySet()))
        assertEquals("빈 집합을 '아무것도 없음'으로 읽으면 휴지통이 빈 것과 구별되지 않는다",
            1, out.size)
    }

    @Test
    fun `기간은 하한 이후만 남긴다`() {
        val now = 10_000_000L
        val groups = groupsOf(
            snap(TrashSnapshot.TYPE_CHARACTER, "최근", "op1", at = now - 1_000L),
            snap(TrashSnapshot.TYPE_CHARACTER, "오래", "op2", at = now - 40L * 24 * 60 * 60 * 1000)
        )
        val since = TrashFilter.Period.LAST_30_DAYS.since(now)
        val out = TrashFilter.apply(groups, TrashFilter.Criteria(since = since))
        assertEquals(listOf("최근"), out.flatMap { it.matched }.map { it.entityName })
    }

    @Test
    fun `전체 기간은 하한이 없다`() {
        assertEquals(null, TrashFilter.Period.ALL.since(10_000L))
        assertFalse(TrashFilter.Criteria(since = null).isActive)
    }

    @Test
    fun `기간 갈래가 서로 다른 하한을 낸다 — 한 갈래로 뭉개지면 고르는 뜻이 없다`() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = TrashFilter.Period.TODAY_24H.since(now)!!
        val week = TrashFilter.Period.LAST_7_DAYS.since(now)!!
        val month = TrashFilter.Period.LAST_30_DAYS.since(now)!!
        assertTrue(day > week)
        assertTrue(week > month)
    }

    @Test
    fun `검색은 이름 부분 일치이고 대소문자를 가리지 않는다`() {
        val groups = groupsOf(
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1"),
            snap(TrashSnapshot.TYPE_CHARACTER, "Elena", "op2")
        )
        assertEquals(
            listOf("가온"),
            TrashFilter.apply(groups, TrashFilter.Criteria(query = "가")).flatMap { it.matched }.map { it.entityName }
        )
        assertEquals(
            listOf("Elena"),
            TrashFilter.apply(groups, TrashFilter.Criteria(query = "elena")).flatMap { it.matched }.map { it.entityName }
        )
    }

    @Test
    fun `검색은 종류 이름에 걸리지 않는다`() {
        // '캐릭터'를 쳐서 종류가 걸리게 하면, 그 이름을 가진 세계관이 조용히 사라진다.
        // 종류는 종류 필터가 맡는다 — 두 축이 한 칸에 섞이면 어느 쪽도 예측할 수 없다.
        val groups = groupsOf(snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1"))
        val out = TrashFilter.apply(groups, TrashFilter.Criteria(query = "character"))
        assertTrue("종류 문자열로는 걸리지 않아야 한다", out.isEmpty())
    }

    @Test
    fun `공백만 친 검색어는 조건이 아니다`() {
        val groups = groupsOf(snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1"))
        assertEquals(1, TrashFilter.apply(groups, TrashFilter.Criteria(query = "   ")).size)
    }

    @Test
    fun `걸린 항목이 하나도 없는 묶음은 통째로 빠진다`() {
        val groups = groupsOf(
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1"),
            snap(TrashSnapshot.TYPE_CHARACTER, "라온", "op2")
        )
        val out = TrashFilter.apply(groups, TrashFilter.Criteria(query = "가온"))
        assertEquals(1, out.size)
        assertEquals("op1", out.single().group.opKey)
    }

    @Test
    fun `조건 셋이 함께 걸리면 전부 만족해야 남는다`() {
        val now = 10_000_000L
        val groups = groupsOf(
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1", at = now - 1000L),
            snap(TrashSnapshot.TYPE_UNIVERSE, "가온성", "op2", at = now - 1000L),
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op3", at = now - 40L * 24 * 60 * 60 * 1000)
        )
        val out = TrashFilter.apply(
            groups,
            TrashFilter.Criteria(
                types = setOf(TrashSnapshot.TYPE_CHARACTER),
                since = TrashFilter.Period.LAST_30_DAYS.since(now),
                query = "가온"
            )
        )
        assertEquals(1, out.size)
        assertEquals("op1", out.single().group.opKey)
    }

    @Test
    fun `isActive는 조건이 하나라도 있을 때만 참이다`() {
        assertFalse(TrashFilter.Criteria().isActive)
        assertTrue(TrashFilter.Criteria(types = setOf("character")).isActive)
        assertTrue(TrashFilter.Criteria(since = 1L).isActive)
        assertTrue(TrashFilter.Criteria(query = "가").isActive)
    }

    @Test
    fun `거른 뒤에도 묶음 순서와 항목 순서가 그대로다`() {
        // 항목 순서가 곧 복원 순서다 — 거르기가 그것을 흔들면 '전체 복원'이 하위를 먼저 되살린다.
        val groups = groupsOf(
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1", at = 500L),
            snap(TrashSnapshot.TYPE_UNIVERSE, "아스테리아", "op1", at = 500L),
            snap(TrashSnapshot.TYPE_NOVEL, "1부", "op1", at = 500L)
        )
        val out = TrashFilter.apply(groups, TrashFilter.Criteria()).single()
        assertEquals(
            listOf(TrashSnapshot.TYPE_UNIVERSE, TrashSnapshot.TYPE_NOVEL, TrashSnapshot.TYPE_CHARACTER),
            out.matched.map { it.entityType }
        )
    }
}
