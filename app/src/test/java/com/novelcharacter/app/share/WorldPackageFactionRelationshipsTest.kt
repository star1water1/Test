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

    /**
     * **좁혀 읽어도 결과가 같다** — 내보내기가 표 전량 대신 *이 세력들에 닿는 관계*만
     * 읽어도 되는 근거다 (2026.08.22).
     *
     * 앞 판은 이 자리를 전량으로 남기며 *"매퍼가 범위 밖까지 세어 고지하므로 좁히면 그
     * 계수가 달라진다"*고 적었는데, 매퍼의 계약은 그 반대다: `droppedCount`는 **한쪽 끝만**
     * 걸친 관계만 세고 **양쪽 다 밖인 관계는 세지 않는다.** 좁히기로 빠지는 것이 정확히
     * 그 *양쪽 다 밖*뿐이므로 `items`도 `droppedCount`도 움직이지 않는다.
     *
     * 이 시험이 없으면 다음 판이 같은 오해로 질의를 전량으로 되돌린다.
     */
    @Test
    fun `양쪽 다 집합 밖인 관계는 있으나 없으나 결과가 같다`() {
        val a = faction(1, "왕국")
        val b = faction(2, "제국")
        val outer1 = faction(8, "타세계관1", universeId = 9L)
        val outer2 = faction(9, "타세계관2", universeId = 9L)

        val touching = listOf(
            rel(1, 2),          // 양쪽 안 → items
            rel(1, 8),          // 한쪽 걸침 → droppedCount
            rel(9, 2)           // 한쪽 걸침 → droppedCount
        )
        // 좁힌 질의가 읽지 않는 행 — 양쪽 다 내보내는 집합 밖이다.
        val bothOutside = listOf(rel(8, 9), rel(9, 8))

        val narrowed = WorldPackageFactionRelationships.toPortable(listOf(a, b), touching)
        val full = WorldPackageFactionRelationships.toPortable(listOf(a, b), touching + bothOutside)

        assertEquals(full.droppedCount, narrowed.droppedCount)
        assertEquals(2, narrowed.droppedCount)
        assertEquals(full.items, narrowed.items)
        assertEquals(1, narrowed.items.size)
    }

    /** 좁힌 목록의 **순서**가 달라도 산출은 같다 — 매퍼가 자기 안에서 다시 정렬한다. */
    @Test
    fun `입력 순서가 달라도 같은 산출이 나온다`() {
        val a = faction(1, "왕국")
        val b = faction(2, "제국")
        val c = faction(3, "연합")
        val rels = listOf(rel(1, 2, order = 2), rel(2, 3, order = 1), rel(1, 3, order = 0))

        val forward = WorldPackageFactionRelationships.toPortable(listOf(a, b, c), rels)
        val reversed = WorldPackageFactionRelationships.toPortable(listOf(a, b, c), rels.reversed())

        assertEquals(forward.items, reversed.items)
        assertEquals(forward.droppedCount, reversed.droppedCount)
    }

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

    // ── fromPortable (S-5 임포터 방향) ──

    private fun portable(
        code1: String, name1: String, code2: String, name2: String, type: String = "동맹"
    ) = PortableFactionRelationship(
        factionCode1 = code1, factionName1 = name1,
        factionCode2 = code2, factionName2 = name2,
        relationType = type, description = "d", intensity = 7,
        isBidirectional = true, displayOrder = 3
    )

    @Test
    fun `fromPortable - code가 새 id로 해석되고 속성이 보존된다`() {
        val r = WorldPackageFactionRelationships.fromPortable(
            listOf(portable("FC1", "왕국", "FC2", "제국")),
            idByCode = mapOf("FC1" to 101L, "FC2" to 102L),
            idByUniqueName = emptyMap()
        )
        assertEquals(0, r.unresolvedCount)
        assertEquals(1, r.relationships.size)
        val rel = r.relationships[0]
        assertEquals(101L, rel.factionId1)
        assertEquals(102L, rel.factionId2)
        assertEquals("동맹", rel.relationType)
        assertEquals(7, rel.intensity)
        assertEquals(3, rel.displayOrder)
    }

    @Test
    fun `fromPortable - code 실패 시 유일한 이름으로 폴백한다`() {
        // 구버전(code 없는) 세력 — code는 빈 문자열로 실려 온다
        val r = WorldPackageFactionRelationships.fromPortable(
            listOf(portable("", "왕국", "FC2", "제국")),
            idByCode = mapOf("FC2" to 102L),
            idByUniqueName = mapOf("왕국" to 101L)
        )
        assertEquals(0, r.unresolvedCount)
        assertEquals(101L, r.relationships[0].factionId1)
    }

    @Test
    fun `fromPortable - code도 유일 이름도 없으면 버리되 개수로 센다`() {
        // 중복 이름은 idByUniqueName에 실리지 않는다(오배정 방지) — 그 관계는 유실 고지 대상
        val r = WorldPackageFactionRelationships.fromPortable(
            listOf(
                portable("", "중복이름", "FC2", "제국"),
                portable("FC1", "왕국", "FC2", "제국")
            ),
            idByCode = mapOf("FC1" to 101L, "FC2" to 102L),
            idByUniqueName = emptyMap()
        )
        assertEquals(1, r.unresolvedCount)
        assertEquals(1, r.relationships.size)
    }

    @Test
    fun `왕복 - toPortable 후 fromPortable이 같은 쌍을 복원한다`() {
        val a = faction(1, "왕국")
        val b = faction(2, "제국")
        val exported = WorldPackageFactionRelationships.toPortable(
            listOf(a, b), listOf(rel(1, 2, type = "적대", order = 5))
        )
        // 가져오는 기기에서 세력이 새 id(201, 202)로 삽입됐다고 가정
        val imported = WorldPackageFactionRelationships.fromPortable(
            exported.items,
            idByCode = mapOf("FC1" to 201L, "FC2" to 202L),
            idByUniqueName = emptyMap()
        )
        assertEquals(0, imported.unresolvedCount)
        val rel = imported.relationships.single()
        assertEquals(201L, rel.factionId1)
        assertEquals(202L, rel.factionId2)
        assertEquals("적대", rel.relationType)
        assertEquals(5, rel.displayOrder)
    }
}
