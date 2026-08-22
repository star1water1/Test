package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.util.StatsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * computeDataOverview의 접힌 완성도(S6 6차 — B-216)의 동작 무변경 대조.
 *
 * 걷어낸 것 둘 — **호출마다 fieldValues 전량 재그룹**(저장 비블랭크 계수)과 **def마다
 * 작품·캐릭터 전수 필터**(def×캐릭터 곱 모수) — 를 하네스가 그대로 들고
 * `fieldCompletionByField`를 **순서까지** 대조한다([StatsMemoParityTest] 1번 규약).
 * 나머지 산출(건강도 경고·연도 밀도·이름은행)은 이 판이 손대지 않았다.
 *
 * 잠그는 것 넷:
 * 1. **옛 파이프라인과 같은 답** — 무소속 세계관 def(universeId=null) · 없는 작품
 *    (novelId=99) · 작품 없음(novelId=null) · 캐릭터 0 세계관 모수 갈래까지, 순서 포함.
 * 2. **CALCULATED 비오염** — 채움 계수를 접힌 값 표([valueCountsOf] — augmented 위)로
 *    바꿔도, 계산값은 CALCULATED def에만 실리고 그 def는 목록에서 제외되므로 저장 계수가
 *    오염되지 않는다. 블랭크 저장 행도 세지 않는다.
 * 3. **미배정 스코프 모수** = 스냅샷 캐릭터 전체 — 종전 분기 그대로.
 * 4. **동수(rate tie)의 정렬 안정성** — 같은 완성도끼리는 def 목록 순서가 유지된다.
 */
class StatsOverviewParityTest {

    private val uniA = 1L
    private val uniB = 2L
    private val uniEmpty = 3L   // 캐릭터가 하나도 없는 세계관 — 모수 0 갈래

    private fun def(
        id: Long, key: String, name: String, universeId: Long? = uniA,
        type: String = "TEXT", config: String = "{}"
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type, config = config,
        entityType = FieldDefinition.ENTITY_CHARACTER
    )

    /**
     * 모수·계수의 모든 갈래가 실리는 스냅샷:
     * - 10 직업(TEXT, uniA): "기사" **2건**(접기 가중 — 고유 1종. 건수 합 대신 고유 종수를
     *   세는 퇴화를 잡는다) + **블랭크 저장 행 1건**(세지 않아야 한다)
     * - 11 힘(NUMBER, uniA): 2건 — CALCULATED(12)의 수식 입력
     * - 12 힘x2(CALCULATED, uniA): 저장 행 0 — augmented에만 계산값이 실리고 목록 제외
     * - 13 취미(TEXT, uniB): 1건
     * - 20/21 빈 필드 둘(TEXT, uniA): 0건 — 동수(rate 0) 정렬 안정성 갈래
     * - 30 유령(TEXT, uniEmpty): 캐릭터 0 세계관 — total 0 · rate 0
     * - 90 전역(TEXT, universeId=null): 무소속 세계관 — 모수는 무소속 작품 캐릭터
     * - 캐릭터 5(무소속작품) · 6(novelId=99, 없는 작품) · 7(novelId=null) — 모수 제외 갈래
     */
    private fun overviewSnapshot(): StatsSnapshot {
        val defs = listOf(
            def(10, "job", "직업"),
            def(11, "power", "힘", type = "NUMBER"),
            def(12, "power2", "힘x2", type = "CALCULATED",
                config = """{"formula":"field('power') * 2"}"""),
            def(13, "hobby", "취미", universeId = uniB),
            def(20, "empty1", "빈필드1"),
            def(21, "empty2", "빈필드2"),
            def(30, "ghost", "유령", universeId = uniEmpty),
            def(90, "global", "전역메모", universeId = null)
        )
        val chars = listOf(
            Character(id = 1, name = "c1", novelId = 1L, updatedAt = 1_700_000_000_000L),
            Character(id = 2, name = "c2", novelId = 1L, updatedAt = 1_700_000_000_000L),
            Character(id = 3, name = "c3", novelId = 1L, updatedAt = 1_700_000_000_000L),
            Character(id = 4, name = "c4", novelId = 2L, updatedAt = 1_700_000_000_000L),
            Character(id = 5, name = "c5", novelId = 3L, updatedAt = 1_700_000_000_000L),
            Character(id = 6, name = "c6", novelId = 99L, updatedAt = 1_700_000_000_000L),
            Character(id = 7, name = "c7", novelId = null, updatedAt = 1_700_000_000_000L)
        )
        val values = listOf(
            CharacterFieldValue(id = 1, characterId = 1, fieldDefinitionId = 10, value = "기사"),
            CharacterFieldValue(id = 2, characterId = 2, fieldDefinitionId = 10, value = "기사"),
            CharacterFieldValue(id = 3, characterId = 3, fieldDefinitionId = 10, value = "  "),
            CharacterFieldValue(id = 4, characterId = 1, fieldDefinitionId = 11, value = "10"),
            CharacterFieldValue(id = 5, characterId = 2, fieldDefinitionId = 11, value = "20"),
            CharacterFieldValue(id = 6, characterId = 4, fieldDefinitionId = 13, value = "낚시"),
            CharacterFieldValue(id = 7, characterId = 5, fieldDefinitionId = 90, value = "메모A")
        )
        return StatsSnapshot(
            characters = chars,
            novels = listOf(
                Novel(id = 1, title = "A작품", universeId = uniA),
                Novel(id = 2, title = "B작품", universeId = uniB),
                Novel(id = 3, title = "무소속작품", universeId = null)
            ),
            universes = listOf(
                Universe(id = uniA, name = "A"), Universe(id = uniB, name = "B"),
                Universe(id = uniEmpty, name = "빈세계관")
            ),
            events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
            tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
            fieldDefinitions = defs, fieldValues = values, crossRefs = emptyList(),
            valueEntries = emptyList()
        )
    }

    /** 접기 도입 전 — 호출마다 저장 행을 재그룹하고 def마다 캐릭터 전수를 필터하던 그 모양. */
    private fun legacyCompletion(s: StatsSnapshot): List<Triple<String, Pair<Int, Int>, Float>> {
        val valuesByFieldDef = s.fieldValues.filter { it.value.isNotBlank() }
            .groupBy { it.fieldDefinitionId }
        return s.fieldDefinitions.filter { it.fieldType != FieldType.CALCULATED }.map { fd ->
            val universeNovels = s.novels.filter { it.universeId == fd.universeId }
                .map { it.id }.toSet()
            val relevantChars = if (s.unassignedScope) s.characters
                else s.characters.filter { it.novelId in universeNovels }
            // **분자는 분모 집합과의 교집합이다**(R-34) — 값 표는 그 칸의 것이 아닌 행을
            // 정상적으로 담는다(작품을 '없음'으로 바꾼 캐릭터의 보관 값). 세면 100%를 넘는다.
            val relevantCharIds = relevantChars.mapTo(HashSet()) { it.id }
            val filled = valuesByFieldDef[fd.id]
                ?.count { it.value.isNotBlank() && it.characterId in relevantCharIds } ?: 0
            val total = relevantChars.size
            val rate = if (total > 0) filled.toFloat() / total * 100f else 0f
            Triple(fd.name, filled to total, rate)
        }.sortedBy { it.third }
    }

    private fun actualCompletion(s: StatsSnapshot): List<Triple<String, Pair<Int, Int>, Float>> =
        StatsDataProvider().computeDataOverview(s).fieldCompletionByField
            .map { Triple(it.fieldName, it.filledCount to it.totalCount, it.completionRate) }

    @Test
    fun `필드별 완성도가 재그룹·def×캐릭터 필터의 답과 순서까지 같다`() {
        val s = overviewSnapshot()
        val expected = legacyCompletion(s)
        val actual = actualCompletion(s)
        assertEquals(expected, actual)

        // 갈래가 실제로 실렸는지 — 값으로도 못박는다(하네스 자체의 회귀 방지)
        val byName = actual.associateBy { it.first }
        assertEquals("직업 — 블랭크 행은 세지 않는다", 2 to 3, byName.getValue("직업").second)
        assertEquals("전역메모 — 무소속 세계관 모수는 무소속 작품 캐릭터",
            1 to 1, byName.getValue("전역메모").second)
        assertEquals("유령 — 캐릭터 0 세계관", 0 to 0, byName.getValue("유령").second)
        assertEquals(0f, byName.getValue("유령").third)
        assertEquals("취미 — B세계관 모수", 1 to 1, byName.getValue("취미").second)
    }

    @Test
    fun `CALCULATED 계산값이 저장 계수에 섞이지 않는다`() {
        val s = overviewSnapshot()
        val actual = actualCompletion(s)
        // 계산 def는 목록에 없다
        assertFalse(actual.any { it.first == "힘x2" })
        // 수식 입력(힘)의 계수는 저장 행 그대로 — augmented의 계산 행이 더해지면 2를 넘는다
        assertEquals(2, actual.first { it.first == "힘" }.second.first)
    }

    @Test
    fun `미배정 스코프에서도 필드별 완성도가 종전 모수와 같다`() {
        val s = overviewSnapshot().copy(unassignedScope = true)
        val expected = legacyCompletion(s)
        val actual = actualCompletion(s)
        assertEquals(expected, actual)
        // 미배정 모수 = 스냅샷 캐릭터 전체(7) — novelId가 없는 캐릭터도 든다
        assertTrue(actual.all { it.second.second == s.characters.size })
    }

    @Test
    fun `같은 완성도끼리는 def 목록 순서가 유지된다`() {
        val s = overviewSnapshot()
        val actual = actualCompletion(s)
        // rate 0 동수 셋(빈필드1·빈필드2·유령) — 정렬이 def 목록 순서를 보존해야 한다.
        // sortedBy가 불안정 정렬로 바뀌거나 모수 셈이 def 순회 순서를 흔들면 여기가 갈린다.
        val zeroRateNames = actual.filter { it.third == 0f }.map { it.first }
        assertEquals(listOf("빈필드1", "빈필드2", "유령"), zeroRateNames)
    }
}
