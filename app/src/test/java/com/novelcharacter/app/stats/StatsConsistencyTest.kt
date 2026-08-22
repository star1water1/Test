package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.PatternType
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.util.StatsSnapshot
import com.novelcharacter.app.ui.stats.SUBGROUP_DISTRIBUTION_LIMIT
import com.novelcharacter.app.util.FieldValueMatchSpec
import com.novelcharacter.app.util.NumericBinning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 화면 간 통계 정합성 회귀 테스트 (색출 개혁 로드맵 4).
 *
 * 고정하는 계약:
 * - **S-14** '통계에 포함'을 끈 필드는 패턴 인사이트에도 나타나지 않는다.
 * - **S-15** 레거시 필드 분석이 CALCULATED를 포함하고 '통계에 포함' 설정을 따른다.
 * - **S-16** BODY_SIZE 구간 조각의 드릴다운이 그 구간의 실제 인원을 돌려준다.
 * - **S-17** 분포는 전량이 오고, 상한은 표시 계층이 적용한다(비율의 분모 = 전체 합).
 * - **S-18** 순위 빈도가 인사이트 분포와 같은 파싱 규칙을 쓴다.
 * - **B-33** 계산 필드가 요약 TOP5·패턴·순위에서 같은 값으로 집계된다.
 * - **B-35** 교차분석의 그룹 확장이 '통계에 포함'을 따른다.
 */
class StatsConsistencyTest {

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

    private fun snapshot(
        characters: List<Character>,
        fieldDefinitions: List<FieldDefinition>,
        fieldValues: List<CharacterFieldValue>,
        novels: List<Novel> = listOf(Novel(id = 1, title = "A작품", universeId = uniA)),
        universes: List<Universe> = listOf(Universe(id = uniA, name = "A"), Universe(id = uniB, name = "B"))
    ) = StatsSnapshot(
        characters = characters, novels = novels, universes = universes,
        events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
        tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
        fieldDefinitions = fieldDefinitions, fieldValues = fieldValues, crossRefs = emptyList()
    )

    private val statsOff = """{"stats":{"enabled":false}}"""

    // ===== S-14: 패턴 인사이트가 '통계에 포함'을 따른다 =====

    /** 성별이 한쪽으로 쏠린 캐릭터 10명 — 편중 패턴이 잡히는 구성. */
    private fun dominanceSnapshot(config: String) = snapshot(
        characters = (1L..10L).map { Character(id = it, name = "c$it", novelId = 1) },
        fieldDefinitions = listOf(charField(10, "gender", "성별", type = "SELECT", config = config)),
        fieldValues = (1L..10L).map {
            CharacterFieldValue(characterId = it, fieldDefinitionId = 10, value = if (it <= 9) "남" else "여")
        }
    )

    @Test
    fun `통계에 포함을 켠 필드는 편중 패턴으로 잡힌다`() {
        val patterns = provider.detectPatterns(dominanceSnapshot("{}"))
        assertTrue(patterns.any { it.type == PatternType.DOMINANCE && it.title.contains("성별") })
    }

    @Test
    fun `통계에 포함을 끈 필드는 패턴 인사이트에 나타나지 않는다`() {
        // 종전: 설정 스위치가 인사이트 목록에만 적용되고 패턴 감지는 무시했다.
        val patterns = provider.detectPatterns(dominanceSnapshot(statsOff))
        assertTrue(
            "끈 필드가 패턴 카드로 되살아나면 안 된다",
            patterns.none { it.title.contains("성별") }
        )
    }

    // ===== S-15 · B-33: 레거시 필드 분석 =====

    /** 힘(NUMBER) + 힘x2(CALCULATED) + 메모(통계 제외 TEXT). */
    private fun calcSnapshot() = snapshot(
        characters = listOf(
            Character(id = 1, name = "가", novelId = 1),
            Character(id = 2, name = "나", novelId = 1)
        ),
        fieldDefinitions = listOf(
            charField(10, "power", "힘", type = "NUMBER"),
            charField(11, "power2", "힘x2", type = "CALCULATED",
                config = """{"formula":"field('power') * 2"}"""),
            charField(12, "memo", "메모", config = statsOff)
        ),
        fieldValues = listOf(
            CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "10"),
            CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "20"),
            CharacterFieldValue(characterId = 1, fieldDefinitionId = 12, value = "비밀"),
            CharacterFieldValue(characterId = 2, fieldDefinitionId = 12, value = "비밀")
        )
    )

    @Test
    fun `레거시 필드 분석이 계산 필드의 수치 요약을 낸다`() {
        // 종전: 수식 필드는 저장 행이 없어 이 화면에서만 통째로 빠졌다.
        val stats = provider.computeFieldAnalysis(calcSnapshot())
        val summary = stats.numberFieldSummaries.find { it.fieldName == "힘x2" }
        assertNotNull("계산 필드가 요약에 있어야 한다", summary)
        assertEquals(20f, summary!!.min, 0.001f)
        assertEquals(40f, summary.max, 0.001f)
    }

    @Test
    fun `레거시 필드 분석이 통계 제외 필드를 빼놓는다`() {
        val stats = provider.computeFieldAnalysis(calcSnapshot())
        assertTrue(
            "끈 필드가 분포에 남으면 설정이 화면마다 다르게 해석된다",
            stats.fieldValueDistributions.none { it.fieldName == "메모" }
        )
        // 완성도는 '입력 현황'이므로 설정과 무관하게 그대로 보인다(의도된 예외).
        assertTrue(stats.fieldCompletionByField.any { it.fieldName == "메모" })
    }

    @Test
    fun `요약 TOP5도 계산 필드를 센다`() {
        val summary = provider.computeSummary(calcSnapshot())
        assertTrue(
            "인사이트와 수치가 일치해야 한다고 선언한 집계다",
            summary.topFieldValues.any { it.first == "힘x2" }
        )
        assertTrue(
            "끈 필드는 TOP5에도 없다",
            summary.topFieldValues.none { it.first == "메모" }
        )
    }

    @Test
    fun `계산 필드의 순위 값과 분포 값이 같은 서식이다`() {
        // 종전: 순위는 %.1f, 분포는 %.2f로 서식이 갈려 같은 필드가 화면마다 다른 값이었다.
        val s = snapshot(
            characters = listOf(Character(id = 1, name = "가", novelId = 1)),
            fieldDefinitions = listOf(
                charField(10, "power", "힘", type = "NUMBER"),
                charField(11, "avg", "평균", type = "CALCULATED",
                    config = """{"formula":"field('power') / 3"}""")
            ),
            fieldValues = listOf(CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "10"))
        )
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "avg" }
        val distKey = insight.analysisResults.firstNotNullOf { it.distributionData }.keys.first()
        val ranking = provider.computeRanking(s, listOf(11L))
        assertEquals(distKey, ranking.entries.first().displayValue)
    }

    @Test
    fun `수치 필드의 순위 표시는 저장 원문이다`() {
        // 저장 행이 있는 타입(NUMBER·GRADE·BODY_SIZE)은 분포 차트도 원문을 키로 쓴다.
        // 순위만 다시 서식하면 같은 값이 두 화면에서 다른 문자열로 보인다.
        val s = snapshot(
            characters = listOf(Character(id = 1, name = "가", novelId = 1)),
            fieldDefinitions = listOf(charField(10, "height", "키", type = "NUMBER")),
            fieldValues = listOf(CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "170.250"))
        )
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "height" }
        val distKey = insight.analysisResults.firstNotNullOf { it.distributionData }.keys.first()
        val ranking = provider.computeRanking(s, listOf(10L))
        assertEquals(distKey, ranking.entries.first().displayValue)
    }

    @Test
    fun `빈 필드 목록으로 순위를 물으면 예외 대신 빈 결과다`() {
        val s = calcSnapshot()
        val ranking = provider.computeRanking(s, emptyList())
        assertTrue(ranking.entries.isEmpty())
    }

    @Test
    fun `순위 빈도는 카테고리가 아니라 값을 센다`() {
        // 통계 그룹핑을 '둘 다'로 둔 필드에서 카드의 키 공간을 그대로 쓰면 frequencyMap에
        // 값 빈도와 카테고리 빈도가 섞여 **캐릭터 순위가 카테고리 크기 순위로** 변한다.
        val both = """{"stats":{"statsGroupBy":"both","valueCategories":{"청염":"불","흑염":"불","빙결":"얼음"}}}"""
        val chars = listOf(
            Character(id = 1, name = "가", novelId = 1),
            Character(id = 2, name = "나", novelId = 1),
            Character(id = 3, name = "다", novelId = 1)
        )
        val s = snapshot(
            characters = chars,
            fieldDefinitions = listOf(charField(10, "attr", "속성", type = "SELECT", config = both)),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "청염"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "흑염"),
                CharacterFieldValue(characterId = 3, fieldDefinitionId = 10, value = "빙결")
            )
        )
        val ranking = provider.computeRanking(s, listOf(10L))
        assertEquals(3, ranking.entries.size)
        assertTrue(
            "표시값은 그 캐릭터가 실제로 가진 값이어야 한다(카테고리가 아니라)",
            ranking.entries.none { it.displayValue.contains("불") || it.displayValue.contains("얼음") }
        )
        assertTrue("전원 1회 동률", ranking.entries.all { it.value == 1.0 })
    }

    @Test
    fun `사용자 구간 밖의 값은 버리지 않고 구간 밖으로 보인다`() {
        // 종전에는 어느 구간에도 안 드는 값이 통째로 사라져, 카드가 "채움 2"라 해놓고
        // 분포 합은 1인 상태를 아무 설명 없이 만들었다.
        val binned = """{"stats":{"binning":{"mode":"custom","ranges":["1~50:하급","50~100:상급"]}}}"""
        val s = snapshot(
            characters = listOf(
                Character(id = 1, name = "가", novelId = 1),
                Character(id = 2, name = "나", novelId = 1)
            ),
            fieldDefinitions = listOf(charField(10, "level", "레벨", type = "NUMBER", config = binned)),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "10"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "120")
            )
        )
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "level" }
        val dist = insight.analysisResults.firstNotNullOf { it.distributionData }
        assertEquals(1, dist["하급"])
        assertEquals(1, dist[StatsDataProvider.OUT_OF_RANGE_LABEL])
        assertEquals("분포 합이 채움 수와 같아야 한다", insight.filledCount, dist.values.sum())

        // 드릴다운도 그 값을 찾아낸다(보이기만 하고 못 여는 조각을 만들지 않는다)
        val listed = provider.getCharactersByFieldValue(
            s, insight.mergedFieldDefIds, StatsDataProvider.OUT_OF_RANGE_LABEL
        )!!
        assertEquals(1, listed.size)
    }

    // ===== S-16: BODY_SIZE 구간 드릴다운 =====

    private fun bodySnapshot() = snapshot(
        characters = (1L..5L).map { Character(id = it, name = "c$it", novelId = 1) },
        fieldDefinitions = listOf(charField(10, "size", "신체", type = "BODY_SIZE")),
        fieldValues = listOf(
            CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "150-60-80"),
            CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "160-60-80"),
            CharacterFieldValue(characterId = 3, fieldDefinitionId = 10, value = "170-60-80"),
            CharacterFieldValue(characterId = 4, fieldDefinitionId = 10, value = "180-60-80"),
            CharacterFieldValue(characterId = 5, fieldDefinitionId = 10, value = "200-60-80")
        )
    )

    @Test
    fun `구간 조각의 인원이 분포 건수와 일치한다`() {
        // 종전: 라벨("150~160")을 저장값("150-60-80")과 비교해 **항상 0명**이었다.
        val s = bodySnapshot()
        val dist = provider.computeFieldAnalysis(s).fieldValueDistributions
            .first { it.fieldName.startsWith("신체") }

        assertTrue("구간마다 매치 스펙이 있어야 한다", dist.matchSpecs.size == dist.distribution.size)
        var totalListed = 0
        for ((label, count) in dist.distribution) {
            val spec = dist.matchSpecs.getValue(label)
            val listed = provider.getCharactersByFieldValue(s, listOf(10L), spec)!!
            assertEquals("[$label] 조각 수치와 드릴다운 인원이 같아야 한다", count, listed.size)
            totalListed += listed.size
        }
        assertEquals("모든 캐릭터가 정확히 한 구간에 들어간다", 5, totalListed)
    }

    @Test
    fun `구간 스펙 없이 라벨만 넘기면 아무도 못 찾는다 — 옛 동작의 실체`() {
        // 이 사실을 남겨 둬야 위 테스트가 무엇을 막고 있는지 분명해진다.
        val s = bodySnapshot()
        val dist = provider.computeFieldAnalysis(s).fieldValueDistributions
            .first { it.fieldName.startsWith("신체") }
        val label = dist.distribution.keys.first()
        assertEquals(0, provider.getCharactersByFieldValue(s, listOf(10L), label)!!.size)
    }

    @Test
    fun `파트별 구간은 그 파트의 수치로만 판정한다`() {
        val s = bodySnapshot()
        val spec = FieldValueMatchSpec.NumericPartRange(
            partIndex = 1, separator = "-", min = 59f, max = 61f, inclusiveMax = true
        )
        // 두 번째 파트가 전부 60이므로 5명 전원
        assertEquals(5, provider.getCharactersByFieldValue(s, listOf(10L), spec)!!.size)
    }

    // ===== S-17: 분포 전량 + 상한은 표시 계층 =====

    @Test
    fun `인사이트 분포는 상한과 무관하게 전량을 싣는다`() {
        val s = snapshot(
            characters = (1L..12L).map { Character(id = it, name = "c$it", novelId = 1) },
            fieldDefinitions = listOf(
                charField(10, "city", "거주지",
                    config = """{"stats":{"analyses":[{"type":"distribution","chart":"pie","limit":3}]}}""")
            ),
            fieldValues = (1L..12L).map {
                CharacterFieldValue(characterId = it, fieldDefinitionId = 10, value = "도시$it")
            }
        )
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "city" }
        val dist = insight.analysisResults.firstNotNullOf { it.distributionData }
        assertEquals("상한(3)이 계산 단계에서 적용되면 잘린 종수·건수를 알 수 없다", 12, dist.size)
        assertEquals(12, dist.values.sum())
    }

    // ===== S-18: 순위 빈도가 통계 파싱을 쓴다 =====

    @Test
    fun `콤마 목록 표시 형식 TEXT의 순위 빈도가 분포와 일치한다`() {
        // 종전: MULTI_TEXT일 때만 콤마로 쪼개서, 표시 형식이 콤마 목록인 TEXT는
        // "검, 활" 전체가 한 값이 되어 전원 1회 동률이 나왔다.
        val commaList = """{"displayFormat":"comma_list"}"""
        val s = snapshot(
            characters = (1L..3L).map { Character(id = it, name = "c$it", novelId = 1) },
            fieldDefinitions = listOf(charField(10, "weapon", "무기", config = commaList)),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "검, 활"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "검"),
                CharacterFieldValue(characterId = 3, fieldDefinitionId = 10, value = "활")
            )
        )
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "weapon" }
        val dist = insight.analysisResults.firstNotNullOf { it.distributionData }
        assertEquals(2, dist["검"])
        assertEquals(2, dist["활"])

        val ranking = provider.computeRanking(s, listOf(10L))
        assertEquals(3, ranking.entries.size)
        // 대표 토큰의 빈도가 분포 건수와 같아야 한다
        val top = ranking.entries.first()
        assertEquals(2.0, top.value, 0.001)
        assertTrue(top.displayValue.contains("2회"))
    }

    @Test
    fun `별칭을 쓰는 필드의 순위 빈도가 분포와 일치한다`() {
        // 값 라이브러리가 '검'의 별칭으로 '장검'을 등재한 필드. 분포 차트는 별칭을 접어
        // '검 2건'으로 세는데 순위가 원문을 그대로 세면 두 화면이 다른 수를 준다(S-18).
        //
        // 이 테스트는 해석기가 **스냅샷에서 파생**되기 때문에 비로소 가능해졌다 —
        // provider의 가변 필드였을 때는 loadSnapshot을 거치지 않는 테스트에서 별칭 경로가
        // 통째로 죽은 코드였다(고쳐도 고쳐졌는지 확인할 방법이 없었다).
        val base = snapshot(
            characters = (1L..3L).map { Character(id = it, name = "c$it", novelId = 1) },
            fieldDefinitions = listOf(charField(10, "weapon", "무기", type = "SELECT")),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "검"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "장검"),
                CharacterFieldValue(characterId = 3, fieldDefinitionId = 10, value = "활")
            )
        )
        val s = base.copy(
            valueEntries = listOf(
                FieldValueEntry(
                    id = 1, fieldDefinitionId = 10, value = "검",
                    aliasesJson = """["장검"]"""
                )
            )
        )

        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "weapon" }
        val dist = insight.analysisResults.firstNotNullOf { it.distributionData }
        assertEquals("별칭이 canonical로 접혀야 한다", 2, dist["검"])

        val ranking = provider.computeRanking(s, listOf(10L))
        val aliasRow = ranking.entries.first { it.characterName == "c2" }
        assertEquals("접힌 값의 빈도가 분포와 같아야 한다", 2.0, aliasRow.value, 0.001)
        assertTrue(aliasRow.displayValue.contains("검"))
    }

    @Test
    fun `등급 필드의 별칭도 순위에서 해석된다`() {
        // 종전: 분포는 canonical로 접어 세는데 등급 해석만 원문을 조회해,
        // 차트에는 있는 캐릭터가 순위에서만 조용히 빠졌다.
        val gradeConfig = """{"grades":{"S":100,"A":80}}"""
        val base = snapshot(
            characters = listOf(
                Character(id = 1, name = "가", novelId = 1),
                Character(id = 2, name = "나", novelId = 1)
            ),
            fieldDefinitions = listOf(charField(10, "rank", "등급", type = "GRADE", config = gradeConfig)),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "S"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "에스")
            )
        )
        val s = base.copy(
            valueEntries = listOf(
                FieldValueEntry(id = 1, fieldDefinitionId = 10, value = "S", aliasesJson = """["에스"]""")
            )
        )
        val ranking = provider.computeRanking(s, listOf(10L))
        assertEquals("별칭 저장값도 등급으로 해석돼야 한다", 2, ranking.entries.size)
        assertEquals(0, ranking.excludedCount)
    }

    @Test
    fun `한 캐릭터는 순위표에 한 번만 나온다`() {
        // 작품 이동 뒤 남은 형제 세계관 값 때문에 같은 캐릭터가 두 줄로 나오면 안 된다.
        val s = snapshot(
            characters = listOf(Character(id = 1, name = "가", novelId = 1)),
            novels = listOf(Novel(id = 1, title = "A작품", universeId = uniA)),
            fieldDefinitions = listOf(
                charField(10, "power", "힘", type = "NUMBER"),
                charField(20, "power", "힘", universeId = uniB, type = "NUMBER")
            ),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "10"),
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 20, value = "99")
            )
        )
        val ranking = provider.computeRanking(s, listOf(10L, 20L))
        assertEquals(1, ranking.entries.size)
        // 그 캐릭터의 현재 세계관(A) 값이 우선한다
        assertEquals(10.0, ranking.entries.first().value, 0.001)
    }

    // ===== B-35: 교차분석 그룹 확장이 설정을 따른다 =====

    private fun crossSnapshot(bEnabled: Boolean) = snapshot(
        characters = listOf(
            Character(id = 1, name = "가", novelId = 1),
            Character(id = 2, name = "나", novelId = 2)
        ),
        novels = listOf(
            Novel(id = 1, title = "A작품", universeId = uniA),
            Novel(id = 2, title = "B작품", universeId = uniB)
        ),
        fieldDefinitions = listOf(
            charField(10, "gender", "성별", type = "SELECT"),
            charField(20, "gender", "성별", universeId = uniB, type = "SELECT",
                config = if (bEnabled) "{}" else statsOff),
            charField(11, "job", "직업"),
            charField(21, "job", "직업", universeId = uniB,
                config = if (bEnabled) "{}" else statsOff)
        ),
        fieldValues = listOf(
            CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "남"),
            CharacterFieldValue(characterId = 1, fieldDefinitionId = 11, value = "검사"),
            CharacterFieldValue(characterId = 2, fieldDefinitionId = 20, value = "남"),
            CharacterFieldValue(characterId = 2, fieldDefinitionId = 21, value = "검사")
        )
    )

    @Test
    fun `교차분석은 인사이트 카드와 같은 범위를 센다`() {
        val s = crossSnapshot(bEnabled = true)
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "gender" }
        val cardCount = insight.analysisResults.firstNotNullOf { it.distributionData }["남"]
        val cross = provider.computeCrossAnalysis(s, 10L, 11L, null, null)!!
        assertEquals(cardCount, cross.crossTable["남"]?.get("검사"))
        assertEquals(2, cross.mergedUniverseCount)
    }

    @Test
    fun `통계에서 뺀 세계관 필드는 교차분석에서도 빠진다`() {
        // 종전: 인사이트는 A만 세는데 교차분석은 A+B를 세고 "세계관 2개 합산"이라 고지까지 했다.
        val s = crossSnapshot(bEnabled = false)
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "gender" }
        val cardCount = insight.analysisResults.firstNotNullOf { it.distributionData }["남"]
        assertEquals(1, cardCount)

        val cross = provider.computeCrossAnalysis(s, 10L, 11L, null, null)!!
        assertEquals(cardCount, cross.crossTable["남"]?.get("검사"))
        assertEquals("합산하지 않았으면 합산했다고 말하지 않는다", 1, cross.mergedUniverseCount)
    }

    @Test
    fun `교차분석은 카드와 같은 기준 def로 파싱한다`() {
        // 값이 속한 def의 설정으로 각각 파싱하면, 세계관마다 값 라벨이 다를 때 같은 저장값이
        // 서로 다른 칸으로 떨어진다 — 카드는 한 칸에 2명인데 교차표는 두 칸에 1명씩이 된다.
        val labeled = """{"stats":{"valueLabels":{"남":"남성"}}}"""
        val s = snapshot(
            characters = listOf(
                Character(id = 1, name = "가", novelId = 1),
                Character(id = 2, name = "나", novelId = 2)
            ),
            novels = listOf(
                Novel(id = 1, title = "A작품", universeId = uniA),
                Novel(id = 2, title = "B작품", universeId = uniB)
            ),
            fieldDefinitions = listOf(
                charField(10, "gender", "성별", type = "SELECT"),
                charField(20, "gender", "성별", universeId = uniB, type = "SELECT", config = labeled),
                charField(11, "job", "직업"),
                charField(21, "job", "직업", universeId = uniB)
            ),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "남"),
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 11, value = "검사"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 20, value = "남"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 21, value = "검사")
            )
        )
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "gender" }
        val cardCount = insight.analysisResults.firstNotNullOf { it.distributionData }["남"]
        assertEquals(2, cardCount)

        val cross = provider.computeCrossAnalysis(s, 10L, 11L, null, null)!!
        assertEquals(cardCount, cross.crossTable["남"]?.get("검사"))
        assertNull("기준 def로 통일했으면 다른 라벨 칸이 생기지 않는다", cross.crossTable["남성"])
    }

    @Test
    fun `하위 그룹 분석은 값 건수가 아니라 대상 수를 센다`() {
        // 행 라벨이 '명'이고 제목이 'N명 기준'이다. 값 건수를 세면 다중값 필드에서
        // 10명 전원이 가진 값이 33%로 표시되고 행의 합이 모집단을 넘는다.
        val chars = (1L..10L).map { Character(id = it, name = "c$it", novelId = 1) }
        val s = snapshot(
            characters = chars,
            fieldDefinitions = listOf(charField(10, "trait", "특성", type = "MULTI_TEXT")),
            fieldValues = chars.map {
                CharacterFieldValue(characterId = it.id, fieldDefinitionId = 10, value = "용감, 성실, 과묵")
            }
        )
        val analysis = provider.computeSubgroupAnalysis(s, chars.map { it.id }.toSet(), listOf(10L))!!
        assertEquals(10, analysis.totalCount)
        assertEquals("전원이 가진 값은 10명이다(30건이 아니다)", 10, analysis.distribution["용감"])
        assertEquals(10, analysis.distribution["성실"])
    }

    @Test
    fun `하위 그룹 분석은 상한을 넘긴 종수를 고지한다`() {
        val chars = (1L..20L).map { Character(id = it, name = "c$it", novelId = 1) }
        val s = snapshot(
            characters = chars,
            fieldDefinitions = listOf(charField(10, "home", "거주지")),
            fieldValues = chars.map {
                CharacterFieldValue(characterId = it.id, fieldDefinitionId = 10, value = "도시${it.id}")
            }
        )
        val analysis = provider.computeSubgroupAnalysis(s, chars.map { it.id }.toSet(), listOf(10L))!!
        assertEquals(SUBGROUP_DISTRIBUTION_LIMIT, analysis.distribution.size)
        assertEquals(5, analysis.truncatedCount)
    }

    @Test
    fun `동수일 때 패턴이 지목하는 값과 카드의 1위가 같다`() {
        // 집계 규칙이 갈리면 같은 데이터에 두 화면이 다른 값을 '최다'로 지목한다.
        val s = snapshot(
            characters = (1L..10L).map { Character(id = it, name = "c$it", novelId = 1) },
            fieldDefinitions = listOf(charField(10, "gender", "성별", type = "SELECT")),
            fieldValues = (1L..10L).map {
                // 먼저 등장하는 값이 '여'가 되도록 배치한다(첫 등장 순서가 이기면 '여'가 top).
                CharacterFieldValue(characterId = it, fieldDefinitionId = 10, value = if (it <= 5) "여" else "남")
            }
        )
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "gender" }
        val cardTop = insight.analysisResults.firstNotNullOf { it.distributionData }.keys.first()
        assertEquals("동수는 값 이름 오름차순", "남", cardTop)

        // 균형 패턴(모든 값 10~35%)은 안 잡히고 편중도 아니므로, 여기서는 집계 규칙만 확인한다.
        val patterns = provider.detectPatterns(s)
        val dominance = patterns.firstOrNull { it.type == PatternType.DOMINANCE }
        assertNull("50:50은 편중이 아니다", dominance)
    }

    @Test
    fun `합산하지 않은 축은 합산했다고 말하지 않는다`() {
        // 두 축의 세계관을 합집합으로 세면, 축1이 A에만·축2가 B에만 있을 때 어느 축도
        // 합치지 않았는데 "세계관 2개를 합산했습니다"라는 거짓 고지가 나간다.
        val s = snapshot(
            characters = listOf(
                Character(id = 1, name = "가", novelId = 1),
                Character(id = 2, name = "나", novelId = 2)
            ),
            novels = listOf(
                Novel(id = 1, title = "A작품", universeId = uniA),
                Novel(id = 2, title = "B작품", universeId = uniB)
            ),
            fieldDefinitions = listOf(
                charField(10, "gender", "성별", type = "SELECT"),
                charField(21, "job", "직업", universeId = uniB)
            ),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "남"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 21, value = "검사")
            )
        )
        val cross = provider.computeCrossAnalysis(s, 10L, 21L, null, null)!!
        assertEquals(1, cross.mergedUniverseCount)
    }

    @Test
    fun `사용자가 직접 고른 필드는 꺼져 있어도 계산한다`() {
        // 고를 수 있는데 빈 표가 나오는 조용한 실패를 만들지 않는다(R-17).
        // 고른 def는 설정과 무관하게 들어가고, 켜진 형제는 평소처럼 함께 합산된다 —
        // 여기서는 B(꺼짐)를 골랐고 A(켜짐)가 형제로 합류해 두 캐릭터가 모두 잡힌다.
        val s = crossSnapshot(bEnabled = false)
        val cross = provider.computeCrossAnalysis(s, 20L, 21L, null, null)
        assertNotNull(cross)
        assertEquals(2, cross!!.crossTable["남"]?.get("검사"))
    }

    // ===== B-33: 패턴 감지가 계산 필드를 센다 =====

    @Test
    fun `패턴 감지가 계산 필드를 분석한다`() {
        val s = snapshot(
            characters = (1L..10L).map { Character(id = it, name = "c$it", novelId = 1) },
            fieldDefinitions = listOf(
                charField(10, "power", "힘", type = "NUMBER"),
                charField(11, "tier", "등급대", type = "CALCULATED",
                    config = """{"formula":"field('power') * 0"}""")
            ),
            fieldValues = (1L..10L).map {
                CharacterFieldValue(characterId = it, fieldDefinitionId = 10, value = "$it")
            }
        )
        // 계산 결과가 전원 "0" → 100% 편중이 잡혀야 한다(저장 행만 읽으면 아무것도 안 잡힌다).
        val patterns = provider.detectPatterns(s)
        assertTrue(
            "계산 필드가 패턴 감지에서 통째로 빠지면 안 된다",
            patterns.any { it.type == PatternType.DOMINANCE && it.title.contains("등급대") }
        )
    }

    @Test
    fun `계산 필드의 사용자 구간 설정이 분포에 반영된다`() {
        val s = snapshot(
            characters = listOf(
                Character(id = 1, name = "가", novelId = 1),
                Character(id = 2, name = "나", novelId = 1)
            ),
            fieldDefinitions = listOf(
                charField(10, "power", "힘", type = "NUMBER"),
                charField(11, "calc", "계산", type = "CALCULATED",
                    config = """{"formula":"field('power')","stats":{"binning":{"mode":"custom","ranges":["~50:낮음","50~:높음"]}}}""")
            ),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "10"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "90")
            )
        )
        val insight = provider.computeFieldInsights(s).first { it.fieldDefinition.key == "calc" }
        val dist = insight.analysisResults.firstNotNullOf { it.distributionData }
        assertEquals(1, dist["낮음"])
        assertEquals(1, dist["높음"])
    }

    // ===== 구간 라벨 계약 =====

    @Test
    fun `구간 분포의 라벨은 구간 생성 규칙이 만든 것과 같다`() {
        val s = bodySnapshot()
        val dist = provider.computeFieldAnalysis(s).fieldValueDistributions
            .first { it.fieldName.startsWith("신체") }
        val expected = NumericBinning.autoBins(listOf(150f, 160f, 170f, 180f, 200f)).map { it.label }
        assertEquals(expected, dist.distribution.keys.toList())
    }

    @Test
    fun `구간 분포 라벨과 스펙은 어긋나지 않는다`() {
        val s = bodySnapshot()
        val dist = provider.computeFieldAnalysis(s).fieldValueDistributions
            .first { it.fieldName.startsWith("신체") }
        assertNull(
            "값 일치 스펙이 섞이면 구간 조각이 다시 0명이 된다",
            dist.matchSpecs.values.firstOrNull { it !is FieldValueMatchSpec.NumericPartRange }
        )
    }

    // ===== B-100 완성도 — 화면 간 한 값이어야 한다 =====

    /**
     * 완성도 대조용 스냅샷: A세계관에 필수 1 + 일반 1, 캐릭터는 필수만 채웠다.
     * [strayValues]는 **다른 세계관 정의를 가리키는 보존 값**이다
     * (`CharacterFieldValueMerge`가 작품을 옮긴 캐릭터에게 일부러 남기는 것).
     */
    private fun completionSnapshot(
        weights: com.novelcharacter.app.util.CompletionWeights,
        strayValues: Boolean = false
    ): StatsSnapshot {
        val required = charField(11, "name", "이름").copy(isRequired = true)
        val optional = charField(12, "hobby", "취미")
        val foreign = charField(21, "law", "법칙", universeId = uniB)
        val values = mutableListOf(
            CharacterFieldValue(characterId = 1, fieldDefinitionId = required.id, value = "홍길동")
        )
        if (strayValues) {
            values += CharacterFieldValue(characterId = 1, fieldDefinitionId = foreign.id, value = "잔류")
        }
        return snapshot(
            characters = listOf(Character(id = 1, name = "홍길동", novelId = 1)),
            fieldDefinitions = listOf(required, optional, foreign),
            fieldValues = values
        ).copy(completionWeights = weights)
    }

    @Test
    fun `완성도는 요약과 캐릭터 통계와 복잡도가 같은 값을 낸다`() {
        // 종전에는 셋이 분자를 다르게 셌다(값 전부 대 교집합) — 같은 캐릭터가 화면마다 달랐다.
        val s = completionSnapshot(com.novelcharacter.app.util.CompletionWeights(2f))
        val stats = provider.computeCharacterStats(s)
        val fromStats = stats.fieldCompletionRates.first { it.first == "홍길동" }.second
        val fromComplexity = stats.complexityScores.first { it.name == "홍길동" }.fieldCompletionRate!!
        val fromSummary = provider.computeSummary(s).avgFieldCompletion

        assertEquals(fromStats, fromComplexity, 0.01f)
        assertEquals(fromStats, fromSummary, 0.01f)
        // 필수(×2) 채움 + 일반 미채움 = 2 / 3
        assertEquals(200f / 3f, fromStats, 0.01f)
    }

    @Test
    fun `다른 세계관 정의를 가리키는 보존 값은 완성도를 부풀리지 않는다`() {
        val weights = com.novelcharacter.app.util.CompletionWeights(2f)
        val clean = provider.computeCharacterStats(completionSnapshot(weights))
            .fieldCompletionRates.first().second
        val withStray = provider.computeCharacterStats(completionSnapshot(weights, strayValues = true))
            .fieldCompletionRates.first().second
        assertEquals("보존 값은 이 세계관의 칸이 아니다", clean, withStray, 0.01f)
        assertTrue("완성도가 100%를 넘을 수 없다", withStray <= 100f)
    }

    @Test
    fun `필수가 없으면 가중을 바꿔도 완성도가 그대로다`() {
        // 사용자 확정 ㄴ1 — 필수를 쓰지 않는 사용자의 숫자는 이유 없이 움직이지 않는다.
        val base = completionSnapshot(com.novelcharacter.app.util.CompletionWeights.NONE)
        val noRequired = base.copy(
            fieldDefinitions = base.fieldDefinitions.map { it.copy(isRequired = false) }
        )
        val plain = provider.computeCharacterStats(noRequired).fieldCompletionRates.first().second
        val weighted = provider.computeCharacterStats(
            noRequired.copy(completionWeights = com.novelcharacter.app.util.CompletionWeights(5f))
        ).fieldCompletionRates.first().second
        assertEquals(plain, weighted, 0.0001f)
        assertEquals(50f, plain, 0.01f)
    }

    @Test
    fun `미흡 판정은 데이터 건강의 명단과 데이터 개요의 개수가 일치한다`() {
        // 임계값이 두 곳에 따로 박혀 있으면(50f 대 0.5f) 한쪽만 바뀌어도 아무도 모른다.
        val s = completionSnapshot(com.novelcharacter.app.util.CompletionWeights(2f))
        val named = provider.computeDataHealth(s).incompleteFieldChars.map { it.first }
        val counted = provider.computeDataOverview(s).healthWarnings.incompleteFieldCount
        assertEquals(named.size, counted)
    }

    // ===== R-51/R-18: 축마다 자기 모수로 좁힌다 (스코프 밖이 순위·평균에 새지 않는다) =====
    //
    // 스코프 필터는 관계·교차참조를 `한쪽 끝이 스코프 안`으로 남긴다 — 그 OR은 '고립'·'사건
    // 미연계' 판정에 필요하다. 그래서 **좁히는 일은 소비처가** 해야 한다.

    private fun scopedSnapshot() = StatsSnapshot(
        // 스코프 안 둘(A작품), 스코프 밖 하나는 아예 목록에 없다(필터가 이미 잘랐다).
        characters = listOf(
            Character(id = 1, name = "안쪽1", novelId = 1),
            Character(id = 2, name = "안쪽2", novelId = 1)
        ),
        novels = listOf(Novel(id = 1, title = "A작품", universeId = uniA)),
        universes = listOf(Universe(id = uniA, name = "A")),
        // 사건 하나만 스코프 안이다.
        events = listOf(
            com.novelcharacter.app.data.model.TimelineEvent(id = 100, year = 1, description = "안쪽사건")
        ),
        // 스코프 밖 인물(id=9)이 안쪽 둘과 이어져 있다 — OR이 남긴 행이다.
        relationships = listOf(
            com.novelcharacter.app.data.model.CharacterRelationship(
                id = 1, characterId1 = 9, characterId2 = 1, relationshipType = "친구"
            ),
            com.novelcharacter.app.data.model.CharacterRelationship(
                id = 2, characterId1 = 9, characterId2 = 2, relationshipType = "친구"
            )
        ),
        relationshipChanges = emptyList(), tags = emptyList(), nameBank = emptyList(),
        stateChanges = emptyList(), fieldDefinitions = emptyList(), fieldValues = emptyList(),
        // 안쪽 인물이 **스코프 밖 사건**(id=999)에 참여한 행 — OR이 남긴다.
        crossRefs = listOf(
            com.novelcharacter.app.data.model.TimelineCharacterCrossRef(eventId = 999, characterId = 1)
        )
    )

    @Test
    fun `관계 순위에 스코프 밖 인물이 끼어들지 않는다`() {
        val stats = provider.computeCharacterStats(scopedSnapshot())
        assertTrue("'?' 행이 떴다: ${stats.topRelationshipChars}",
            stats.topRelationshipChars.none { it.first == "?" })
        assertEquals(setOf("안쪽1", "안쪽2"), stats.topRelationshipChars.map { it.first }.toSet())
    }

    @Test
    fun `사건 연계 순위도 스코프 안 인물만 센다`() {
        val stats = provider.computeCharacterStats(scopedSnapshot())
        assertTrue(stats.topEventLinkedChars.none { it.first == "?" })
    }

    @Test
    fun `관계 상세의 순위도 같은 규칙을 쓴다`() {
        val stats = provider.computeRelationshipStats(scopedSnapshot())
        assertTrue("'?' 행이 떴다: ${stats.topConnectedChars}",
            stats.topConnectedChars.none { it.first == "?" })
    }

    /** 최댓값이 스코프 밖 인물이면 종전에는 이름이 null이 되어 **안내줄이 통째로 사라졌다.** */
    @Test
    fun `가장 관계가 많은 캐릭터 안내줄이 사라지지 않는다`() {
        val summary = provider.computeSummary(scopedSnapshot())
        assertNotNull("안내줄이 사라졌다", summary.mostConnectedChar)
        assertTrue(summary.mostConnectedChar in setOf("안쪽1", "안쪽2"))
    }

    /**
     * 사건 축 평균의 분자는 **스코프 안 사건의 참여**뿐이다. 종전에는 참여자가 0명인 사건
     * 하나뿐인데도 "사건당 평균 1.0"과 "미연계 1"을 한 카드가 동시에 말했다.
     */
    @Test
    fun `사건당 평균 캐릭터가 스코프 밖 참여를 세지 않는다`() {
        val stats = provider.computeEventStats(scopedSnapshot())
        assertEquals(0f, stats.avgCharsPerEvent, 1e-6f)
        assertEquals(1, stats.orphanEventCount)
    }

    // ===== R-34: 완성도의 분자는 분모 집합과의 교집합이다 =====

    /**
     * 작품을 '없음'으로 바꾼 캐릭터의 값은 **지워지지 않는다** — 값 병합 규약이 일부러
     * 보존하고(*"폼이 비어도 기존 값을 전량 보존한다"*) 시험이 그것을 잠근다. 그 캐릭터는
     * 모수(실재하는 작품을 경유해야 센다)에는 없으므로, 분자가 그 값을 세면 **완성도가
     * 100%를 넘는다.**
     */
    private fun keptValueSnapshot() = snapshot(
        characters = listOf(
            Character(id = 1, name = "소속1", novelId = 1),
            Character(id = 2, name = "소속2", novelId = 1),
            Character(id = 3, name = "무소속", novelId = null)   // 값은 남아 있다
        ),
        fieldDefinitions = listOf(charField(10, "power", "힘")),
        fieldValues = listOf(
            CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "10"),
            CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "20"),
            CharacterFieldValue(characterId = 3, fieldDefinitionId = 10, value = "30")
        )
    )

    @Test
    fun `데이터 개요 완성도가 100퍼센트를 넘지 않는다`() {
        val detail = provider.computeDataOverview(keptValueSnapshot()).fieldCompletionByField.single()
        assertEquals(2, detail.filledCount)
        assertEquals(2, detail.totalCount)
        assertEquals(100f, detail.completionRate, 1e-3f)
    }

    @Test
    fun `필드 분석 완성도도 같은 모수를 쓴다`() {
        val detail = provider.computeFieldAnalysis(keptValueSnapshot()).fieldCompletionByField.single()
        assertEquals(2, detail.filledCount)
        assertEquals(2, detail.totalCount)
    }

    /** 다른 세계관 정의를 가리키는 보관 값도 분자가 아니다 — 그 칸의 것이 아니다. */
    @Test
    fun `다른 구역 정의의 보관 값은 세지 않는다`() {
        val s = snapshot(
            characters = listOf(Character(id = 1, name = "A소속", novelId = 1)),
            fieldDefinitions = listOf(charField(10, "power", "힘", universeId = uniB)),
            fieldValues = listOf(CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "10"))
        )
        val detail = provider.computeDataOverview(s).fieldCompletionByField.single()
        assertEquals(0, detail.filledCount)
    }

    // ===== 순위표 요약: 참여 + 제외가 모수와 맞는다 (음수 금지) =====

    @Test
    fun `제외 인원이 음수가 되지 않는다`() {
        // 보관 값 보유자(작품 '없음')는 순위표에 실리지만 모수에는 없다 — 뺄셈이면 음수다.
        val s = snapshot(
            characters = listOf(
                Character(id = 1, name = "소속1", novelId = 1),
                Character(id = 2, name = "소속2", novelId = 1),
                Character(id = 3, name = "무소속", novelId = null)
            ),
            fieldDefinitions = listOf(charField(10, "power", "힘", type = "NUMBER")),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "10"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "20"),
                CharacterFieldValue(characterId = 3, fieldDefinitionId = 10, value = "30")
            )
        )
        val r = provider.computeRanking(s, listOf(10L), ascending = false)
        assertEquals(3, r.entries.size)
        assertTrue("제외가 음수다: ${r.excludedCount}", r.excludedCount >= 0)
        assertEquals(0, r.excludedCount)
    }

    /** 값이 없는 캐릭터는 정확히 한 번만 제외로 세어진다. */
    @Test
    fun `값이 없는 캐릭터가 제외로 한 번 세어진다`() {
        val s = snapshot(
            characters = listOf(
                Character(id = 1, name = "값있음", novelId = 1),
                Character(id = 2, name = "값없음", novelId = 1)
            ),
            fieldDefinitions = listOf(charField(10, "power", "힘", type = "NUMBER")),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "10")
            )
        )
        val r = provider.computeRanking(s, listOf(10L), ascending = false)
        assertEquals(1, r.entries.size)
        assertEquals(1, r.excludedCount)
    }

    /** 값이 깨진 캐릭터는 표에 없고 제외에 **한 번만** 든다(종전에는 행 수로 두 번 셌다). */
    @Test
    fun `파싱 실패가 표와 제외에 동시에 들어가지 않는다`() {
        val s = snapshot(
            characters = listOf(
                Character(id = 1, name = "정상", novelId = 1),
                Character(id = 2, name = "깨짐", novelId = 1)
            ),
            fieldDefinitions = listOf(charField(10, "power", "힘", type = "NUMBER")),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "10"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "abc")
            )
        )
        val r = provider.computeRanking(s, listOf(10L), ascending = false)
        assertEquals(1, r.entries.size)
        assertEquals(1, r.excludedCount)
    }
}
