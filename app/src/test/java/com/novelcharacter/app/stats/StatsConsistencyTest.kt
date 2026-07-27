package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.PatternType
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot
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
}
