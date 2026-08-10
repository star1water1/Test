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
        assertEquals(false, filtered.unassignedScope)
    }

    // ===== 미배정 스코프 모수 (B-계열 후속: novels가 비어도 완성도·인사이트 모수가 0이면 안 된다) =====

    /** 사건 필드까지 포함한 확장 픽스처 — 미배정 사건 2건 중 1건만 세계관 소속 */
    private fun snapshotWithEventFields(): StatsSnapshot {
        val base = snapshot()
        return base.copy(
            events = listOf(
                TimelineEvent(id = 20L, year = 1, description = "배정 사건", universeId = 100L),
                TimelineEvent(id = 21L, year = 2, description = "미배정 사건", universeId = 100L),
                TimelineEvent(id = 22L, year = 3, description = "세계관 없는 미배정 사건", universeId = null)
            ),
            eventFieldDefinitions = listOf(
                FieldDefinition(id = 50L, universeId = 100L, key = "evf", name = "사건 필드", type = "TEXT")
            ),
            eventFieldValues = listOf(
                com.novelcharacter.app.data.model.EventFieldValue(
                    eventId = 21L, fieldDefinitionId = 50L, value = "값"
                )
            )
        )
    }

    @Test
    fun unassignedScope_scopeMarkerSet() {
        assertEquals(true, provider.filterByNovel(snapshot(), UnassignedFilter.NO_NOVEL_ID).unassignedScope)
    }

    @Test
    fun unassignedScope_characterFieldInsightDenominatorIsScopeSize() {
        val filtered = provider.filterByNovel(snapshot(), UnassignedFilter.NO_NOVEL_ID)
        val insight = provider.computeFieldInsights(filtered).first { it.fieldDefinition.id == 40L }
        // 모수 = 스코프 캐릭터 수(1) — novels가 비어 있다고 0이 되면 "채움 1 / 전체 0" 모순
        assertEquals(1, insight.totalCount)
        assertEquals(1, insight.filledCount)
    }

    @Test
    fun unassignedScope_eventFieldInsightDenominatorStaysUniverseBased() {
        // 사건은 자체 universeId를 유지하므로 모수는 세계관 기준이어야 한다 —
        // 스코프 사건 전체(2)로 바꾸면 세계관 없는 사건까지 편입돼 채움률이 과소 표시된다
        val filtered = provider.filterByNovel(snapshotWithEventFields(), UnassignedFilter.NO_NOVEL_ID)
        assertEquals(listOf(21L, 22L), filtered.events.map { it.id })
        val insight = provider.computeFieldInsights(filtered).first { it.fieldDefinition.id == 50L }
        assertEquals(1, insight.totalCount)
        assertEquals(1, insight.filledCount)
    }

    @Test
    fun unassignedScope_incompleteCountUsesFillRate() {
        // 미배정 캐릭터(2)는 보존 정의 1개 중 1개를 채움(100%) — '미배정 = 무조건 미완성'이면
        // 스코프 전원이 미완성으로 집계되는 모순이 생긴다
        val filtered = provider.filterByNovel(snapshot(), UnassignedFilter.NO_NOVEL_ID)
        assertEquals(0, provider.computeDataOverview(filtered).healthWarnings.incompleteFieldCount)
    }

    @Test
    fun unassignedScope_incompleteCountGuardsEmptyFieldSet() {
        // 보존 값이 없어 스코프 정의가 0개면 미완성 판정 자체가 성립하지 않는다 (NaN 가드)
        val noValues = snapshot().copy(
            fieldValues = snapshot().fieldValues.filter { it.characterId != 2L }
        )
        val filtered = provider.filterByNovel(noValues, UnassignedFilter.NO_NOVEL_ID)
        assertTrue(filtered.fieldDefinitions.isEmpty())
        assertEquals(0, provider.computeDataOverview(filtered).healthWarnings.incompleteFieldCount)
    }

    @Test
    fun unassignedScope_hasNoNovelAxis() {
        // 확-3: '작품 미배정'은 작품이 없는 캐릭터의 스코프다. 작품 모수가 0이므로 작품 필드
        // 카드를 만들면 "N/0개"가 되고, 그것은 읽을 수 없는 수치이자 캐릭터 축이 일부러 피한
        // 모순이다. 값이 있어도 이 스코프에서는 작품 축을 만들지 않는다.
        val withNovelField = snapshot().copy(
            novelFieldDefinitions = listOf(
                FieldDefinition(
                    id = 50L, universeId = 100L, key = "form", name = "형식", type = "TEXT",
                    entityType = FieldDefinition.ENTITY_NOVEL
                )
            ),
            novelFieldValues = listOf(
                com.novelcharacter.app.data.model.NovelFieldValue(
                    novelId = 10L, fieldDefinitionId = 50L, value = "장편"
                )
            )
        )
        val filtered = provider.filterByNovel(withNovelField, UnassignedFilter.NO_NOVEL_ID)
        assertTrue(filtered.novelFieldDefinitions.isEmpty())
        assertTrue(filtered.novelFieldValues.isEmpty())
        assertTrue(provider.computeFieldInsights(filtered)
            .none { it.fieldDefinition.entityType == FieldDefinition.ENTITY_NOVEL })
    }

    @Test
    fun fullScope_unassignedCharacterStillCountedIncomplete() {
        // 회귀 고정: 필터 없는 전체 스코프에서는 기존 판정('작품 미배정 = 미완성') 불변
        val overview = provider.computeDataOverview(snapshot())
        assertEquals(1, overview.healthWarnings.incompleteFieldCount)
    }

    // ===== B-30: 계산 필드는 산출하지 않되, 그 사실을 센다 (확정 7-4) =====
    //
    // **이 셋의 방어선은 건수가 아니라 그 안의 둘이고, 둘이 서로를 받친다.**
    // ① *"값을 만들지 않는다"* — 기각된 쪽(필드값으로 세계관 역추적)은 참조 필드가 빠지면
    //    수식이 **조용히 다른 값**을 내어 틀린 값이 맞는 값처럼 보인다.
    // ② *"만들지 않는다는 사실이 세어진다"* — ①만 있으면 `calculatedUnavailable`이 **늘 0을
    //    돌려줘도 통과한다.** 그러면 고지가 영영 안 뜨고, 화면에서는 계산 필드가 *존재하지
    //    않는 것*과 구별되지 않는다 — 그 구별이 이 항목의 전부다(결함이 아니라 고지 누락).

    private fun withCalculatedField(): StatsSnapshot = snapshot().let { s ->
        s.copy(
            fieldDefinitions = s.fieldDefinitions + FieldDefinition(
                id = 60L, universeId = 100L, key = "power", name = "전투력", type = "CALCULATED",
                config = """{"formula":"1+1"}"""
            )
        )
    }

    @Test
    fun unassignedScope_calculatedFieldIsNotFabricated() {
        val filtered = provider.filterByNovel(withCalculatedField(), UnassignedFilter.NO_NOVEL_ID)
        // 계산 필드는 저장 행이 없어 '참조된 정의' 필터에 걸려 정의째 빠진다 — 값도 정의도 없다.
        assertTrue(filtered.fieldDefinitions.none { it.type == "CALCULATED" })
    }

    @Test
    fun unassignedScope_countsWhatItCannotCompute() {
        val filtered = provider.filterByNovel(withCalculatedField(), UnassignedFilter.NO_NOVEL_ID)
        assertEquals(1, filtered.calculatedUnavailable)
    }

    @Test
    fun unassignedScope_saysNothingWhenThereIsNothingToSay() {
        // 계산 필드가 애초에 없으면 고지할 것이 없다 — 0이어야 화면이 줄을 감춘다.
        val filtered = provider.filterByNovel(snapshot(), UnassignedFilter.NO_NOVEL_ID)
        assertEquals(0, filtered.calculatedUnavailable)
    }

    @Test
    fun assignedScope_doesNotClaimAnythingIsUnavailable() {
        // 작품 스코프에서는 수식을 돌릴 수 있으므로 이 고지가 뜨면 안 된다 —
        // 뜨면 멀쩡히 계산되는 필드를 두고 "산출하지 않는다"고 **거짓말**하게 된다.
        val filtered = provider.filterByNovel(withCalculatedField(), 10L)
        assertEquals(0, filtered.calculatedUnavailable)
        assertTrue(filtered.fieldDefinitions.any { it.type == "CALCULATED" })
    }
}
