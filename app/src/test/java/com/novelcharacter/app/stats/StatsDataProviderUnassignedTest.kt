package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TimelineEventNovelCrossRef
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot
import com.novelcharacter.app.util.UnassignedFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "작품 미배정" sentinel 스코프 회귀 테스트.
 * 계약: novelId 없는 캐릭터·어느 작품에도 배정 안 된 사건만 남고, 세계관 스코프(작품/세계관/세력)는
 * 비되, **미배정 캐릭터가 실제 보존 중인 값이 참조하는 필드 정의는 포함**된다 (원칙 02).
 */
class StatsDataProviderUnassignedTest {

    private val provider = StatsDataProvider()

    private fun snapshot(): StatsSnapshot {
        val assigned = Character(id = 1L, name = "배정", novelId = 10L)
        val unassigned = Character(id = 2L, name = "미배정", novelId = null)
        return StatsSnapshot(
            characters = listOf(assigned, unassigned),
            novels = listOf(Novel(id = 10L, title = "작품", universeId = 100L)),
            universes = listOf(Universe(id = 100L, name = "세계관")),
            events = listOf(
                TimelineEvent(id = 20L, year = 1, description = "배정 사건"),
                TimelineEvent(id = 21L, year = 2, description = "미배정 사건")
            ),
            relationships = emptyList(),
            relationshipChanges = emptyList(),
            tags = listOf(CharacterTag(characterId = 1L, tag = "a"), CharacterTag(characterId = 2L, tag = "b")),
            nameBank = listOf(
                NameBankEntry(id = 30L, name = "이름1", isUsed = true, usedByCharacterId = 2L),
                NameBankEntry(id = 31L, name = "이름2", isUsed = true, usedByCharacterId = 1L),
                NameBankEntry(id = 32L, name = "미사용")
            ),
            stateChanges = emptyList(),
            fieldDefinitions = listOf(
                FieldDefinition(id = 40L, universeId = 100L, key = "kept", name = "보존 필드", type = "TEXT"),
                FieldDefinition(id = 41L, universeId = 100L, key = "other", name = "무관 필드", type = "TEXT")
            ),
            // 미배정 캐릭터(2)가 세계관 이탈 후에도 보존 중인 값 → 정의 40번은 통계에 남아야 함
            fieldValues = listOf(
                CharacterFieldValue(characterId = 2L, fieldDefinitionId = 40L, value = "값"),
                CharacterFieldValue(characterId = 1L, fieldDefinitionId = 41L, value = "값")
            ),
            crossRefs = listOf(
                TimelineCharacterCrossRef(eventId = 20L, characterId = 1L),
                TimelineCharacterCrossRef(eventId = 21L, characterId = 2L)
            ),
            eventNovelCrossRefs = listOf(TimelineEventNovelCrossRef(eventId = 20L, novelId = 10L))
        )
    }

    @Test
    fun unassignedScope_onlyUnassignedCharactersRemain() {
        val filtered = provider.filterByNovel(snapshot(), UnassignedFilter.NO_NOVEL_ID)
        assertEquals(listOf(2L), filtered.characters.map { it.id })
        assertTrue(filtered.novels.isEmpty())
        assertTrue(filtered.universes.isEmpty())
        assertTrue(filtered.factions.isEmpty())
        assertTrue(filtered.factionMemberships.isEmpty())
    }

    @Test
    fun unassignedScope_onlyUnassignedEventsRemain() {
        val filtered = provider.filterByNovel(snapshot(), UnassignedFilter.NO_NOVEL_ID)
        assertEquals(listOf(21L), filtered.events.map { it.id })
        assertTrue(filtered.eventNovelCrossRefs.isEmpty())
        assertEquals(listOf(2L), filtered.crossRefs.map { it.characterId })
    }

    @Test
    fun unassignedScope_referencedFieldDefinitionsKept() {
        val filtered = provider.filterByNovel(snapshot(), UnassignedFilter.NO_NOVEL_ID)
        // 미배정 캐릭터가 보존 중인 값의 정의(40)는 남고, 무관 정의(41)는 제외
        assertEquals(listOf(40L), filtered.fieldDefinitions.map { it.id })
        assertEquals(listOf(2L), filtered.fieldValues.map { it.characterId })
    }

    @Test
    fun unassignedScope_tagsAndNameBankScoped() {
        val filtered = provider.filterByNovel(snapshot(), UnassignedFilter.NO_NOVEL_ID)
        assertEquals(listOf("b"), filtered.tags.map { it.tag })
        assertEquals(listOf(30L), filtered.nameBank.map { it.id })
    }

    @Test
    fun realNovelId_unchangedBehavior() {
        val filtered = provider.filterByNovel(snapshot(), 10L)
        assertEquals(listOf(1L), filtered.characters.map { it.id })
        assertEquals(listOf(10L), filtered.novels.map { it.id })
    }
}
