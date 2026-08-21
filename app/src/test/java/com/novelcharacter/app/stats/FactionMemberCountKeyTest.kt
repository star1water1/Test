package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.util.StatsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 세력 멤버 집계의 키는 **id**다 (R-20: 라벨은 매칭 키가 아니다).
 *
 * 종전에는 `factionName to members.size`를 `toMap()`으로 접어, 사용자가 자유롭게 짓는
 * 이름이 겹치면 뒤엣것이 앞엣것을 덮었다 — 세력 관리 화면은 제 수를 보여 주는데
 * 통계의 '소속 멤버 N명'만 조용히 적어졌고, 어느 세력이 빠졌는지 화면 어디에도 안 나왔다.
 */
class FactionMemberCountKeyTest {

    private val provider = StatsDataProvider()

    private fun snapshot(
        factions: List<Faction>,
        memberships: List<FactionMembership>,
        characters: List<Character>
    ) = StatsSnapshot(
        characters = characters,
        novels = emptyList(),
        universes = listOf(Universe(id = 100L, name = "세계관")),
        events = emptyList(),
        relationships = emptyList(),
        relationshipChanges = emptyList(),
        tags = emptyList(),
        nameBank = emptyList(),
        stateChanges = emptyList(),
        fieldDefinitions = emptyList(),
        fieldValues = emptyList(),
        crossRefs = emptyList(),
        factions = factions,
        factionMemberships = memberships
    )

    private fun faction(id: Long, name: String) =
        Faction(id = id, universeId = 100L, name = name, autoRelationType = "동료")

    @Test
    fun `동명 세력 둘의 멤버가 서로를 덮지 않는다`() {
        val s = snapshot(
            factions = listOf(faction(1L, "기사단"), faction(2L, "기사단")),
            memberships = listOf(
                FactionMembership(id = 1L, factionId = 1L, characterId = 10L),
                FactionMembership(id = 2L, factionId = 1L, characterId = 11L),
                FactionMembership(id = 3L, factionId = 2L, characterId = 12L)
            ),
            characters = listOf(
                Character(id = 10L, name = "가"),
                Character(id = 11L, name = "나"),
                Character(id = 12L, name = "다")
            )
        )

        val result = provider.computeFactionStats(s)

        assertEquals(mapOf(1L to 2, 2L to 1), result.factionMemberCounts)
        // 화면이 쓰는 합계 — 이름으로 접히면 2명(또는 1명)이 되어 한 세력이 통째로 사라졌다
        assertEquals(3, result.factionMemberCounts.values.sum())
    }

    @Test
    fun `지워진 세력의 잔여 멤버십은 세지 않는다`() {
        val s = snapshot(
            factions = listOf(faction(1L, "기사단")),
            memberships = listOf(
                FactionMembership(id = 1L, factionId = 1L, characterId = 10L),
                FactionMembership(id = 2L, factionId = 99L, characterId = 11L)
            ),
            characters = listOf(Character(id = 10L, name = "가"), Character(id = 11L, name = "나"))
        )

        val result = provider.computeFactionStats(s)

        assertEquals(mapOf(1L to 1), result.factionMemberCounts)
    }
}
