package com.novelcharacter.app.data.maintenance

import com.novelcharacter.app.data.maintenance.FactionAutoRelationRelink.Link
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FactionAutoRelationRelink] — **세력 연결을 잃은 자동 관계를 도로 잇는 규칙**.
 *
 * 실측이 세운 계약이다(2026.08.25 사용자가 내보낸 파일): '캐릭터 관계' 135행 전부 세력 칸이
 * 비었는데 그중 87행이 소속 클리크와 정확히 일치하는 자동 관계였다.
 *
 * **이 시험의 무게는 '잇는다'보다 '안 잇는다' 쪽에 있다** — 수동 관계에 세력을 붙이면
 * 탈퇴가 사용자의 관계를 지우는 조용한 유실 경로가 열린다(`FactionRepository`의 금지).
 */
class FactionAutoRelationRelinkTest {

    private val born = 1_774_535_050_000L

    private fun faction(id: Long, type: String = "가문원", intensity: Int = 5) =
        Faction(id = id, universeId = 1, name = "카노스", autoRelationType = type, autoRelationIntensity = intensity)

    private fun member(factionId: Long, characterId: Long, at: Long = born, leaveType: String? = null) =
        FactionMembership(
            id = factionId * 100 + characterId,
            factionId = factionId,
            characterId = characterId,
            leaveType = leaveType,
            createdAt = at
        )

    private fun relation(
        id: Long,
        c1: Long,
        c2: Long,
        type: String = "가문원",
        intensity: Int = 5,
        description: String = "",
        bidirectional: Boolean = true,
        displayOrder: Int = 0,
        createdAt: Long = born + 3,
        factionId: Long? = null
    ) = CharacterRelationship(
        id = id,
        characterId1 = c1,
        characterId2 = c2,
        relationshipType = type,
        description = description,
        intensity = intensity,
        isBidirectional = bidirectional,
        displayOrder = displayOrder,
        createdAt = createdAt,
        factionId = factionId
    )

    // ── 잇는다 ──

    @Test
    fun `소속 클리크와 일치하는 자동 관계를 잇는다`() {
        val plan = FactionAutoRelationRelink.plan(
            factions = listOf(faction(1)),
            memberships = listOf(member(1, 10), member(1, 11)),
            orphanRelations = listOf(relation(500, 10, 11))
        )
        assertEquals(listOf(Link(500, 1)), plan)
    }

    @Test
    fun `실측대로 세 명의 클리크 세 쌍이 전부 이어진다`() {
        val plan = FactionAutoRelationRelink.plan(
            factions = listOf(faction(1)),
            memberships = listOf(member(1, 10), member(1, 11, born + 1), member(1, 12, born + 2)),
            orphanRelations = listOf(
                relation(503, 10, 11, createdAt = born + 1),
                relation(501, 10, 12, createdAt = born + 2),
                relation(502, 11, 12, createdAt = born + 2)
            )
        )
        // 차례는 관계 id 오름차순 — 실행마다 흔들리지 않는다.
        assertEquals(listOf(Link(501, 1), Link(502, 1), Link(503, 1)), plan)
    }

    @Test
    fun `뒤늦게 가입한 멤버의 관계도 그 시점을 기준으로 이어진다`() {
        val late = born + 30 * 24 * 60 * 60 * 1000L
        val plan = FactionAutoRelationRelink.plan(
            factions = listOf(faction(1)),
            memberships = listOf(member(1, 10), member(1, 11, late)),
            orphanRelations = listOf(relation(500, 10, 11, createdAt = late + 5))
        )
        assertEquals(listOf(Link(500, 1)), plan)
    }

    // ── 안 잇는다 — 수동 관계를 지키는 축들 ──

    @Test
    fun `유형이 자동관계유형과 다르면 잇지 않는다`() {
        assertTrue(
            FactionAutoRelationRelink.plan(
                listOf(faction(1)),
                listOf(member(1, 10), member(1, 11)),
                listOf(relation(500, 10, 11, type = "연인"))
            ).isEmpty()
        )
    }

    @Test
    fun `사용자가 손댄 모양이면 잇지 않는다`() {
        val factions = listOf(faction(1))
        val members = listOf(member(1, 10), member(1, 11))
        // 설명 · 강도 · 단방향 · 표시순서 — 생성자가 넣는 기본 모양에서 벗어난 넷.
        val touched = listOf(
            relation(1, 10, 11, description = "가문 이야기"),
            relation(2, 10, 11, intensity = 9),
            relation(3, 10, 11, bidirectional = false),
            relation(4, 10, 11, displayOrder = 2)
        )
        for (rel in touched) {
            assertTrue(
                "손댄 관계를 이었다: $rel",
                FactionAutoRelationRelink.plan(factions, members, listOf(rel)).isEmpty()
            )
        }
    }

    @Test
    fun `소속 시점과 멀리 떨어져 만들어졌으면 잇지 않는다`() {
        // 나중에 사람이 같은 이름으로 만든 관계 — 나머지 축을 전부 우연히 맞춰도 시각이 가른다.
        val far = born + FactionAutoRelationRelink.CREATED_AT_WINDOW_MS + 1
        assertTrue(
            FactionAutoRelationRelink.plan(
                listOf(faction(1)),
                listOf(member(1, 10), member(1, 11)),
                listOf(relation(500, 10, 11, createdAt = far))
            ).isEmpty()
        )
    }

    @Test
    fun `한쪽이 지금 소속이 아니면 잇지 않는다`() {
        assertTrue(
            FactionAutoRelationRelink.plan(
                listOf(faction(1)),
                listOf(member(1, 10), member(1, 11, leaveType = FactionMembership.LEAVE_DEPARTED)),
                listOf(relation(500, 10, 11))
            ).isEmpty()
        )
    }

    @Test
    fun `이미 이어져 있으면 옮기지 않는다`() {
        assertTrue(
            FactionAutoRelationRelink.plan(
                listOf(faction(1), faction(2)),
                listOf(member(1, 10), member(1, 11)),
                listOf(relation(500, 10, 11, factionId = 2))
            ).isEmpty()
        )
    }

    @Test
    fun `자동관계유형이 빈 세력에는 붙지 않는다`() {
        assertTrue(
            FactionAutoRelationRelink.plan(
                listOf(faction(1, type = "  ")),
                listOf(member(1, 10), member(1, 11)),
                listOf(relation(500, 10, 11, type = ""))
            ).isEmpty()
        )
    }

    // ── 애매하면 손대지 않는다 ──

    @Test
    fun `같은 조건을 만족하는 세력이 둘이면 잇지 않는다`() {
        // 겸직 — 두 세력이 같은 자동관계유형·강도를 쓰고 둘 다 이 쌍을 담는다.
        // 어느 쪽의 관계인지 데이터가 말해 주지 않으므로 사용자에게 남긴다.
        assertTrue(
            FactionAutoRelationRelink.plan(
                listOf(faction(1), faction(2)),
                listOf(member(1, 10), member(1, 11), member(2, 10), member(2, 11)),
                listOf(relation(500, 10, 11))
            ).isEmpty()
        )
    }

    @Test
    fun `유형이 갈리면 겸직이어도 하나로 좁혀진다`() {
        val plan = FactionAutoRelationRelink.plan(
            listOf(faction(1, type = "가문원"), faction(2, type = "동료")),
            listOf(member(1, 10), member(1, 11), member(2, 10), member(2, 11)),
            listOf(relation(500, 10, 11, type = "동료"))
        )
        assertEquals(listOf(Link(500, 2)), plan)
    }

    // ── 빈 입력 · 멱등 ──

    @Test
    fun `세력이나 소속이 없으면 빈 계획이다`() {
        assertTrue(
            FactionAutoRelationRelink.plan(emptyList(), emptyList(), listOf(relation(1, 10, 11))).isEmpty()
        )
        assertTrue(
            FactionAutoRelationRelink.plan(listOf(faction(1)), emptyList(), listOf(relation(1, 10, 11))).isEmpty()
        )
    }

    @Test
    fun `이은 뒤 다시 세우면 빈 계획이다`() {
        val factions = listOf(faction(1))
        val members = listOf(member(1, 10), member(1, 11))
        val orphans = listOf(relation(500, 10, 11))
        val links = FactionAutoRelationRelink.plan(factions, members, orphans)
        val applied = orphans.map { rel ->
            links.firstOrNull { it.relationshipId == rel.id }
                ?.let { rel.copy(factionId = it.factionId) } ?: rel
        }
        assertTrue(FactionAutoRelationRelink.plan(factions, members, applied).isEmpty())
    }
}
