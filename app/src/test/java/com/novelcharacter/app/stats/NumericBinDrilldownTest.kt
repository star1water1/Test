package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
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
 * 수치 자동 구간 분포와 히스토그램 드릴다운의 계약 (B-39, 로드맵 13판).
 *
 * 고정하는 것:
 * - **자동 구간 분포가 존재한다** — 종전에는 사용자 구간(custom)일 때만 그렸고, 자동일 때는
 *   `continue`로 건너뛰어 NUMBER 필드가 레거시 분석 화면에서 통째로 빠졌다.
 * - **구간 조각이 정확히 그 구간의 대상만 집는다** — 라벨은 계산 결과라 저장값과 같을 수
 *   없으므로, 구간 규칙 자체를 스펙으로 실어야만 성립한다(S-16이 BODY_SIZE에서 겪은 결함).
 * - **두 화면의 구간이 한 벌이다** — 레거시 상세가 자체 8등분을 들고 있어 같은 필드가 인사이트
 *   화면(5등분)과 다른 모양으로 보였다.
 * - **값을 뽑는 규칙이 한 벌이다** — 분포가 `toFloatOrNull()`을, 드릴다운이 `partValue()`(트림)를
 *   따로 쓰면 **줄바꿈 없는 공백(U+00A0)이 낀 값이 한쪽에만 잡힌다.** 일반 공백은 양쪽이 같게
 *   보므로(`parseFloat`가 ASCII 공백을 건너뛴다) 이 자리는 그 문자로만 드러난다.
 */
class NumericBinDrilldownTest {

    private val provider = StatsDataProvider()
    private val uniA = 1L

    private fun numberField(config: String = "{}", type: String = "NUMBER") = FieldDefinition(
        id = 10, universeId = uniA, key = "height", name = "키", type = type, config = config,
        entityType = FieldDefinition.ENTITY_CHARACTER
    )

    private fun snapshot(
        fieldDefinitions: List<FieldDefinition>,
        rawValues: List<String>
    ): StatsSnapshot {
        val characters = rawValues.indices.map {
            Character(id = it + 1L, name = "c${it + 1}", novelId = 1)
        }
        return StatsSnapshot(
            characters = characters,
            novels = listOf(Novel(id = 1, title = "A작품", universeId = uniA)),
            universes = listOf(Universe(id = uniA, name = "A")),
            events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
            tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
            fieldDefinitions = fieldDefinitions,
            fieldValues = rawValues.mapIndexed { i, v ->
                CharacterFieldValue(characterId = i + 1L, fieldDefinitionId = 10, value = v)
            },
            crossRefs = emptyList()
        )
    }

    /** 150·160·170·180·200 — 자동 5등분이 겹치지 않는 라벨을 내는 구성. */
    private fun heightSnapshot(config: String = "{}") =
        snapshot(listOf(numberField(config)), listOf("150", "160", "170", "180", "200"))

    private fun heightDistribution(s: StatsSnapshot) =
        provider.computeFieldAnalysis(s).fieldValueDistributions.first { it.fieldName == "키" }

    // ===== 자동 구간 분포가 존재한다 =====

    @Test
    fun `자동 구간 필드도 분포를 그린다`() {
        // 종전: `binning.mode != custom`이면 continue — 사용자가 구간을 손으로 적기 전까지
        // NUMBER 필드는 이 화면의 분포에서 아예 빠져 있었다.
        val dist = heightDistribution(heightSnapshot())
        assertEquals(
            "자동 구간은 NumericBinning의 기본 구간 수를 따른다",
            NumericBinning.DEFAULT_BIN_COUNT, dist.distribution.size
        )
        assertEquals("모든 값이 어느 구간엔가 든다", 5, dist.distribution.values.sum())
    }

    @Test
    fun `자동 구간의 라벨은 구간 생성 규칙이 만든 것과 같다`() {
        val dist = heightDistribution(heightSnapshot())
        val expected = NumericBinning.autoBins(listOf(150f, 160f, 170f, 180f, 200f)).map { it.label }
        assertEquals(expected, dist.distribution.keys.toList())
    }

    @Test
    fun `자동 구간 분포는 건수순으로 재정렬되지 않는다`() {
        // 인접 구간이 흩어지면 '어디에 몰렸는가'를 읽을 수 없다 — 순서 자체가 정보다.
        assertTrue(heightDistribution(heightSnapshot()).orderedByValue)
    }

    // ===== 조각이 정확히 그 구간의 대상만 집는다 =====

    @Test
    fun `자동 구간 조각의 인원이 분포 건수와 일치한다`() {
        val s = heightSnapshot()
        val dist = heightDistribution(s)
        assertEquals("구간마다 매치 스펙이 있어야 한다", dist.distribution.size, dist.matchSpecs.size)

        var totalListed = 0
        for ((label, count) in dist.distribution) {
            val spec = dist.matchSpecs.getValue(label)
            val listed = provider.getCharactersByFieldValue(s, listOf(10L), spec)!!
            assertEquals("[$label] 조각 수치와 드릴다운 인원이 같아야 한다", count, listed.size)
            totalListed += listed.size
        }
        assertEquals("모든 캐릭터가 정확히 한 구간에 든다", 5, totalListed)
    }

    @Test
    fun `자동 구간 스펙은 값 일치가 아니라 구간이다`() {
        // 값 일치 스펙이 섞이면 그 조각만 조용히 0명이 된다 — 화면에서는 구분되지 않는다.
        val dist = heightDistribution(heightSnapshot())
        assertNull(
            "자동 구간에 Values 스펙이 섞이면 안 된다",
            dist.matchSpecs.values.firstOrNull { it !is FieldValueMatchSpec.NumericPartRange }
        )
    }

    @Test
    fun `라벨만 넘기면 아무도 못 찾는다 — 옛 동작의 실체`() {
        // 이 사실을 남겨 둬야 위 두 테스트가 무엇을 막고 있는지 분명해진다.
        val s = heightSnapshot()
        val label = heightDistribution(s).distribution.keys.first()
        assertEquals(0, provider.getCharactersByFieldValue(s, listOf(10L), label)!!.size)
    }

    @Test
    fun `최댓값은 마지막 구간에서 사라지지 않는다`() {
        // 마지막 구간만 상한을 포함한다는 규칙이 스펙에도 실려야 한다 — 빠지면 최댓값을 가진
        // 캐릭터가 어느 조각에도 안 들어 분포 합이 모집단보다 작아진다.
        val s = heightSnapshot()
        val dist = heightDistribution(s)
        val lastLabel = dist.distribution.keys.last()
        val listed = provider.getCharactersByFieldValue(s, listOf(10L), dist.matchSpecs.getValue(lastLabel))!!
        assertTrue("200을 가진 캐릭터가 마지막 구간에 있어야 한다", listed.any { it.fieldValue == "200" })
    }

    // ===== 값을 뽑는 규칙이 한 벌이다 =====

    @Test
    fun `줄바꿈 없는 공백이 낀 값도 분포와 목록 양쪽에 똑같이 잡힌다`() {
        // **일반 공백으로는 이 자리가 갈리지 않는다** — `Float.parseFloat`가 ASCII 공백을
        // 이미 건너뛰기 때문이다. 갈리는 것은 **줄바꿈 없는 공백(NBSP, U+00A0)**이다:
        // 코틀린 `trim()`은 `isSpaceChar`도 보므로 지우지만 `parseFloat`는 지우지 않는다.
        // 그래서 분포가 `toFloatOrNull()`을 직접 쓰면 이 값이 **분포에서만 빠지고 드릴다운
        // 목록에는 뜬다** — 조각 수치와 목록 인원이 갈리는 그 모양이다(S-16).
        // 엑셀·웹에서 붙여 넣은 값이 실제로 이 문자를 달고 온다.
        val s = snapshot(listOf(numberField()), listOf("150", "\u00A0170\u00A0", "200"))
        val dist = heightDistribution(s)
        assertEquals("공백이 낀 값도 모집단이다", 3, dist.distribution.values.sum())

        var totalListed = 0
        for ((label, count) in dist.distribution) {
            val listed = provider.getCharactersByFieldValue(s, listOf(10L), dist.matchSpecs.getValue(label))!!
            assertEquals("[$label]", count, listed.size)
            totalListed += listed.size
        }
        assertEquals(3, totalListed)
    }

    @Test
    fun `수치가 아닌 값은 어느 구간에도 들지 않는다`() {
        // 구간은 수치의 것이다. 파싱되지 않는 값을 억지로 넣으면 그 구간의 뜻이 무너진다 —
        // 다만 모집단에서 빠졌다는 사실은 요약의 count가 말한다.
        val s = snapshot(listOf(numberField()), listOf("150", "170", "200", "미상"))
        val dist = heightDistribution(s)
        assertEquals(3, dist.distribution.values.sum())
    }

    // ===== 두 화면의 구간이 한 벌이다 =====

    @Test
    fun `레거시 수치 요약과 인사이트가 같은 구간을 쓴다`() {
        // 종전: 레거시 상세는 자체 8등분, 인사이트는 NumericBinning 5등분 — 같은 필드가
        // 화면마다 다른 모양이었다.
        // 공백이 낀 값을 섞는다 — 두 경로 중 한쪽만 `toFloatOrNull()`로 돌아가도 여기서 갈린다.
        // (값 목록이 같아야 min/max가 같고, 그래야 자동 구간의 경계 자체가 같아진다.)
        val s = snapshot(
            listOf(numberField("""{"stats":{"analyses":[{"type":"numeric"}]}}""")),
            listOf("150", "\u00A0160\u00A0", "170", "180", "200")
        )
        val legacy = provider.computeFieldAnalysis(s).numberFieldSummaries.first { it.fieldName == "키" }
        val insight = provider.computeFieldInsights(s)
            .first { it.fieldDefinition.key == "height" }
            .analysisResults.firstNotNullOf { it.numericSummary }

        assertEquals(5, legacy.count)
        assertEquals(insight.histogram, legacy.histogram)
        assertEquals(insight.matchSpecs, legacy.matchSpecs)
    }

    @Test
    fun `레거시 히스토그램 막대의 인원이 막대 높이와 일치한다`() {
        val s = heightSnapshot()
        val summary = provider.computeFieldAnalysis(s).numberFieldSummaries.first { it.fieldName == "키" }
        assertEquals("드릴다운 대상 필드를 요약이 들고 있어야 한다", 10L, summary.fieldDefId)
        assertTrue("막대가 있어야 한다", summary.histogram.isNotEmpty())

        for ((label, count) in summary.histogram) {
            val spec = summary.matchSpecs.getValue(label)
            val listed = provider.getCharactersByFieldValue(s, listOf(summary.fieldDefId), spec)!!
            assertEquals("[$label] 막대 높이와 드릴다운 인원이 같아야 한다", count, listed.size)
        }
    }

    // ===== 사용자 구간도 같은 종류의 스펙을 싣는다 =====

    @Test
    fun `사용자 구간 히스토그램도 구간 스펙을 싣는다`() {
        // 사용자 구간은 `getFieldValues`가 라벨을 돌려주므로 라벨 매칭으로도 **우연히** 되지만,
        // BODY_SIZE 파트에서는 그 우연이 성립하지 않는다. 한쪽만 조용히 죽지 않도록 둘 다
        // 구간 스펙으로 통일한다.
        val config = """{"stats":{"analyses":[{"type":"numeric"}],"binning":{"mode":"custom","ranges":["~160:작은","160~180:보통","180~:큰"]}}}"""
        val s = heightSnapshot(config)
        val summary = provider.computeFieldAnalysis(s).numberFieldSummaries.first { it.fieldName == "키" }

        assertEquals(listOf("작은", "보통", "큰"), summary.histogram.keys.toList())
        assertNull(
            "사용자 구간에도 값 일치 스펙이 섞이면 안 된다",
            summary.matchSpecs.values.firstOrNull { it !is FieldValueMatchSpec.NumericPartRange }
        )
        for ((label, count) in summary.histogram) {
            val listed = provider.getCharactersByFieldValue(s, listOf(10L), summary.matchSpecs.getValue(label))!!
            assertEquals("[$label]", count, listed.size)
        }
    }

    @Test
    fun `열린 구간은 경계 없이 판정한다`() {
        // `~160`·`180~`은 한쪽 경계가 없다. 스펙의 경계는 non-null이라 ±무한으로 싣는데,
        // 유한값에 대해 무한 비교는 '경계 없음'과 정확히 같은 뜻이어야 한다.
        val config = """{"stats":{"analyses":[{"type":"numeric"}],"binning":{"mode":"custom","ranges":["~160:작은","160~180:보통","180~:큰"]}}}"""
        val s = heightSnapshot(config)
        val summary = provider.computeFieldAnalysis(s).numberFieldSummaries.first { it.fieldName == "키" }

        // 150 → 작은 / 160·170 → 보통 / 180·200 → 큰
        assertEquals(1, summary.histogram["작은"])
        assertEquals(2, summary.histogram["보통"])
        assertEquals(2, summary.histogram["큰"])
    }

    // ===== 어느 구간에도 안 드는 값 (B-197) =====

    /** `~160`·`180~` — 160 이상 180 미만이 **정의에서 빠진** 구성. 150·160·170·180·200 중 둘이 여기 든다. */
    private val gappedConfig =
        """{"stats":{"analyses":[{"type":"numeric"}],"binning":{"mode":"custom","ranges":["~160:작은","180~:큰"]}}}"""

    private fun gappedSummary(s: StatsSnapshot) =
        provider.computeFieldAnalysis(s).numberFieldSummaries.first { it.fieldName == "키" }

    @Test
    fun `사이가 빈 구간에서도 막대 합이 모집단과 같다`() {
        // 종전: 구간마다 따로 세므로 160·170이 **어느 막대에도 없었고**, 그 사실을 말하는 자리도
        // 없었다. 막대 합 3 < 모집단 5인데 화면에는 아무 설명이 없다.
        val summary = gappedSummary(snapshot(listOf(numberField(gappedConfig)),
            listOf("150", "160", "170", "180", "200")))

        assertEquals(1, summary.histogram["작은"])
        assertEquals(2, summary.histogram["큰"])
        assertEquals("어디에도 안 드는 둘이 보여야 한다", 2, summary.histogram[StatsDataProvider.OUT_OF_RANGE_LABEL])
        assertEquals("막대 합은 모집단과 같아야 한다", 5, summary.histogram.values.sum())
    }

    @Test
    fun `구간 밖 막대도 눌리고 정확히 그 사람들만 나온다`() {
        val s = snapshot(listOf(numberField(gappedConfig)), listOf("150", "160", "170", "180", "200"))
        val summary = gappedSummary(s)

        for ((label, count) in summary.histogram) {
            val listed = provider.getCharactersByFieldValue(s, listOf(10L), summary.matchSpecs.getValue(label))!!
            assertEquals("[$label] 막대 높이와 드릴다운 인원이 같아야 한다", count, listed.size)
        }
        val outside = provider.getCharactersByFieldValue(
            s, listOf(10L), summary.matchSpecs.getValue(StatsDataProvider.OUT_OF_RANGE_LABEL)
        )!!
        assertEquals(setOf("c2", "c3"), outside.map { it.characterName }.toSet())
    }

    @Test
    fun `구간 밖은 값 일치가 아니라 여집합 규칙으로 싣는다`() {
        // 라벨('구간 밖')로 실으면 NUMBER·CALCULATED에서는 통계 파싱이 같은 라벨을 돌려줘
        // **우연히** 맞지만, 같은 분기가 도는 BODY_SIZE 파트에서는 그 우연이 없다.
        val summary = gappedSummary(snapshot(listOf(numberField(gappedConfig)), listOf("150", "170")))
        assertTrue(
            "여집합은 규칙으로 실려야 한다",
            summary.matchSpecs.getValue(StatsDataProvider.OUT_OF_RANGE_LABEL)
                is FieldValueMatchSpec.NumericPartOutsideRanges
        )
    }

    @Test
    fun `구간이 값을 다 덮으면 구간 밖 막대는 없다`() {
        // 정의된 구간은 0이어도 남긴다(거기가 비었다는 정보다). 여집합의 0은 반대로
        // *구간 정의가 값을 다 덮었다*는 뜻이라 막대로 말할 것이 없다 — 레거시 분포가
        // `counted[OUT_OF_RANGE_LABEL]?.let`으로 세우는 그 규칙과 같다.
        val config = """{"stats":{"analyses":[{"type":"numeric"}],"binning":{"mode":"custom","ranges":["~160:작은","160~180:보통","180~:큰"]}}}"""
        val summary = gappedSummary(heightSnapshot(config))
        assertNull(summary.histogram[StatsDataProvider.OUT_OF_RANGE_LABEL])
    }

    @Test
    fun `마지막 구간의 상한 포함이 여집합과 어긋나지 않는다`() {
        // 상한 포함 규칙이 막대 쪽과 여집합 쪽에 따로 적히면, 경계값(200)이 **양쪽에 다 들거나
        // 양쪽에서 다 빠진다.** 그래서 판정식을 하나로 묶어 두었다.
        val config = """{"stats":{"analyses":[{"type":"numeric"}],"binning":{"mode":"custom","ranges":["~160:작은","180~200:큰"]}}}"""
        val s = snapshot(listOf(numberField(config)), listOf("150", "200"))
        val summary = gappedSummary(s)

        assertEquals("경계값은 마지막 구간에 든다", 1, summary.histogram["큰"])
        assertNull("그러므로 여집합에는 없다", summary.histogram[StatsDataProvider.OUT_OF_RANGE_LABEL])
        assertEquals(2, summary.histogram.values.sum())
    }

    @Test
    fun `구간 정의가 하나도 파싱되지 않으면 전부 구간 밖이다`() {
        // mode는 custom인데 ranges가 전부 형식 이탈인 구성. 종전에는 히스토그램이 통째로 비어
        // **아무 말도 없었다** — 값이 있는데 안 보이는 그 자리다(R-17).
        val config = """{"stats":{"analyses":[{"type":"numeric"}],"binning":{"mode":"custom","ranges":["말이 안 되는 구간"]}}}"""
        val s = snapshot(listOf(numberField(config)), listOf("150", "170"))
        val summary = gappedSummary(s)

        assertEquals(2, summary.histogram[StatsDataProvider.OUT_OF_RANGE_LABEL])
        val listed = provider.getCharactersByFieldValue(
            s, listOf(10L), summary.matchSpecs.getValue(StatsDataProvider.OUT_OF_RANGE_LABEL)
        )!!
        assertEquals(2, listed.size)
    }

    @Test
    fun `수치로 읽히지 않는 값은 구간 밖 막대에 들지 않는다`() {
        // 히스토그램의 모집단은 `numericValuesOf`가 뽑은 수치뿐이다. 여집합이 비수치까지 집으면
        // **막대 높이보다 드릴다운 목록이 길어진다**(레거시 분포는 비수치도 같은 라벨에 담지만
        // 그쪽 모집단은 파싱 키 전량이라 자기 막대와는 맞는다).
        val s = snapshot(listOf(numberField(gappedConfig)), listOf("150", "170", "모름"))
        val summary = gappedSummary(s)

        assertEquals("수치 둘만 모집단이다", 2, summary.histogram.values.sum())
        assertEquals(1, summary.histogram[StatsDataProvider.OUT_OF_RANGE_LABEL])
        val listed = provider.getCharactersByFieldValue(
            s, listOf(10L), summary.matchSpecs.getValue(StatsDataProvider.OUT_OF_RANGE_LABEL)
        )!!
        assertEquals(listOf("c2"), listed.map { it.characterName })
    }

    @Test
    fun `체형 파트에서도 구간 밖이 보이고 눌린다`() {
        // **이 시험이 여집합 스펙의 값을 증명한다.** BODY_SIZE는 `isBinnable`이 아니라 통계 파싱이
        // '구간 밖' 라벨을 만들지 않는데, 히스토그램의 사용자 구간 분기는 이 타입에서도 돈다.
        // 라벨로 실었다면 여기서만 0명이 나왔을 자리다(S-16이 자동 구간에서 겪은 그 결함).
        val bodyField = FieldDefinition(
            id = 10, universeId = uniA, key = "body", name = "키", type = "BODY_SIZE",
            config = gappedConfig, entityType = FieldDefinition.ENTITY_CHARACTER
        )
        val s = snapshot(listOf(bodyField), listOf("150-80-90", "170-80-90", "200-80-90"))
        val summary = provider.computeFieldAnalysis(s).numberFieldSummaries
            .first { it.matchSpecs.containsKey(StatsDataProvider.OUT_OF_RANGE_LABEL) }

        assertEquals(1, summary.histogram[StatsDataProvider.OUT_OF_RANGE_LABEL])
        val listed = provider.getCharactersByFieldValue(
            s, listOf(10L), summary.matchSpecs.getValue(StatsDataProvider.OUT_OF_RANGE_LABEL)
        )!!
        assertEquals("파트 수치 170만 구간 밖이다", listOf("c2"), listed.map { it.characterName })
    }

    // ===== 나눌 구간이 없는 경우 =====

    @Test
    fun `값이 전부 같으면 막대 하나이고 그 막대도 눌린다`() {
        // 폭이 0이면 구간을 나눌 수 없다. 그렇다고 막대를 없애면 "값이 있는데 아무것도 안 보임"이
        // 되므로 한 칸으로 그리고, 라벨은 다른 막대와 같은 서식을 쓴다(`170.0`이 아니라 `170~170`).
        val s = snapshot(listOf(numberField("""{"stats":{"analyses":[{"type":"numeric"}]}}""")),
            listOf("170", "170", "170"))
        val summary = provider.computeFieldAnalysis(s).numberFieldSummaries.first { it.fieldName == "키" }

        assertEquals(1, summary.histogram.size)
        val label = summary.histogram.keys.first()
        assertEquals(NumericBinning.label(170f, 170f), label)
        assertEquals(3, summary.histogram.getValue(label))
        assertEquals(3, provider.getCharactersByFieldValue(s, listOf(10L), summary.matchSpecs.getValue(label))!!.size)
    }

    @Test
    fun `값이 전부 같고 정수가 아니면 라벨이 그 값을 그대로 말한다`() {
        // 자릿수를 0으로 고정하면 `170.5`가 **`170~170`**이 된다 — 라벨이 실제 값을 잘못
        // 말하는 것이고, 막대가 하나뿐이라 옆과 견줘 알아챌 수도 없다.
        val s = snapshot(listOf(numberField("""{"stats":{"analyses":[{"type":"numeric"}]}}""")),
            listOf("170.5", "170.5"))
        val summary = provider.computeFieldAnalysis(s).numberFieldSummaries.first { it.fieldName == "키" }

        val label = summary.histogram.keys.single()
        assertEquals("170.5~170.5", label)
        assertEquals(2, provider.getCharactersByFieldValue(s, listOf(10L), summary.matchSpecs.getValue(label))!!.size)
    }

    @Test
    fun `값이 전부 같으면 구간 분포는 만들지 않는다`() {
        // 한 칸짜리 파이는 "100%"만 말하고 아무 정보가 없다 — 수치 요약이 그 자리를 대신한다.
        val s = snapshot(listOf(numberField()), listOf("170", "170", "170"))
        assertTrue(
            provider.computeFieldAnalysis(s).fieldValueDistributions.none { it.fieldName == "키" }
        )
        assertNotNull(
            "요약은 그래도 나온다",
            provider.computeFieldAnalysis(s).numberFieldSummaries.firstOrNull { it.fieldName == "키" }
        )
    }

    // ===== 축을 넘지 않는다 =====

    @Test
    fun `사건 필드 히스토그램의 드릴다운은 사건을 돌려준다`() {
        // 히스토그램에 드릴다운이 붙은 것은 이 판이 처음이라, **사건·작품 축에서도 성립하는지**
        // 한 번은 재야 한다. 축을 안 보면 사건 카드가 캐릭터 조회로 흘러 0명짜리 빈 시트가
        // 뜨는데(S-9), 그 실패는 화면에서 "그 구간에 아무도 없음"과 구분되지 않는다.
        val eventField = FieldDefinition(
            id = 20, universeId = uniA, key = "toll", name = "사상자", type = "NUMBER",
            config = """{"stats":{"analyses":[{"type":"numeric"}]}}""",
            entityType = FieldDefinition.ENTITY_EVENT
        )
        val events = (1L..5L).map {
            TimelineEvent(id = it, year = 1000 + it.toInt(), description = "e$it", universeId = uniA)
        }
        val s = StatsSnapshot(
            characters = emptyList(),
            novels = listOf(Novel(id = 1, title = "A작품", universeId = uniA)),
            universes = listOf(Universe(id = uniA, name = "A")),
            events = events, relationships = emptyList(), relationshipChanges = emptyList(),
            tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
            fieldDefinitions = emptyList(), fieldValues = emptyList(), crossRefs = emptyList(),
            eventFieldDefinitions = listOf(eventField),
            eventFieldValues = listOf("150", "160", "170", "180", "200").mapIndexed { i, v ->
                EventFieldValue(eventId = i + 1L, fieldDefinitionId = 20, value = v)
            }
        )

        val summary = provider.computeFieldInsights(s)
            .first { it.fieldDefinition.key == "toll" }
            .analysisResults.firstNotNullOf { it.numericSummary }
        assertEquals(NumericBinning.DEFAULT_BIN_COUNT, summary.histogram.size)

        var listedTotal = 0
        for ((label, count) in summary.histogram) {
            val spec = summary.matchSpecs.getValue(label)
            val found = provider.getEventsByFieldValue(s, listOf(20L), spec)!!
            assertEquals("[$label] 막대 높이와 사건 수가 같아야 한다", count, found.size)
            listedTotal += found.size
        }
        assertEquals(5, listedTotal)

        // 반대편: 같은 스펙을 캐릭터 축으로 물으면 **길이 없다** — 캐릭터 필드가 아니므로
        // null이고, 빈 목록이 아니다(빈 목록이면 '0명'으로 읽혀 조용한 실패가 된다).
        val spec = summary.matchSpecs.values.first()
        assertNull(provider.getCharactersByFieldValue(s, listOf(20L), spec))
    }

    // ===== BODY_SIZE 파트 회귀 =====

    @Test
    fun `BODY_SIZE 파트 요약도 그 파트의 수치로만 막대를 만든다`() {
        val s = snapshot(
            listOf(FieldDefinition(
                id = 10, universeId = uniA, key = "size", name = "신체", type = "BODY_SIZE",
                config = "{}", entityType = FieldDefinition.ENTITY_CHARACTER
            )),
            listOf("150-60-80", "160-60-80", "170-60-80", "180-60-80", "200-60-80")
        )
        val summaries = provider.computeFieldAnalysis(s).numberFieldSummaries
        val part0 = summaries.first { it.fieldName.endsWith("칸1") }
        val part1 = summaries.first { it.fieldName.endsWith("칸2") }

        // 첫 파트는 150~200으로 퍼지고, 둘째 파트는 전부 60이라 한 칸이다.
        assertEquals(NumericBinning.DEFAULT_BIN_COUNT, part0.histogram.size)
        assertEquals(1, part1.histogram.size)

        for ((label, count) in part0.histogram) {
            val listed = provider.getCharactersByFieldValue(s, listOf(10L), part0.matchSpecs.getValue(label))!!
            assertEquals("[$label] 파트 막대의 인원이 높이와 같아야 한다", count, listed.size)
        }
    }
}
