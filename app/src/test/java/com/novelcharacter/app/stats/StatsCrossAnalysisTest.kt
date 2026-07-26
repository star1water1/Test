package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.CrossAxis
import com.novelcharacter.app.ui.stats.CrossAxisResolution
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 교차분석의 축 의미론 회귀 테스트 (B-4).
 *
 * 이 기능이 없던 동안의 실제 결함: 인사이트 목록은 사건 필드를 함께 보여주므로 교차분석
 * 다이얼로그에도 사건 필드가 실렸는데, `computeCrossAnalysis`는 캐릭터 필드만 조회해
 * null을 돌려줬고 화면에는 **아무 일도 일어나지 않았다**. 조용한 실패를 막는 것이 요점이라
 * "사건 축이 실제로 표를 만든다"와 "축이 섞이면 이유를 돌려준다" 둘 다 검증한다.
 */
class StatsCrossAnalysisTest {

    private val provider = StatsDataProvider()

    // ── 고정 id: 캐릭터 필드 10번대, 사건 필드 20번대 ──
    private val uniA = 1L
    private val uniB = 2L

    private fun charField(id: Long, key: String, name: String, universeId: Long = uniA) =
        FieldDefinition(
            id = id, universeId = universeId, key = key, name = name, type = "TEXT",
            entityType = FieldDefinition.ENTITY_CHARACTER
        )

    private fun eventField(
        id: Long, key: String, name: String, universeId: Long = uniA,
        type: String = "TEXT", config: String = "{}"
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type, config = config,
        entityType = FieldDefinition.ENTITY_EVENT
    )

    private fun event(id: Long, universeId: Long? = uniA) =
        TimelineEvent(id = id, year = 100, description = "사건 $id", universeId = universeId)

    /**
     * 회차(episode) × 사건 유형(kind) 사건 3건.
     *  - 1: 1화 / 전투     - 2: 1화 / 전투     - 3: 2화 / 대화
     */
    private fun eventSnapshot(
        extraFields: List<FieldDefinition> = emptyList(),
        extraValues: List<EventFieldValue> = emptyList(),
        extraEvents: List<TimelineEvent> = emptyList()
    ) = StatsSnapshot(
        characters = emptyList(), novels = emptyList(),
        universes = listOf(Universe(id = uniA, name = "A"), Universe(id = uniB, name = "B")),
        events = listOf(event(1), event(2), event(3)) + extraEvents,
        relationships = emptyList(), relationshipChanges = emptyList(), tags = emptyList(),
        nameBank = emptyList(), stateChanges = emptyList(),
        fieldDefinitions = listOf(charField(10, "gender", "성별")),
        fieldValues = emptyList(), crossRefs = emptyList(),
        eventFieldDefinitions = listOf(
            eventField(20, "episode", "회차"),
            eventField(21, "kind", "사건 유형")
        ) + extraFields,
        eventFieldValues = listOf(
            EventFieldValue(eventId = 1, fieldDefinitionId = 20, value = "1화"),
            EventFieldValue(eventId = 1, fieldDefinitionId = 21, value = "전투"),
            EventFieldValue(eventId = 2, fieldDefinitionId = 20, value = "1화"),
            EventFieldValue(eventId = 2, fieldDefinitionId = 21, value = "전투"),
            EventFieldValue(eventId = 3, fieldDefinitionId = 20, value = "2화"),
            EventFieldValue(eventId = 3, fieldDefinitionId = 21, value = "대화")
        ) + extraValues
    )

    // ===== 축 판정 =====

    @Test
    fun `사건 필드 둘이면 사건 축으로 판정한다`() {
        val res = provider.resolveCrossAxis(eventSnapshot(), 20L, 21L)
        assertEquals(CrossAxisResolution.Resolved(CrossAxis.EVENT), res)
    }

    @Test
    fun `캐릭터 필드와 사건 필드를 섞으면 이유와 함께 거절한다`() {
        val res = provider.resolveCrossAxis(eventSnapshot(), 10L, 20L)
        assertTrue("축 불일치는 Mismatch여야 한다: $res", res is CrossAxisResolution.Mismatch)
        res as CrossAxisResolution.Mismatch
        assertEquals("성별", res.characterFieldName)
        assertEquals("회차", res.eventFieldName)
    }

    @Test
    fun `필터 필드만 다른 축이어도 거절한다`() {
        // 필터가 다른 축이면 대상 집합을 좁힐 수 없다 — 조용히 무시하지 않는다.
        val res = provider.resolveCrossAxis(eventSnapshot(), 20L, 21L, filterFieldId = 10L)
        assertTrue(res is CrossAxisResolution.Mismatch)
    }

    @Test
    fun `스냅샷에 없는 필드 id는 UnknownField다`() {
        val res = provider.resolveCrossAxis(eventSnapshot(), 20L, 999L)
        assertEquals(CrossAxisResolution.UnknownField, res)
    }

    // ===== 사건 축 집계 =====

    @Test
    fun `사건 축 교차표의 셀은 사건 수다`() {
        val r = provider.computeEventCrossAnalysis(eventSnapshot(), 20L, 21L, null, null)
        assertNotNull(r)
        r!!
        assertEquals(CrossAxis.EVENT, r.axis)
        assertEquals("회차", r.field1Name)
        assertEquals("사건 유형", r.field2Name)
        assertEquals(2, r.crossTable["1화"]?.get("전투"))
        assertEquals(1, r.crossTable["2화"]?.get("대화"))
        assertNull(r.crossTable["1화"]?.get("대화"))
        assertEquals(3, r.filteredCount)
    }

    @Test
    fun `세계관이 없는 사건도 값이 있으면 표에 남는다`() {
        // 세계관을 지우면 사건의 universeId는 null이 되지만 필드값 행은 남는다.
        // 모수를 세계관으로 좁히면 그 사건이 값을 가진 채 표에서 조용히 빠진다 — 그러면 안 된다.
        val snapshot = eventSnapshot(
            extraEvents = listOf(event(90, universeId = null)),
            extraValues = listOf(
                EventFieldValue(eventId = 90, fieldDefinitionId = 20, value = "1화"),
                EventFieldValue(eventId = 90, fieldDefinitionId = 21, value = "전투")
            )
        )
        val r = provider.computeEventCrossAnalysis(snapshot, 20L, 21L, null, null)!!
        assertEquals(3, r.crossTable["1화"]?.get("전투"))
        assertEquals(4, r.filteredCount)
        assertEquals(4, r.totalCount)
        assertEquals(1, r.mergedUniverseCount)
    }

    @Test
    fun `작품이 없는 미분류 캐릭터도 표에 남는다`() {
        // novelId=null은 정상 운용 상태다(재배정 대기). 세계관으로 모수를 좁히면 이들이 사라진다.
        val snapshot = StatsSnapshot(
            characters = listOf(
                Character(id = 1, name = "가", novelId = 1),
                Character(id = 2, name = "나", novelId = null)
            ),
            novels = listOf(Novel(id = 1, title = "작품", universeId = uniA)),
            universes = listOf(Universe(id = uniA, name = "A")),
            events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
            tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
            fieldDefinitions = listOf(charField(10, "gender", "성별"), charField(11, "role", "역할")),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "여성"),
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 11, value = "주역"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "여성"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 11, value = "주역")
            ),
            crossRefs = emptyList()
        )
        val r = provider.computeCrossAnalysis(snapshot, 10L, 11L, null, null)!!
        assertEquals(2, r.crossTable["여성"]?.get("주역"))
        assertEquals(2, r.filteredCount)
        assertEquals(2, r.totalCount)
    }

    @Test
    fun `사건 축 필터는 같은 축 필드로 대상을 좁힌다`() {
        val r = provider.computeEventCrossAnalysis(eventSnapshot(), 20L, 21L, 21L, "전투")!!
        assertEquals(2, r.filteredCount)
        assertEquals(2, r.crossTable["1화"]?.get("전투"))
        assertNull(r.crossTable["2화"])
    }

    @Test
    fun `빈 값은 칸을 만들지 않는다`() {
        val snapshot = eventSnapshot(
            extraEvents = listOf(event(4)),
            extraValues = listOf(
                EventFieldValue(eventId = 4, fieldDefinitionId = 20, value = "3화"),
                EventFieldValue(eventId = 4, fieldDefinitionId = 21, value = "   ")
            )
        )
        val r = provider.computeEventCrossAnalysis(snapshot, 20L, 21L, null, null)!!
        assertNull("빈 값이 빈 칸 카테고리를 만들면 안 된다", r.crossTable["3화"])
        assertEquals(3, r.filteredCount)
    }

    @Test
    fun `CALCULATED 사건 필드도 축이 된다`() {
        // 계산 필드는 저장 행이 없다 — 합치지 않으면 인사이트에는 보이는 필드가 교차분석에서만 빈 표가 된다.
        // (수식은 숫자로 읽히는 값만 참조할 수 있다 — "1화" 같은 라벨은 0으로 떨어진다.)
        val epNo = eventField(23, "ep_no", "회차 번호", type = "NUMBER")
        val calc = eventField(
            22, "double_ep", "회차x2", type = "CALCULATED",
            config = """{"formula":"field('ep_no') * 2"}"""
        )
        val snapshot = eventSnapshot(
            extraFields = listOf(epNo, calc),
            extraValues = listOf(
                EventFieldValue(eventId = 1, fieldDefinitionId = 23, value = "1"),
                EventFieldValue(eventId = 2, fieldDefinitionId = 23, value = "1"),
                EventFieldValue(eventId = 3, fieldDefinitionId = 23, value = "2")
            )
        )
        val r = provider.computeEventCrossAnalysis(snapshot, 22L, 21L, null, null)!!
        assertEquals(2, r.crossTable["2"]?.get("전투"))   // 1 × 2 = 2
        assertEquals(1, r.crossTable["4"]?.get("대화"))   // 2 × 2 = 4
    }

    @Test
    fun `같은 key의 사건 필드가 여러 세계관에 있으면 합산한다`() {
        // 인사이트 목록이 (key, type)로 통합해 한 장으로 보여주므로 교차분석도 같은 범위를 센다.
        val snapshot = eventSnapshot(
            extraFields = listOf(
                eventField(30, "episode", "회차", universeId = uniB),
                eventField(31, "kind", "사건 유형", universeId = uniB)
            ),
            extraEvents = listOf(event(5, universeId = uniB)),
            extraValues = listOf(
                EventFieldValue(eventId = 5, fieldDefinitionId = 30, value = "1화"),
                EventFieldValue(eventId = 5, fieldDefinitionId = 31, value = "전투")
            )
        )
        val r = provider.computeEventCrossAnalysis(snapshot, 20L, 21L, null, null)!!
        assertEquals(3, r.crossTable["1화"]?.get("전투"))
        assertEquals(2, r.mergedUniverseCount)
    }

    // ===== 캐릭터 축이 사건 필드를 집어삼키지 않는다 =====

    @Test
    fun `캐릭터 축 함수에 사건 필드를 주면 계산하지 않는다`() {
        // 이 null이 곧 옛 결함의 실체였다 — 그래서 호출부는 resolveCrossAxis로 먼저 갈라야 한다.
        assertNull(provider.computeCrossAnalysis(eventSnapshot(), 20L, 21L, null, null))
    }

    @Test
    fun `캐릭터 축은 종전대로 캐릭터 수를 센다`() {
        val snapshot = StatsSnapshot(
            characters = listOf(
                Character(id = 1, name = "가", novelId = 1),
                Character(id = 2, name = "나", novelId = 1),
                Character(id = 3, name = "다", novelId = 1)
            ),
            novels = listOf(Novel(id = 1, title = "작품", universeId = uniA)),
            universes = listOf(Universe(id = uniA, name = "A")),
            events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
            tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
            fieldDefinitions = listOf(charField(10, "gender", "성별"), charField(11, "role", "역할")),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "여성"),
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 11, value = "주역"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "여성"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 11, value = "주역"),
                CharacterFieldValue(characterId = 3, fieldDefinitionId = 10, value = "남성"),
                CharacterFieldValue(characterId = 3, fieldDefinitionId = 11, value = "조역")
            ),
            crossRefs = emptyList()
        )
        val r = provider.computeCrossAnalysis(snapshot, 10L, 11L, null, null)!!
        assertEquals(CrossAxis.CHARACTER, r.axis)
        assertEquals(2, r.crossTable["여성"]?.get("주역"))
        assertEquals(1, r.crossTable["남성"]?.get("조역"))
        assertEquals(3, r.totalCount)
        assertEquals(3, r.filteredCount)
    }
}
