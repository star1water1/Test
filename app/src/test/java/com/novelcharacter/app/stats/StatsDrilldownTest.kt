package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 인사이트 드릴다운·하위 그룹 분석의 조용한 실패 회귀 테스트 (색출 개혁 로드맵 3).
 *
 * 고정하는 계약은 셋이다:
 * - **S-7** 차트가 (key,type)로 합산한 만큼 드릴다운 목록도 나온다. 대표 def 하나만 조회하면
 *   전체 세계관 보기에서 조각 수치와 인원이 어긋난다.
 * - **S-9** 사건 필드 카드의 조각은 사건 목록을 돌려준다. 종전에는 캐릭터 조회로 흘러가
 *   항상 0명짜리 빈 시트가 떴다.
 * - **S-19** 하위 그룹 분석이 CALCULATED 필드를 실제로 분석한다. 목록이 고를 수 있게
 *   약속한 필드가 항상 '데이터 없음'을 돌려주면 안 된다.
 *
 * 공통 원칙: **대상 필드 정의를 못 찾은 것과 값이 없는 것은 다르다.** 전자는 null(호출부가
 * 사유를 고지), 후자는 빈 결과다. 빈 목록으로 위장하지 않는다.
 */
class StatsDrilldownTest {

    private val provider = StatsDataProvider()

    private val uniA = 1L
    private val uniB = 2L

    private fun charField(
        id: Long, key: String, name: String, universeId: Long = uniA,
        type: String = "TEXT", config: String = "{}"
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type, config = config,
        entityType = FieldDefinition.ENTITY_CHARACTER
    )

    private fun eventField(
        id: Long, key: String, name: String, universeId: Long = uniA,
        type: String = "TEXT", config: String = "{}"
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type, config = config,
        entityType = FieldDefinition.ENTITY_EVENT
    )

    // ===== S-7: 두 세계관에 같은 '직업' 필드 =====

    /**
     * 세계관 A(필드 10)에 검사 2명, 세계관 B(필드 20)에 검사 1명.
     * 인사이트 카드는 (key,type) 머지로 '검사 3명'을 그리므로 드릴다운도 3명이어야 한다.
     */
    private fun twoUniverseSnapshot() = StatsSnapshot(
        characters = listOf(
            Character(id = 1, name = "가", novelId = 1),
            Character(id = 2, name = "나", novelId = 1),
            Character(id = 3, name = "다", novelId = 2),
            Character(id = 4, name = "라", novelId = 2)
        ),
        novels = listOf(
            Novel(id = 1, title = "A작품", universeId = uniA),
            Novel(id = 2, title = "B작품", universeId = uniB)
        ),
        universes = listOf(Universe(id = uniA, name = "A"), Universe(id = uniB, name = "B")),
        events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
        tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
        fieldDefinitions = listOf(
            charField(10, "job", "직업"),
            charField(20, "job", "직업", universeId = uniB)
        ),
        fieldValues = listOf(
            CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "검사"),
            CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "검사"),
            CharacterFieldValue(characterId = 3, fieldDefinitionId = 20, value = "검사"),
            CharacterFieldValue(characterId = 4, fieldDefinitionId = 20, value = "마법사")
        ),
        crossRefs = emptyList()
    )

    @Test
    fun `드릴다운은 카드가 합산한 세계관 전체를 조회한다`() {
        val s = twoUniverseSnapshot()
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "job" }

        // 카드가 그린 수치
        assertEquals(listOf(10L, 20L), insight.mergedFieldDefIds)
        val chartCount = insight.analysisResults.firstNotNullOf { it.distributionData }["검사"]
        assertEquals(3, chartCount)

        // 목록 인원이 그 수치와 일치해야 한다
        val listed = provider.getCharactersByFieldValue(s, insight.mergedFieldDefIds, "검사")!!
        assertEquals(3, listed.size)
        assertEquals(listOf("가", "나", "다"), listed.map { it.characterName })
    }

    @Test
    fun `대표 id 하나만 주면 그 세계관만 나온다 — 과소집계의 실체`() {
        // 이 테스트는 옛 동작을 고정한다: 머지 id를 넘기지 않으면 실제로 적게 나온다는 사실을
        // 남겨 둬야 위 테스트가 무엇을 막고 있는지가 분명해진다.
        val s = twoUniverseSnapshot()
        assertEquals(2, provider.getCharactersByFieldValue(s, listOf(10L), "검사")!!.size)
    }

    @Test
    fun `드릴다운은 원본 값을 그대로 보여준다`() {
        // 다중값 필드에서 조각 라벨만 남기면 그 캐릭터가 가진 나머지 값이 화면에서 사라진다.
        // (콤마 분리는 필드별 설정이다 — MULTI_TEXT여야 토큰으로 갈린다.)
        val base = twoUniverseSnapshot()
        val s = base.copy(
            fieldDefinitions = listOf(
                charField(30, "job_multi", "직업(다중)", type = "MULTI_TEXT"),
                charField(31, "job_multi", "직업(다중)", universeId = uniB, type = "MULTI_TEXT")
            ),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 30, value = "검사, 궁수"),
                CharacterFieldValue(characterId = 3, fieldDefinitionId = 31, value = "검사")
            )
        )
        val listed = provider.getCharactersByFieldValue(s, listOf(30L, 31L), "검사")!!
        assertEquals(2, listed.size)
        assertTrue("원본 값이 보존돼야 한다", listed.any { it.fieldValue == "검사, 궁수" })
    }

    @Test
    fun `필드 정의를 못 찾으면 빈 목록이 아니라 null을 돌려준다`() {
        val s = twoUniverseSnapshot()
        assertNull("빈 목록으로 위장하면 안 된다", provider.getCharactersByFieldValue(s, listOf(999L), "검사"))
        assertNull(provider.getCharactersByFieldValue(s, emptyList(), "검사"))
        // 진짜로 아무도 없는 값은 빈 목록 — null과 구분된다
        assertEquals(0, provider.getCharactersByFieldValue(s, listOf(10L, 20L), "없는값")!!.size)
    }

    @Test
    fun `형제 조회 함수도 못 찾음과 없음을 같은 방식으로 구분한다`() {
        // getCharactersByFieldValue만 고치고 이쪽에 빈 목록 위장을 남기면, 같은 질문에
        // 두 함수가 다른 계약을 갖는다(R-17). 한 축만 고치지 않는다.
        val s = twoUniverseSnapshot()
        assertNull(provider.getCharactersByFieldKeyValues(s, listOf(999L), setOf("검사")))
        // 아무것도 묻지 않은 호출은 '못 찾음'이 아니다 — 빈 목록이 맞다
        assertEquals(0, provider.getCharactersByFieldKeyValues(s, emptyList(), setOf("검사"))!!.size)
        assertEquals(0, provider.getCharactersByFieldKeyValues(s, listOf(10L), emptySet())!!.size)
        // 정상 조회는 머지 전체를 센다
        assertEquals(3, provider.getCharactersByFieldKeyValues(s, listOf(10L, 20L), setOf("검사"))!!.size)
    }

    @Test
    fun `캐릭터가 형제 def로 중복 매칭돼도 한 번만 센다`() {
        val base = twoUniverseSnapshot()
        val s = base.copy(
            fieldValues = base.fieldValues + CharacterFieldValue(
                characterId = 1, fieldDefinitionId = 20, value = "검사"
            )
        )
        val listed = provider.getCharactersByFieldValue(s, listOf(10L, 20L), "검사")!!
        assertEquals("차트 조각은 엔티티 수를 세므로 목록도 같은 단위여야 한다", 3, listed.size)
    }

    // ===== S-9: 사건 필드 드릴다운 =====

    private fun eventSnapshot() = StatsSnapshot(
        characters = emptyList(), novels = emptyList(),
        universes = listOf(Universe(id = uniA, name = "A")),
        events = listOf(
            TimelineEvent(id = 1, year = 100, description = "개전", universeId = uniA),
            TimelineEvent(id = 2, year = 90, description = "회담", universeId = uniA),
            TimelineEvent(id = 3, year = 110, description = "종전", universeId = uniA)
        ),
        relationships = emptyList(), relationshipChanges = emptyList(), tags = emptyList(),
        nameBank = emptyList(), stateChanges = emptyList(),
        fieldDefinitions = emptyList(), fieldValues = emptyList(), crossRefs = emptyList(),
        eventFieldDefinitions = listOf(eventField(20, "kind", "사건 유형")),
        eventFieldValues = listOf(
            EventFieldValue(eventId = 1, fieldDefinitionId = 20, value = "전투"),
            EventFieldValue(eventId = 2, fieldDefinitionId = 20, value = "대화"),
            EventFieldValue(eventId = 3, fieldDefinitionId = 20, value = "전투")
        )
    )

    @Test
    fun `사건 필드 조각을 탭하면 사건 목록이 나온다`() {
        val s = eventSnapshot()
        val insight = provider.computeFieldInsights(s).first {
            it.fieldDefinition.entityType == FieldDefinition.ENTITY_EVENT
        }
        val chartCount = insight.analysisResults.firstNotNullOf { it.distributionData }["전투"]
        assertEquals(2, chartCount)

        val listed = provider.getEventsByFieldValue(s, insight.mergedFieldDefIds, "전투")!!
        assertEquals(2, listed.size)
        // 연도 오름차순 — 연표와 같은 순서로 읽힌다
        assertEquals(listOf("개전", "종전"), listed.map { it.description })
        assertEquals(listOf(100, 110), listed.map { it.year })
        // 시트가 부제로 쓰는 값들 — 사건은 이름이 없으므로 날짜가 정체를 대신한다
        assertEquals(listOf("전투", "전투"), listed.map { it.fieldValue })
        assertTrue("날짜 표기가 비면 시트에서 사건을 구분할 수 없다",
            listed.all { it.formattedDate.isNotBlank() })
    }

    @Test
    fun `사건 조회는 캐릭터 필드 id로는 아무것도 돌려주지 않는다`() {
        // 축을 섞으면 계산하지 않고 사유를 돌려준다(R-13) — 빈 목록으로 위장하지 않는다.
        val s = twoUniverseSnapshot()
        assertNull(provider.getEventsByFieldValue(s, listOf(10L), "검사"))
    }

    @Test
    fun `사건 CALCULATED 필드도 드릴다운된다`() {
        val base = eventSnapshot()
        val s = base.copy(
            eventFieldDefinitions = base.eventFieldDefinitions + listOf(
                eventField(21, "ep", "회차", type = "NUMBER"),
                eventField(22, "ep2", "회차x2", type = "CALCULATED",
                    config = """{"formula":"field('ep') * 2"}""")
            ),
            eventFieldValues = base.eventFieldValues + listOf(
                EventFieldValue(eventId = 1, fieldDefinitionId = 21, value = "3"),
                EventFieldValue(eventId = 2, fieldDefinitionId = 21, value = "3"),
                EventFieldValue(eventId = 3, fieldDefinitionId = 21, value = "5")
            )
        )
        val listed = provider.getEventsByFieldValue(s, listOf(22L), "6")!!
        assertEquals(2, listed.size)
        // 연도 오름차순: 회담(90) → 개전(100)
        assertEquals(listOf("회담", "개전"), listed.map { it.description })
    }

    // ===== S-19: 하위 그룹 분석 =====

    /** 힘(NUMBER) + 힘x2(CALCULATED) + 역할(TEXT), 세계관 A. */
    private fun calcSnapshot() = StatsSnapshot(
        characters = listOf(
            Character(id = 1, name = "가", novelId = 1),
            Character(id = 2, name = "나", novelId = 1),
            Character(id = 3, name = "다", novelId = 1)
        ),
        novels = listOf(Novel(id = 1, title = "작품", universeId = uniA)),
        universes = listOf(Universe(id = uniA, name = "A")),
        events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
        tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
        fieldDefinitions = listOf(
            charField(10, "power", "힘", type = "NUMBER"),
            charField(11, "role", "역할"),
            charField(12, "double_power", "힘x2", type = "CALCULATED",
                config = """{"formula":"field('power') * 2"}""")
        ),
        fieldValues = listOf(
            CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "1"),
            CharacterFieldValue(characterId = 1, fieldDefinitionId = 11, value = "주역"),
            CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "1"),
            CharacterFieldValue(characterId = 2, fieldDefinitionId = 11, value = "주역"),
            CharacterFieldValue(characterId = 3, fieldDefinitionId = 10, value = "2"),
            CharacterFieldValue(characterId = 3, fieldDefinitionId = 11, value = "조역")
        ),
        crossRefs = emptyList()
    )

    @Test
    fun `하위 그룹 분석이 CALCULATED 필드를 실제로 분석한다`() {
        val s = calcSnapshot()
        val r = provider.computeSubgroupAnalysis(s, setOf(1L, 2L, 3L), listOf(12L))!!
        assertEquals("힘x2", r.targetFieldName)
        assertEquals(2, r.distribution["2"])  // 힘 1 × 2 = 2 → 2명
        assertEquals(1, r.distribution["4"])  // 힘 2 × 2 = 4 → 1명
        assertEquals(3, r.totalCount)
    }

    @Test
    fun `하위 그룹 분석은 부분집합만 센다`() {
        val s = calcSnapshot()
        val r = provider.computeSubgroupAnalysis(s, setOf(1L), listOf(12L))!!
        assertEquals(1, r.distribution["2"])
        assertNull("집합 밖 캐릭터가 섞이면 안 된다", r.distribution["4"])
        assertEquals(1, r.totalCount)
    }

    @Test
    fun `하위 그룹 분석은 세계관 머지 id 전체를 센다`() {
        val s = twoUniverseSnapshot()
        val merged = provider.computeSubgroupAnalysis(s, setOf(1L, 2L, 3L, 4L), listOf(10L, 20L))!!
        assertEquals(3, merged.distribution["검사"])
        assertEquals(1, merged.distribution["마법사"])

        // 대표 id 하나만 주면 형제 세계관 값이 통째로 빠진다 — 이것이 막으려는 결함이다
        val single = provider.computeSubgroupAnalysis(s, setOf(1L, 2L, 3L, 4L), listOf(10L))!!
        assertEquals(2, single.distribution["검사"])
    }

    @Test
    fun `대상 필드를 못 찾으면 null — 값이 없는 것과 구분된다`() {
        val s = calcSnapshot()
        assertNull(provider.computeSubgroupAnalysis(s, setOf(1L), listOf(999L)))
        assertNull(provider.computeSubgroupAnalysis(s, setOf(1L), emptyList()))

        // 대상 필드는 있는데 그 부분집합에 값이 없으면 빈 분포 — '데이터 없음'이 맞는 경우다
        val empty = provider.computeSubgroupAnalysis(s, emptySet(), listOf(11L))!!
        assertNotNull(empty)
        assertTrue(empty.distribution.isEmpty())
    }

    @Test
    fun `사건 하위 그룹 분석은 사건 수를 센다`() {
        val s = eventSnapshot()
        val r = provider.computeEventSubgroupAnalysis(s, setOf(1L, 3L), listOf(20L))!!
        assertEquals(2, r.distribution["전투"])
        assertNull(r.distribution["대화"])
        assertEquals(2, r.totalCount)
    }

    @Test
    fun `사건 하위 그룹 분석은 캐릭터 필드 id를 받지 않는다`() {
        assertNull(provider.computeEventSubgroupAnalysis(eventSnapshot(), setOf(1L), listOf(10L)))
    }

    // ===== 확-3: 작품 필드 드릴다운 (사건 축과 대칭) =====

    private fun novelField(
        id: Long, key: String, name: String, universeId: Long = uniA,
        type: String = "TEXT", config: String = "{}"
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type, config = config,
        entityType = FieldDefinition.ENTITY_NOVEL
    )

    private fun novelSnapshot() = StatsSnapshot(
        characters = emptyList(),
        novels = listOf(
            Novel(id = 1, title = "가작품", universeId = uniA),
            Novel(id = 2, title = "나작품", universeId = uniA),
            Novel(id = 3, title = "다작품", universeId = uniA)
        ),
        universes = listOf(Universe(id = uniA, name = "A")),
        events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
        tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
        fieldDefinitions = emptyList(), fieldValues = emptyList(), crossRefs = emptyList(),
        novelFieldDefinitions = listOf(novelField(30, "form", "형식")),
        novelFieldValues = listOf(
            NovelFieldValue(novelId = 1, fieldDefinitionId = 30, value = "장편"),
            NovelFieldValue(novelId = 2, fieldDefinitionId = 30, value = "단편"),
            NovelFieldValue(novelId = 3, fieldDefinitionId = 30, value = "장편")
        )
    )

    @Test
    fun `작품 필드도 인사이트 카드가 되고 모수는 작품 수다`() {
        val s = novelSnapshot()
        val insight = provider.computeFieldInsights(s).first {
            it.fieldDefinition.entityType == FieldDefinition.ENTITY_NOVEL
        }
        // 모수는 캐릭터 수(0)가 아니라 그 세계관의 작품 수 — 단위를 잘못 세면
        // "3/0개"라는 읽을 수 없는 완성도가 나온다.
        assertEquals(3, insight.totalCount)
        assertEquals(2, insight.analysisResults.firstNotNullOf { it.distributionData }["장편"])
    }

    @Test
    fun `작품 필드 조각을 탭하면 작품 목록이 나온다`() {
        val s = novelSnapshot()
        val insight = provider.computeFieldInsights(s).first {
            it.fieldDefinition.entityType == FieldDefinition.ENTITY_NOVEL
        }
        val listed = provider.getNovelsByFieldValue(
            s, insight.mergedFieldDefIds,
            com.novelcharacter.app.util.FieldValueMatchSpec.Values("장편")
        )!!
        assertEquals(listOf("가작품", "다작품"), listed.map { it.title })
        assertEquals(listOf("장편", "장편"), listed.map { it.fieldValue })
        // 행을 누르면 갈 곳(그 작품의 세계관)이 실려 있어야 한다 — 없으면 탭이 죽는다
        assertTrue(listed.all { it.universeId == uniA })
    }

    @Test
    fun `작품 조회는 캐릭터·사건 필드 id로는 아무것도 돌려주지 않는다`() {
        // 축이 셋이 됐으므로 축 판정도 셋이다 — 남의 축 id로 빈 목록을 돌려주면
        // "0개"라는 거짓 사실이 된다(R-17).
        val s = novelSnapshot()
        assertNull(provider.getNovelsByFieldValue(
            s, listOf(10L), com.novelcharacter.app.util.FieldValueMatchSpec.Values("검사")))
        assertNull(provider.getNovelsByFieldValue(
            s, listOf(20L), com.novelcharacter.app.util.FieldValueMatchSpec.Values("전투")))
    }

    @Test
    fun `작품 CALCULATED 필드도 드릴다운된다`() {
        val base = novelSnapshot()
        val s = base.copy(
            novelFieldDefinitions = base.novelFieldDefinitions + listOf(
                novelField(31, "vol", "권수", type = "NUMBER"),
                novelField(32, "vol2", "권수x2", type = "CALCULATED",
                    config = """{"formula":"field('vol') * 2"}""")
            ),
            novelFieldValues = base.novelFieldValues + listOf(
                NovelFieldValue(novelId = 1, fieldDefinitionId = 31, value = "3"),
                NovelFieldValue(novelId = 2, fieldDefinitionId = 31, value = "3"),
                NovelFieldValue(novelId = 3, fieldDefinitionId = 31, value = "5")
            )
        )
        val listed = provider.getNovelsByFieldValue(
            s, listOf(32L), com.novelcharacter.app.util.FieldValueMatchSpec.Values("6"))!!
        assertEquals(listOf("가작품", "나작품"), listed.map { it.title })
    }

    @Test
    fun `작품 하위 그룹 분석은 작품 수를 센다`() {
        val s = novelSnapshot()
        val r = provider.computeNovelSubgroupAnalysis(s, setOf(1L, 3L), listOf(30L))!!
        assertEquals(2, r.distribution["장편"])
        assertNull(r.distribution["단편"])
        assertEquals(2, r.totalCount)
    }

    @Test
    fun `작품 하위 그룹 분석은 남의 축 필드 id를 받지 않는다`() {
        assertNull(provider.computeNovelSubgroupAnalysis(novelSnapshot(), setOf(1L), listOf(20L)))
    }

    @Test
    fun `작품 필터 스코프는 그 작품의 값만 남긴다`() {
        // filterByNovel이 작품 축을 걸러 주지 않으면 한 작품을 골라도 분포가 전체를 센다.
        val s = provider.filterByNovel(novelSnapshot(), 1L)
        assertEquals(listOf(1L), s.novelFieldValues.map { it.novelId })
        val insight = provider.computeFieldInsights(s).first {
            it.fieldDefinition.entityType == FieldDefinition.ENTITY_NOVEL
        }
        assertEquals(1, insight.totalCount)
    }

    // ===== 필드 선택 목록의 머지 =====

    @Test
    fun `필드 선택 목록은 같은 필드를 세계관마다 중복 나열하지 않는다`() {
        val s = twoUniverseSnapshot()
        val groups = provider.getMergedFieldGroups(s.fieldDefinitions)
        assertEquals(1, groups.size)
        assertEquals("직업", groups[0].primary.name)
        assertEquals(listOf(10L, 20L), groups[0].mergedFieldDefIds)
    }

    @Test
    fun `현재 필드를 제외할 때 형제 세계관 def까지 함께 빠진다`() {
        // 시트의 '현재 필드 제외'가 그룹 단위여야 같은 필드가 다른 이름인 척 남지 않는다.
        val s = twoUniverseSnapshot()
        val current = setOf(10L, 20L)
        val remaining = provider.getMergedFieldGroups(s.fieldDefinitions)
            .filter { g -> g.mergedFieldDefIds.none { it in current } }
        assertTrue(remaining.isEmpty())
    }
}
