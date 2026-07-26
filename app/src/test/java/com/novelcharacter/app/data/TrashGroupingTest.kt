package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.repository.TrashGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 휴지통 목록의 작업 묶음·정렬 (B-1/B-14).
 *
 * 이 정렬이 곧 **복원 순서**다. 항목 순서가 틀리면 '전체 복원'이 하위 엔티티를 먼저
 * 되살려 참조가 유실되고, 뿌리 항목이 잘못 뽑히면 머리글이 엉뚱한 이름을 말한다.
 */
class TrashGroupingTest {

    private var nextId = 1L

    private fun snap(
        type: String,
        name: String,
        opId: String?,
        at: Long = 1000L,
        id: Long = nextId++
    ) = TrashSnapshot(
        id = id, entityType = type, entityName = name,
        payload = "{}", deletedAt = at, operationId = opId
    )

    @Test
    fun `한 작업의 항목은 복원 순서대로 정렬된다`() {
        val items = listOf(
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1"),
            snap(TrashSnapshot.TYPE_EVENT, "대전쟁", "op1"),
            snap(TrashSnapshot.TYPE_UNIVERSE, "아스테리아", "op1"),
            snap(TrashSnapshot.TYPE_FACTION, "은검단", "op1"),
            snap(TrashSnapshot.TYPE_NOVEL, "1부", "op1")
        )
        val group = TrashGrouping.group(items).single()
        assertEquals(
            listOf(
                TrashSnapshot.TYPE_UNIVERSE, TrashSnapshot.TYPE_NOVEL,
                TrashSnapshot.TYPE_FACTION, TrashSnapshot.TYPE_EVENT,
                TrashSnapshot.TYPE_CHARACTER
            ),
            group.items.map { it.entityType }
        )
    }

    @Test
    fun `뿌리 항목은 복원 순서가 가장 이른 것이다`() {
        val items = listOf(
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1"),
            snap(TrashSnapshot.TYPE_UNIVERSE, "아스테리아", "op1")
        )
        assertEquals("아스테리아", TrashGrouping.group(items).single().root.entityName)
    }

    @Test
    fun `같은 타입끼리는 만들어진 순서를 지킨다`() {
        val items = listOf(
            snap(TrashSnapshot.TYPE_CHARACTER, "나중", "op1", id = 20),
            snap(TrashSnapshot.TYPE_CHARACTER, "먼저", "op1", id = 10)
        )
        assertEquals(listOf("먼저", "나중"), TrashGrouping.group(items).single().items.map { it.entityName })
    }

    @Test
    fun `구버전 행은 각자 자기만의 작업이 된다`() {
        // operationId가 null인 기존 휴지통 항목은 종전과 똑같이 평평한 목록이어야 한다 —
        // 한 묶음으로 접히면 '전체 복원'이 서로 무관한 삭제를 함께 되살린다.
        val a = snap(TrashSnapshot.TYPE_CHARACTER, "가온", null, at = 100L, id = 1)
        val b = snap(TrashSnapshot.TYPE_CHARACTER, "나린", null, at = 200L, id = 2)
        val groups = TrashGrouping.group(listOf(a, b))
        assertEquals(2, groups.size)
        assertEquals(listOf("row:2", "row:1"), groups.map { it.opKey })
        assertTrue("한 항목짜리 묶음은 머리글이 필요 없다", groups.none { it.needsHeader })
    }

    @Test
    fun `작업 키는 엔티티의 operationKey와 같은 문자열이다`() {
        // SQL의 COALESCE(operationId, 'row:' || id)와 어긋나면 보호 목록이 빗나가
        // 방금 만든 백업을 태운다 — R-3가 막으려는 바로 그 결함이다.
        assertEquals("row:7", snap(TrashSnapshot.TYPE_CHARACTER, "x", null, id = 7).operationKey)
        assertEquals("op1", snap(TrashSnapshot.TYPE_CHARACTER, "x", "op1").operationKey)
        assertEquals("row:7", TrashSnapshot.legacyOperationKey(7))
    }

    @Test
    fun `묶음은 최신 삭제부터 나온다`() {
        val old = snap(TrashSnapshot.TYPE_CHARACTER, "옛것", "opOld", at = 100L)
        val new = snap(TrashSnapshot.TYPE_CHARACTER, "새것", "opNew", at = 900L)
        assertEquals(listOf("opNew", "opOld"), TrashGrouping.group(listOf(old, new)).map { it.opKey })
    }

    @Test
    fun `묶음의 시각은 가장 최근 스냅샷 기준이다`() {
        val items = listOf(
            snap(TrashSnapshot.TYPE_UNIVERSE, "아스테리아", "op1", at = 100L),
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1", at = 500L)
        )
        assertEquals(500L, TrashGrouping.group(items).single().newestAt)
    }

    @Test
    fun `같은 시각의 묶음은 키로 갈라 순서가 흔들리지 않는다`() {
        // 관찰 갱신마다 순서가 바뀌면 사용자가 누르려던 버튼이 다른 항목의 것으로 바뀐다.
        val a = snap(TrashSnapshot.TYPE_CHARACTER, "a", "opB", at = 100L)
        val b = snap(TrashSnapshot.TYPE_CHARACTER, "b", "opA", at = 100L)
        assertEquals(listOf("opA", "opB"), TrashGrouping.group(listOf(a, b)).map { it.opKey })
        assertEquals(listOf("opA", "opB"), TrashGrouping.group(listOf(b, a)).map { it.opKey })
    }

    @Test
    fun `머리글은 항목이 둘 이상일 때만 만든다`() {
        val single = listOf(snap(TrashSnapshot.TYPE_EVENT, "대전쟁", "op1"))
        assertFalse(TrashGrouping.group(single).single().needsHeader)
        val pair = single + snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1")
        assertTrue(TrashGrouping.group(pair).single().needsHeader)
    }

    @Test
    fun `빈 목록은 빈 결과다`() {
        assertTrue(TrashGrouping.group(emptyList()).isEmpty())
    }

    @Test
    fun `알 수 없는 타입은 맨 뒤로 밀리되 사라지지 않는다`() {
        // 라벨이 없다고 항목을 버리면 사용자는 존재 자체를 잃는다.
        val items = listOf(
            snap("unknown_future_type", "미래", "op1"),
            snap(TrashSnapshot.TYPE_UNIVERSE, "아스테리아", "op1")
        )
        val group = TrashGrouping.group(items).single()
        assertEquals(2, group.size)
        assertEquals("미래", group.items.last().entityName)
    }
}
