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
        // 세력별 합계 — 이름으로 접히면 2명(또는 1명)이 되어 한 세력이 통째로 사라졌다
        assertEquals(3, result.factionMemberCounts.values.sum())
    }

    /**
     * **요약 줄의 두 수는 단위가 같아야 한다** (2026.08.22).
     *
     * 종전 요약은 `factionMemberCounts.values.sum()`(소속 **행** 수)을 *"소속 멤버 N명"*으로
     * 적고 바로 옆에 *"미소속 M명"*(캐릭터 수)을 나란히 적었다. 겸직이 있으면 둘을 더한 값이
     * **캐릭터 총원을 넘었고**, 어느 수가 틀린 것인지 화면에서 알 길이 없었다.
     */
    @Test
    fun `소속 캐릭터 수와 미소속 수를 더하면 총원이다`() {
        val s = snapshot(
            factions = listOf(faction(1L, "기사단"), faction(2L, "상단")),
            memberships = listOf(
                // 10번이 두 세력에 겸직한다 — 행은 둘, 캐릭터는 하나다.
                FactionMembership(id = 1L, factionId = 1L, characterId = 10L),
                FactionMembership(id = 2L, factionId = 2L, characterId = 10L),
                FactionMembership(id = 3L, factionId = 1L, characterId = 11L)
            ),
            characters = listOf(
                Character(id = 10L, name = "가"),
                Character(id = 11L, name = "나"),
                Character(id = 12L, name = "다")
            )
        )

        val result = provider.computeFactionStats(s)

        assertEquals(3, result.factionMemberCounts.values.sum())   // 행 수는 셋
        assertEquals(2, result.memberCharacterCount)               // 캐릭터는 둘
        assertEquals(1, result.factionlessCharacterCount)
        assertEquals(
            s.characters.size,
            result.memberCharacterCount + result.factionlessCharacterCount
        )
    }

    /** 같은 세력에 재가입한 이력이 두 줄이어도 **겸직이 아니다** — 세력을 세지 행을 세지 않는다. */
    @Test
    fun `같은 세력 재가입은 다중 소속으로 세지 않는다`() {
        val s = snapshot(
            factions = listOf(faction(1L, "기사단")),
            memberships = listOf(
                FactionMembership(id = 1L, factionId = 1L, characterId = 10L),
                FactionMembership(id = 2L, factionId = 1L, characterId = 10L)
            ),
            characters = listOf(Character(id = 10L, name = "가"))
        )

        val result = provider.computeFactionStats(s)

        assertEquals(0, result.multiMemberCharacters)
        assertEquals(1, result.memberCharacterCount)
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
