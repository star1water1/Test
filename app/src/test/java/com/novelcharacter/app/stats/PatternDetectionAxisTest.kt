package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.PatternAxis
import com.novelcharacter.app.ui.stats.PatternThresholds
import com.novelcharacter.app.ui.stats.PatternType
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 패턴 감지의 **축 개방**(B-36)과 **유형별 민감도**(B-70) 계약.
 *
 * 이 시험이 지키는 것은 넷이다:
 * ① 사건·작품 필드도 편중·균형·희소가 나온다(종전에는 캐릭터 축만 있었다)
 * ② 카드가 **자기 축을 밝힌다** — 어시스턴트 드릴다운이 캐릭터 조회에 사건 id를 넘기지 않게 하는
 *    유일한 근거이고, 없으면 그 경로가 *조용히* 빈 목록으로 떨어진다(R-13)
 * ③ 문구의 단위가 축을 따라간다("각 1건" ↔ "각 1명")
 * ④ 기본 민감도는 **종전 동작 그대로**이고, 손잡이를 움직이면 감지가 실제로 따라 움직인다
 */
class PatternDetectionAxisTest {

    private val provider = StatsDataProvider()

    private fun charField(id: Long) =
        FieldDefinition(id = id, universeId = 1L, key = "kind", name = "종류", type = "TEXT",
            entityType = FieldDefinition.ENTITY_CHARACTER)

    private fun eventField(id: Long) =
        FieldDefinition(id = id, universeId = 1L, key = "kind", name = "종류", type = "TEXT",
            entityType = FieldDefinition.ENTITY_EVENT)

    private fun novelField(id: Long) =
        FieldDefinition(id = id, universeId = 1L, key = "genre", name = "장르", type = "TEXT",
            entityType = FieldDefinition.ENTITY_NOVEL)

    /** 값 열 개 중 여덟이 같은 값 = 80% → 기본 기준(60%)에서 '편중', 승급선(80%)에서 '높음'. */
    private fun skewed(n: Int = 10, sameCount: Int = 8) =
        (1..n).map { if (it <= sameCount) "전투" else "일상$it" }

    private fun snapshot(
        charDefs: List<FieldDefinition> = emptyList(),
        charValues: List<CharacterFieldValue> = emptyList(),
        eventDefs: List<FieldDefinition> = emptyList(),
        eventValues: List<EventFieldValue> = emptyList(),
        novelDefs: List<FieldDefinition> = emptyList(),
        novelValues: List<NovelFieldValue> = emptyList(),
        events: List<TimelineEvent> = emptyList(),
        characters: List<Character> = emptyList(),
        novels: List<Novel> = listOf(Novel(id = 10L, title = "작품", universeId = 1L))
    ) = StatsSnapshot(
        characters = characters,
        novels = novels,
        universes = listOf(Universe(id = 1L, name = "세계관")),
        events = events,
        relationships = emptyList(),
        relationshipChanges = emptyList(),
        tags = emptyList(),
        nameBank = emptyList(),
        stateChanges = emptyList(),
        fieldDefinitions = charDefs,
        fieldValues = charValues,
        crossRefs = emptyList(),
        eventFieldDefinitions = eventDefs,
        eventFieldValues = eventValues,
        novelFieldDefinitions = novelDefs,
        novelFieldValues = novelValues
    )

    // ── ① 사건 축이 열렸는가 ─────────────────────────────────────────────

    @Test
    fun `사건 필드 편중이 감지된다`() {
        val values = skewed().mapIndexed { i, v ->
            EventFieldValue(id = i + 1L, eventId = i + 1L, fieldDefinitionId = 5L, value = v)
        }
        val s = snapshot(
            eventDefs = listOf(eventField(5L)),
            eventValues = values,
            events = (1..10).map { TimelineEvent(id = it.toLong(), year = it, description = "e$it", universeId = 1L) }
        )

        val dominance = provider.detectPatterns(s).filter { it.type == PatternType.DOMINANCE }
        assertEquals(1, dominance.size)
        assertEquals(PatternAxis.EVENT, dominance[0].axis)
        // 모집단은 값 건수가 아니라 **대상 수**다 — 값을 가진 사건 열 개.
        assertEquals(10, dominance[0].population)
    }

    @Test
    fun `작품 필드 편중이 감지된다`() {
        val novels = (1..10).map { Novel(id = it.toLong(), title = "작품$it", universeId = 1L) }
        val values = skewed().mapIndexed { i, v ->
            NovelFieldValue(id = i + 1L, novelId = i + 1L, fieldDefinitionId = 7L, value = v)
        }
        val s = snapshot(novelDefs = listOf(novelField(7L)), novelValues = values, novels = novels)

        val dominance = provider.detectPatterns(s).filter { it.type == PatternType.DOMINANCE }
        assertEquals(1, dominance.size)
        assertEquals(PatternAxis.NOVEL, dominance[0].axis)
    }

    /**
     * **축이 섞이지 않는다** — 같은 `key`("kind")를 가진 캐릭터 필드와 사건 필드는 서로 다른
     * 카드로 나온다. 한 그룹으로 뭉치면 셀 단위가 캐릭터 수와 사건 수로 섞여 어느 숫자도
     * 읽을 수 없게 된다(R-13). 그룹화가 축 안에서만 도는 것을 반대편에서 잠근다.
     */
    @Test
    fun `같은 키의 캐릭터 필드와 사건 필드는 따로 센다`() {
        val chars = (1..10).map { Character(id = it.toLong(), name = "c$it", novelId = 10L) }
        val charValues = skewed().mapIndexed { i, v ->
            CharacterFieldValue(id = i + 1L, characterId = i + 1L, fieldDefinitionId = 1L, value = v)
        }
        val eventValues = skewed().mapIndexed { i, v ->
            EventFieldValue(id = i + 1L, eventId = i + 1L, fieldDefinitionId = 5L, value = v)
        }
        val s = snapshot(
            charDefs = listOf(charField(1L)), charValues = charValues, characters = chars,
            eventDefs = listOf(eventField(5L)), eventValues = eventValues,
            events = (1..10).map { TimelineEvent(id = it.toLong(), year = it, description = "e$it", universeId = 1L) }
        )

        val dominance = provider.detectPatterns(s).filter { it.type == PatternType.DOMINANCE }
        assertEquals(2, dominance.size)
        assertEquals(setOf(PatternAxis.CHARACTER, PatternAxis.EVENT), dominance.map { it.axis }.toSet())
        // 접두가 없으면 두 카드의 제목이 글자 하나까지 같아 사용자가 구분할 수 없다.
        assertNotNull(dominance.firstOrNull { it.title.startsWith("사건 · ") })
        assertNotNull(dominance.firstOrNull { it.title.startsWith("종류") })
    }

    // ── ② · ③ 축을 밝히는가 · 단위가 따라가는가 ──────────────────────────

    @Test
    fun `캐릭터 축 카드는 축을 캐릭터로 밝힌다`() {
        val chars = (1..10).map { Character(id = it.toLong(), name = "c$it", novelId = 10L) }
        val values = skewed().mapIndexed { i, v ->
            CharacterFieldValue(id = i + 1L, characterId = i + 1L, fieldDefinitionId = 1L, value = v)
        }
        val s = snapshot(charDefs = listOf(charField(1L)), charValues = values, characters = chars)

        val dominance = provider.detectPatterns(s).single { it.type == PatternType.DOMINANCE }
        assertEquals(PatternAxis.CHARACTER, dominance.axis)
        // 드릴다운 계약은 그대로다 — 캐릭터 축만 어시스턴트 시트로 펼쳐진다.
        assertEquals(listOf("전투"), dominance.drilldownValues)
        assertEquals(listOf(1L), dominance.mergedFieldDefIds)
    }

    /**
     * 희소 카드의 단위. 사건에 "각 1명에게만 해당됩니다"가 나가면 사용자는 그 숫자가 무엇의
     * 개수인지 알 수 없다 — 문구 단위를 캐릭터판과 가르는 것이 착수 조건이었다(R-13).
     */
    @Test
    fun `희소 값 문구의 단위가 축을 따라간다`() {
        // 값 스무 개 중 **하나만** 1건짜리(1/20 = 5%, 기준 이하)이고 종류는 넷.
        // 1건짜리를 셋 두면 15%라 기준을 넘어 카드가 아예 안 뜬다 — 이 구성이 곧 게이트의 뜻이다.
        val vals = List(14) { "전투" } + List(3) { "연회" } + List(2) { "혼례" } + listOf("장례")
        val eventValues = vals.mapIndexed { i, v ->
            EventFieldValue(id = i + 1L, eventId = i + 1L, fieldDefinitionId = 5L, value = v)
        }
        val s = snapshot(
            eventDefs = listOf(eventField(5L)), eventValues = eventValues,
            events = vals.indices.map { TimelineEvent(id = it + 1L, year = it, description = "e$it", universeId = 1L) }
        )

        val outlier = provider.detectPatterns(s).single { it.type == PatternType.OUTLIER }
        assertEquals(PatternAxis.EVENT, outlier.axis)
        assertTrue(outlier.description, outlier.description.contains("각 1건에만"))
        assertFalse(outlier.description, outlier.description.contains("1명"))
        // 조언도 축을 탄다 — 사건의 드문 값은 '개성'이 아니다.
        assertFalse(outlier.suggestion, outlier.suggestion.contains("개성"))
    }

    // ── ④ 민감도 ────────────────────────────────────────────────────────

    @Test
    fun `기본 민감도는 종전 동작을 그대로 재현한다`() {
        val t = PatternThresholds.DEFAULT
        assertEquals(60f, t.dominancePercent, 0.001f)
        // 승급선은 기준에서 파생된다 — 기본 60에서 정확히 종전의 80이어야 한다.
        assertEquals(80f, t.dominanceHighPercent, 0.001f)
        assertEquals(35f, t.balanceMaxPercent, 0.001f)
        assertEquals(10f, t.balanceMinPercent, 0.001f)
        assertEquals(5f, t.outlierSingletonPercent, 0.001f)
        assertEquals(50f, t.clusterPercent, 0.001f)
        assertEquals(100, t.absenceGapYears)
        assertEquals(3f, t.crossNovelRatio, 0.001f)
    }

    /**
     * 손잡이를 올리면 카드가 **실제로 사라진다.** 되돌려 확인하지 않으면 "설정은 있는데 아무
     * 영향이 없는" 겉핥기가 되고, 그것이 B-70이 가리킨 상태의 정반대 실패다(원칙 02).
     */
    @Test
    fun `편중 기준을 올리면 같은 데이터가 더 이상 편중이 아니다`() {
        val chars = (1..10).map { Character(id = it.toLong(), name = "c$it", novelId = 10L) }
        // 7/10 = 70% — 기본 60%에서는 편중, 75%로 올리면 아니다.
        val values = skewed(sameCount = 7).mapIndexed { i, v ->
            CharacterFieldValue(id = i + 1L, characterId = i + 1L, fieldDefinitionId = 1L, value = v)
        }
        val s = snapshot(charDefs = listOf(charField(1L)), charValues = values, characters = chars)

        assertEquals(1, provider.detectPatterns(s).count { it.type == PatternType.DOMINANCE })
        val stricter = PatternThresholds.DEFAULT.copy(dominancePercent = 75f)
        assertEquals(0, provider.detectPatterns(s, PatternType.values().toSet(), stricter)
            .count { it.type == PatternType.DOMINANCE })
    }

    /**
     * 승급선이 기준을 따라 움직인다. 고정해 두면 기준을 90%로 올린 사용자에게 감지된 것이
     * **전부** '높음'이 되어 심각도가 뜻을 잃는다.
     */
    @Test
    fun `편중 기준을 올리면 높음 승급선도 함께 올라간다`() {
        assertEquals(90f, PatternThresholds.DEFAULT.copy(dominancePercent = 80f).dominanceHighPercent, 0.001f)
        assertEquals(55f, PatternThresholds.DEFAULT.copy(dominancePercent = 10f).dominanceHighPercent, 0.001f)
    }

    /**
     * 공백 기준이 스케일을 탄다 — B-70이 든 실사용자(수천 년 역법)에게 100년 고정은
     * 아무것도 뜻하지 않는다.
     */
    @Test
    fun `공백 기준을 바꾸면 연표 공백 감지가 따라 움직인다`() {
        val events = listOf(
            TimelineEvent(id = 1L, year = 0, description = "a", universeId = 1L),
            TimelineEvent(id = 2L, year = 300, description = "b", universeId = 1L),
            TimelineEvent(id = 3L, year = 310, description = "c", universeId = 1L),
            TimelineEvent(id = 4L, year = 320, description = "d", universeId = 1L),
            TimelineEvent(id = 5L, year = 330, description = "e", universeId = 1L)
        )
        val s = snapshot(events = events)

        // 기본 100년: 0→300의 300년 간격이 공백이다.
        assertEquals(1, provider.detectPatterns(s).count { it.type == PatternType.ABSENCE })
        // 500년으로 올리면 같은 연표에 공백이 없다.
        val wide = PatternThresholds.DEFAULT.copy(absenceGapYears = 500)
        assertEquals(0, provider.detectPatterns(s, PatternType.values().toSet(), wide)
            .count { it.type == PatternType.ABSENCE })
    }

    @Test
    fun `사건 집중 기준을 올리면 집중 카드가 사라진다`() {
        // 10년대 0에 넷, 100년대에 하나 = 80% 집중.
        val events = (1..4).map { TimelineEvent(id = it.toLong(), year = it, description = "e$it", universeId = 1L) } +
            TimelineEvent(id = 9L, year = 100, description = "far", universeId = 1L)
        val s = snapshot(events = events)

        assertEquals(1, provider.detectPatterns(s).count { it.type == PatternType.CLUSTER })
        val strict = PatternThresholds.DEFAULT.copy(clusterPercent = 90f)
        assertEquals(0, provider.detectPatterns(s, PatternType.values().toSet(), strict)
            .count { it.type == PatternType.CLUSTER })
    }

    // ── 저장 형식 ───────────────────────────────────────────────────────

    @Test
    fun `민감도는 적은 그대로 왕복한다`() {
        val t = PatternThresholds(
            dominancePercent = 75f, balanceMaxPercent = 40f, outlierSingletonPercent = 3f,
            clusterPercent = 65f, absenceGapYears = 5000, crossNovelRatio = 2.5f
        )
        assertEquals(t, PatternThresholds.decode(t.encode()).thresholds)
        // 정수는 소수점 없이 적힌다 — 엑셀에서 손으로 고치는 자리다.
        assertTrue(t.encode(), t.encode().contains("dominance=75"))
        assertFalse(t.encode(), t.encode().contains("75.0"))
    }

    /**
     * **적히지 않은 항목은 기본값을 지킨다.** 엑셀에서 한 줄만 고쳤는데 나머지가 초기화되면
     * 사용자는 자기가 설정한 것을 잃고도 그 사실을 모른다.
     */
    @Test
    fun `일부만 적힌 값은 나머지를 건드리지 않는다`() {
        val decoded = PatternThresholds.decode("absence_years=4000")
        assertEquals(4000, decoded.thresholds.absenceGapYears)
        assertEquals(PatternThresholds.DEFAULT.dominancePercent, decoded.thresholds.dominancePercent, 0.001f)
        assertTrue(decoded.unknownKeys.isEmpty())
        assertTrue(decoded.invalidKeys.isEmpty())
    }

    /**
     * **일부만 적힌 파일이 나머지를 지우지 않는다.** 내보내기는 언제나 여섯을 다 적으므로
     * 일부만 적힌 파일은 사람이 손으로 만든 것이고, 한 줄을 고친 뜻은 *그 한 줄을 바꾸라*이지
     * 나머지를 초기화하라가 아니다 — 그래서 엑셀 바인딩은 **지금 저장된 값**을 base로 넘긴다.
     * base 없이 읽으면 다섯이 말없이 기본값으로 돌아간다.
     */
    @Test
    fun `적히지 않은 항목은 base를 그대로 둔다`() {
        val stored = PatternThresholds(
            dominancePercent = 85f, balanceMaxPercent = 20f, outlierSingletonPercent = 2f,
            clusterPercent = 70f, absenceGapYears = 3000, crossNovelRatio = 5f
        )
        val decoded = PatternThresholds.decode("absence_years=4000", stored)
        assertEquals(4000, decoded.thresholds.absenceGapYears)
        // 나머지 다섯은 손대지 않았다 — 기본값으로 돌아가면 사용자가 설정을 잃고도 모른다.
        assertEquals(85f, decoded.thresholds.dominancePercent, 0.001f)
        assertEquals(20f, decoded.thresholds.balanceMaxPercent, 0.001f)
        assertEquals(5f, decoded.thresholds.crossNovelRatio, 0.001f)
        // 못 읽은 항목도 base를 지킨다(기본값으로 떨어지지 않는다).
        assertEquals(85f, PatternThresholds.decode("dominance=abc", stored).thresholds.dominancePercent, 0.001f)
    }

    /**
     * **못 읽은 것을 조용히 버리지 않는다**(개발 의도 2번 '변수 제어'). 모르는 항목과 숫자가
     * 아닌 값은 사용자가 고칠 자리가 다르므로 갈라서 센다.
     */
    @Test
    fun `모르는 항목과 못 읽는 값을 갈라서 알린다`() {
        val decoded = PatternThresholds.decode("dominance=abc,없는항목=3,cluster=55")
        assertEquals(listOf("dominance"), decoded.invalidKeys)
        assertEquals(listOf("없는항목"), decoded.unknownKeys)
        // 읽힌 것은 그대로 적용되고, 못 읽은 것만 기본값이다.
        assertEquals(55f, decoded.thresholds.clusterPercent, 0.001f)
        assertEquals(60f, decoded.thresholds.dominancePercent, 0.001f)
    }

    /** 범위 밖은 **거부가 아니라 교정**이되, 교정한 사실을 말한다. */
    @Test
    fun `범위 밖 값은 접히고 접힌 사실이 남는다`() {
        val decoded = PatternThresholds.decode("dominance=9999")
        assertEquals(100f, decoded.thresholds.dominancePercent, 0.001f)
        assertTrue(decoded.coerced)
        // 정상 범위 값은 접히지 않았다고 말해야 한다 — 늘 true면 고지가 소음이 된다.
        assertFalse(PatternThresholds.decode("dominance=70").coerced)
    }

    /**
     * 균형의 하한은 상한을 따라 **올라가지 않는다.** 함께 움직이면 상한을 올릴수록 하한도 올라
     * 되레 덜 관대해져 민감도가 단조롭지 않게 된다. 상한을 하한 아래로 내린 경우에만 따라 내려간다.
     */
    @Test
    fun `균형 하한은 상한을 넘지 않는다`() {
        assertEquals(10f, PatternThresholds.DEFAULT.copy(balanceMaxPercent = 90f).balanceMinPercent, 0.001f)
        assertEquals(10f, PatternThresholds.DEFAULT.copy(balanceMaxPercent = 10f).balanceMinPercent, 0.001f)
        // 구간이 뒤집히면 어떤 분포도 균형이 될 수 없다 — 그 자리에서만 하한이 따라 내려간다.
        val squeezed = PatternThresholds.clamp(PatternThresholds.DEFAULT.copy(balanceMaxPercent = 5f))
        assertTrue(squeezed.balanceMinPercent <= squeezed.balanceMaxPercent)
    }

    /** 값이 셋뿐인 필드에서는 희소가 산술적으로 성립할 수 없다 — 표본 조건은 민감도와 다르다. */
    @Test
    fun `희소는 최소 값 건수 아래에서 뜨지 않는다`() {
        val chars = (1..4).map { Character(id = it.toLong(), name = "c$it", novelId = 10L) }
        val values = listOf("가", "나", "다", "라").mapIndexed { i, v ->
            CharacterFieldValue(id = i + 1L, characterId = i + 1L, fieldDefinitionId = 1L, value = v)
        }
        val s = snapshot(charDefs = listOf(charField(1L)), charValues = values, characters = chars)
        assertEquals(0, provider.detectPatterns(s).count { it.type == PatternType.OUTLIER })
    }

    /** 꺼진 유형은 축이 늘어도 여전히 꺼져 있다 — 축 개방이 유형 설정을 새는 문이 되면 안 된다. */
    @Test
    fun `유형을 끄면 사건 축에서도 나오지 않는다`() {
        val values = skewed().mapIndexed { i, v ->
            EventFieldValue(id = i + 1L, eventId = i + 1L, fieldDefinitionId = 5L, value = v)
        }
        val s = snapshot(
            eventDefs = listOf(eventField(5L)), eventValues = values,
            events = (1..10).map { TimelineEvent(id = it.toLong(), year = it, description = "e$it", universeId = 1L) }
        )
        val without = PatternType.values().toSet() - PatternType.DOMINANCE
        assertTrue(provider.detectPatterns(s, without).none { it.type == PatternType.DOMINANCE })
    }

    /**
     * **드릴다운은 축을 넘지 않는다** — 사건 축 카드의 필드 id를 캐릭터 조회에 넘기면 정의를
     * 못 찾아 `null`이다. 어시스턴트는 그 앞에서 축을 물어 애초에 넘기지 않지만, 계산 쪽도
     * 다시 확인해야 다른 호출부가 생겼을 때 조용한 실패가 되살아나지 않는다(R-13의 두 겹).
     */
    @Test
    fun `사건 필드 id로 캐릭터를 조회하면 빈 목록이 아니라 null이다`() {
        val values = skewed().mapIndexed { i, v ->
            EventFieldValue(id = i + 1L, eventId = i + 1L, fieldDefinitionId = 5L, value = v)
        }
        val s = snapshot(
            eventDefs = listOf(eventField(5L)), eventValues = values,
            events = (1..10).map { TimelineEvent(id = it.toLong(), year = it, description = "e$it", universeId = 1L) }
        )
        val card = provider.detectPatterns(s).single { it.type == PatternType.DOMINANCE }
        assertNull(provider.getCharactersByFieldKeyValues(s, card.mergedFieldDefIds, card.drilldownValues.toSet()))
        // 사건 축 조회는 같은 id로 실제 사건을 돌려준다 — 길이 없는 것이 아니라 축이 다른 것이다.
        assertEquals(8, provider.getEventsByFieldValue(s, card.mergedFieldDefIds, "전투")?.size)
    }
}
