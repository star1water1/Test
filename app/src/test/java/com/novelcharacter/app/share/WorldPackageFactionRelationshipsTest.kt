package com.novelcharacter.app.share

import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionRelationship
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 월드패키지 세력 간 관계 직렬화 (B-6).
 * 포함/한쪽 걸침(고지 대상)/범위 밖(무집계) 3분류와 code 참조, 결정적 순서를 고정한다.
 */
class WorldPackageFactionRelationshipsTest {

    private fun faction(id: Long, name: String, code: String = "FC$id", universeId: Long = 1L) =
        Faction(
            id = id, universeId = universeId, name = name,
            autoRelationType = "소속", code = code
        )

    private fun rel(
        id1: Long,
        id2: Long,
        type: String = "동맹",
        order: Int = 0,
        bidirectional: Boolean = true
    ) = FactionRelationship(
        factionId1 = id1, factionId2 = id2, relationType = type,
        description = "d", intensity = 7, isBidirectional = bidirectional,
        displayOrder = order
    )

    @Test
    fun `양쪽이 내보내는 집합 안이면 code로 실린다`() {
        val a = faction(1, "왕국")
        val b = faction(2, "제국")
        val r = WorldPackageFactionRelationships.toPortable(listOf(a, b), listOf(rel(1, 2)))

        assertEquals(0, r.droppedCount)
        assertEquals(1, r.items.size)
        val item = r.items[0]
        assertEquals("FC1", item.factionCode1)
        assertEquals("왕국", item.factionName1)
        assertEquals("FC2", item.factionCode2)
        assertEquals("제국", item.factionName2)
        assertEquals("동맹", item.relationType)
        assertEquals(7, item.intensity)
        assertTrue(item.isBidirectional)
    }

    @Test
    fun `한쪽만 집합 안이면 버리되 개수로 센다 - 고지 대상`() {
        val a = faction(1, "왕국")
        val r = WorldPackageFactionRelationships.toPortable(
            listOf(a),
            listOf(rel(1, 99), rel(98, 1))
        )
        assertEquals(0, r.items.size)
        assertEquals(2, r.droppedCount)
    }

    @Test
    fun `양쪽 다 집합 밖이면 범위 밖 - 세지 않는다`() {
        // 다른 세계관의 관계까지 '유실'로 세면 고지가 거짓 경고가 된다
        val a = faction(1, "왕국")
        val r = WorldPackageFactionRelationships.toPortable(
            listOf(a),
            listOf(rel(50, 51), rel(60, 61))
        )
        assertEquals(0, r.items.size)
        assertEquals(0, r.droppedCount)
    }

    @Test
    fun `순서는 displayOrder 다음 code 다음 유형 - 결정적`() {
        val a = faction(1, "왕국", code = "AAA")
        val b = faction(2, "제국", code = "BBB")
        val c = faction(3, "연합", code = "CCC")
        val r = WorldPackageFactionRelationships.toPortable(
            listOf(a, b, c),
            listOf(
                rel(2, 3, type = "적대", order = 1),
                rel(1, 2, type = "동맹", order = 0),
                rel(1, 3, type = "경쟁", order = 0)
            )
        )
        val keys = r.items.map { Triple(it.displayOrder, it.factionCode2, it.relationType) }
        // 입력 순서와 무관하게 항상 이 순서로 나와야 한다
        // (displayOrder → code1 → code2 → 유형: AAA-BBB 동맹 < AAA-CCC 경쟁 < BBB-CCC 적대)
        assertEquals(
            listOf(
                Triple(0, "BBB", "동맹"),
                Triple(0, "CCC", "경쟁"),
                Triple(1, "CCC", "적대")
            ),
            keys
        )
        assertEquals(0, r.droppedCount)
    }

    @Test
    fun `code가 빈 구버전 세력도 이름과 함께 실린다 - 임포터가 이름 해석으로 받는다`() {
        // FactionRefResolver 규약: code 우선, 없으면 이름 해석. 빈 code를 이유로 버리면
        // v35 이전 행이 조용히 빠진다 — 싣고 해석은 임포터의 몫이다.
        val a = faction(1, "왕국", code = "")
        val b = faction(2, "제국")
        val r = WorldPackageFactionRelationships.toPortable(listOf(a, b), listOf(rel(1, 2)))
        assertEquals(1, r.items.size)
        assertEquals("", r.items[0].factionCode1)
        assertEquals("왕국", r.items[0].factionName1)
        assertEquals(0, r.droppedCount)
    }
}
