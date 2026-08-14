package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.PatternType
import com.novelcharacter.app.ui.stats.PatternThresholds
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot
import com.novelcharacter.app.util.NumericBinning
import com.novelcharacter.app.util.ValueDistributions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 접힌 집계(S6 4차 — 통계 계수가 건별 재료화 대신 **원문 × 건수** 위에서 도는 것)의
 * 동작 무변경 대조. [StatsKeysParityTest]가 파싱 한 번의 답을 잠그고, 이 시험은 그 위의
 * **세는 일**을 잠근다 — 접기 도입 전의 건별 파이프라인(값마다 토큰을 재료화해 세고 정렬)을
 * 하네스가 그대로 들고 결과를 대조한다([StatsMemoParityTest] 1번 규약).
 *
 * 잠그는 것 다섯:
 * 1. **옛 건별 파이프라인과 같은 답** — 요약 TOP5 · 이산 분포 · 사용자 구간 계수 · 수치
 *    자동구간/요약 · 필드별 완성도 · 패턴 편중 · 작품별 편중. 접기 가중(같은 원문 여러 건)과
 *    복수 토큰·동수(tie)·그룹 파싱(R-15)이 전부 실린 스냅샷으로 대조한다.
 * 2. **순서까지 같다** — 분포는 LinkedHashMap이라 entries 순서가 화면 순서다. 값 대조만 하면
 *    "합은 같은데 다른 것부터 그리는" 퇴화를 못 잡는다.
 * 3. **수치 파싱 한 벌의 전제** — 자동구간 분포와 수치 요약이 파싱 한 벌을 나눠 쓰는 것은
 *    augmented 값 표에 블랭크가 없어서다(블랭크 필터와 파싱 탈락이 동치). 그 전제를 직접 잠근다.
 * 4. **공유 사본 계약** — 모든 소비처를 돌린 뒤 [valueCountsOf] 캐시 내용 = 새로 지은 것
 *    (원문 첫 등장 순서 포함 — 사용자 구간의 잔여 키 순서가 이 순서에 기대므로 순서도 계약이다).
 * 5. **격리·상한·동시성** — 스냅샷 간 비누출(값 대조), 캐시 상한, 동시 8스레드 = 콜드 기준.
 */
class StatsFoldParityTest {

    private val uniA = 1L
    private val uniB = 2L

    private fun def(
        id: Long, key: String, name: String, universeId: Long? = uniA,
        type: String = "TEXT", config: String = "{}"
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type, config = config,
        entityType = FieldDefinition.ENTITY_CHARACTER
    )

    /**
     * 집계의 모든 갈래에 접기 가중이 실리는 스냅샷:
     * - 10 성별(SELECT): 해석기 + category — "남"이 **3건**(접기 가중 3)
     * - 11 특성(MULTI_TEXT): "용감, 신중"이 **2건**(가중 2 × 토큰 2) + "용감" 1건 — 동수(tie) 유발
     * - 12 힘(NUMBER, 사용자 구간): "10"이 2건 + 구간 밖 "-5"
     * - 13 힘x2(CALCULATED): augmented 합성 + 자동 구간·수치 요약
     * - 14 나이(NUMBER, 자동 구간): 비수치 "abc" 포함 — 파싱 탈락 경로
     * - 20/21 별칭(TEXT): comma_list 기준 def + 무포맷 형제 — R-15 그룹 파싱, 두 작품에 걸침
     * - 30 기질(TEXT): 폴백 라벨/카테고리 — "불" 1건·"물" 1건(동수)
     * - 90 전역(TEXT, universeId=null): 무소속 세계관 완성도 모수 경로
     * - 캐릭터 7(작품3=무소속 세계관) · 8(novelId=99, 없는 작품) · 9(novelId=null) — 완성도 모수 갈래
     */
    private fun richSnapshot(salt: String = ""): StatsSnapshot {
        val defs = listOf(
            def(10, "gender", "성별", type = "SELECT",
                config = """{"stats":{"statsGroupBy":"category"}}"""),
            def(11, "traits", "특성", type = "MULTI_TEXT"),
            def(12, "power", "힘", type = "NUMBER",
                config = """{"stats":{"binning":{"mode":"custom","ranges":["0~15:저","15~30:중","30~:고"]}}}"""),
            def(13, "power2", "힘x2", type = "CALCULATED",
                config = """{"formula":"field('power') * 2"}"""),
            def(14, "age", "나이", type = "NUMBER"),
            def(20, "alias", "별칭", universeId = uniA, config = """{"displayFormat":"comma_list"}"""),
            def(21, "alias", "별칭", universeId = uniB),
            def(30, "temper", "기질",
                config = """{"stats":{"statsGroupBy":"both","valueLabels":{"불$salt":"화염$salt"},"valueCategories":{"화염$salt":"원소"}}}"""),
            def(90, "global", "전역메모", universeId = null)
        )
        val chars = listOf(
            Character(id = 1, name = "c1", novelId = 1L, updatedAt = 1_700_000_000_000L),
            Character(id = 2, name = "c2", novelId = 1L, updatedAt = 1_700_000_000_000L),
            Character(id = 3, name = "c3", novelId = 1L, updatedAt = 1_700_000_000_000L),
            Character(id = 4, name = "c4", novelId = 1L, updatedAt = 1_700_000_000_000L),
            Character(id = 5, name = "c5", novelId = 2L, updatedAt = 1_700_000_000_000L),
            Character(id = 6, name = "c6", novelId = 2L, updatedAt = 1_700_000_000_000L),
            Character(id = 7, name = "c7", novelId = 3L, updatedAt = 1_700_000_000_000L),
            Character(id = 8, name = "c8", novelId = 99L, updatedAt = 1_700_000_000_000L),
            Character(id = 9, name = "c9", novelId = null, updatedAt = 1_700_000_000_000L)
        )
        val values = listOf(
            CharacterFieldValue(id = 1, characterId = 1, fieldDefinitionId = 10, value = "남$salt"),
            CharacterFieldValue(id = 2, characterId = 2, fieldDefinitionId = 10, value = "여$salt"),
            CharacterFieldValue(id = 3, characterId = 3, fieldDefinitionId = 10, value = "남$salt"),
            CharacterFieldValue(id = 4, characterId = 4, fieldDefinitionId = 10, value = "남$salt"),
            CharacterFieldValue(id = 5, characterId = 1, fieldDefinitionId = 11, value = "용감, 신중"),
            CharacterFieldValue(id = 6, characterId = 2, fieldDefinitionId = 11, value = "용감, 신중"),
            CharacterFieldValue(id = 7, characterId = 3, fieldDefinitionId = 11, value = "용감"),
            CharacterFieldValue(id = 8, characterId = 1, fieldDefinitionId = 12, value = "10"),
            CharacterFieldValue(id = 9, characterId = 2, fieldDefinitionId = 12, value = "10"),
            CharacterFieldValue(id = 10, characterId = 3, fieldDefinitionId = 12, value = "20"),
            CharacterFieldValue(id = 11, characterId = 4, fieldDefinitionId = 12, value = "-5"),
            CharacterFieldValue(id = 12, characterId = 1, fieldDefinitionId = 14, value = "17"),
            CharacterFieldValue(id = 13, characterId = 2, fieldDefinitionId = 14, value = "23"),
            CharacterFieldValue(id = 14, characterId = 3, fieldDefinitionId = 14, value = "abc"),
            CharacterFieldValue(id = 15, characterId = 1, fieldDefinitionId = 20, value = "그림자, 밤"),
            CharacterFieldValue(id = 16, characterId = 2, fieldDefinitionId = 20, value = "그림자, 밤"),
            CharacterFieldValue(id = 17, characterId = 5, fieldDefinitionId = 21, value = "그림자, 밤"),
            CharacterFieldValue(id = 18, characterId = 6, fieldDefinitionId = 21, value = "새벽"),
            CharacterFieldValue(id = 19, characterId = 2, fieldDefinitionId = 30, value = "불$salt"),
            CharacterFieldValue(id = 20, characterId = 3, fieldDefinitionId = 30, value = "물"),
            CharacterFieldValue(id = 21, characterId = 7, fieldDefinitionId = 90, value = "메모A")
        )
        val entries = listOf(
            FieldValueEntry(id = 1, fieldDefinitionId = 10, value = "남$salt", displayLabel = "남성$salt",
                aliasesJson = "[]", category = "인간", isHidden = false,
                source = FieldValueEntry.SOURCE_AUTO, usageCount = 3),
            FieldValueEntry(id = 2, fieldDefinitionId = 10, value = "여$salt", displayLabel = "여성$salt",
                aliasesJson = "[]", category = "인간", isHidden = false,
                source = FieldValueEntry.SOURCE_AUTO, usageCount = 1)
        )
        return StatsSnapshot(
            characters = chars,
            novels = listOf(
                Novel(id = 1, title = "A작품", universeId = uniA),
                Novel(id = 2, title = "B작품", universeId = uniB),
                Novel(id = 3, title = "무소속작품", universeId = null)
            ),
            universes = listOf(Universe(id = uniA, name = "A"), Universe(id = uniB, name = "B")),
            events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
            tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
            fieldDefinitions = defs,
            fieldValues = values,
            crossRefs = emptyList(),
            valueEntries = entries
        )
    }

    // ── 리플렉션 헬퍼 — private 파싱·조립 자리를 직접 본다 ([StatsKeysParityTest] 규약) ──

    private val gfv = StatsDataProvider::class.java.getDeclaredMethod(
        "getFieldValues", StatsSnapshot::class.java, FieldDefinition::class.java,
        String::class.java, FieldStatsConfig::class.java
    ).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun keysOf(p: StatsDataProvider, s: StatsSnapshot, fd: FieldDefinition,
                       raw: String, cfg: FieldStatsConfig): List<String> =
        gfv.invoke(p, s, fd, raw, cfg) as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun canonicalConfigs(p: StatsDataProvider, s: StatsSnapshot): Map<Long, FieldStatsConfig> {
        val m = StatsDataProvider::class.java
            .getDeclaredMethod("statsConfigsOf", StatsSnapshot::class.java)
        m.isAccessible = true
        return m.invoke(p, s) as Map<Long, FieldStatsConfig>
    }

    @Suppress("UNCHECKED_CAST")
    private fun augOf(p: StatsDataProvider, s: StatsSnapshot): Map<Long, List<CharacterFieldValue>> {
        val m = StatsDataProvider::class.java
            .getDeclaredMethod("augmentedCharacterValues", StatsSnapshot::class.java)
        m.isAccessible = true
        return m.invoke(p, s) as Map<Long, List<CharacterFieldValue>>
    }

    private fun analyzable(p: StatsDataProvider, s: StatsSnapshot): List<FieldDefinition> {
        val cfgs = canonicalConfigs(p, s)
        return s.fieldDefinitions.filter { cfgs.getValue(it.id).enabled }
    }

    // ===== 1. 옛 건별 파이프라인 대조 — 요약 TOP5 =====

    /** 접기 도입 전 TOP5 — 값마다 (필드명, 토큰) 쌍을 재료화해 세고 정렬하던 그 모양. */
    private fun legacyTop5(p: StatsDataProvider, s: StatsSnapshot): List<Triple<String, String, Int>> {
        val cfgs = canonicalConfigs(p, s)
        val defById = s.fieldDefinitions.associateBy { it.id }
        return augOf(p, s)
            .flatMap { (fieldDefId, values) ->
                val fd = defById[fieldDefId] ?: return@flatMap emptyList<Pair<String, String>>()
                val cfg = cfgs[fieldDefId] ?: return@flatMap emptyList<Pair<String, String>>()
                if (!cfg.enabled) return@flatMap emptyList<Pair<String, String>>()
                values.filter { it.value.isNotBlank() }
                    .flatMap { fv -> keysOf(p, s, fd, fv.value, cfg).map { Pair(fd.name, it) } }
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                    .thenBy { it.key.first }
                    .thenBy { it.key.second }
            )
            .take(5)
            .map { Triple(it.key.first, it.key.second, it.value) }
    }

    @Test
    fun `요약 TOP5가 접기 도입 전 건별 파이프라인과 같다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val expected = legacyTop5(StatsDataProvider(), s)
        assertEquals(expected, p.computeSummary(s).topFieldValues)
        // 접기 가중이 실제로 실렸는지 — "남"은 3건이 접혀 카테고리 "인간"으로 센다
        assertTrue(expected.any { it.third >= 3 })
    }

    // ===== 2. 옛 파이프라인 대조 — 이산 분포 (entries 순서까지) =====

    @Test
    fun `이산 분포가 접기 도입 전 건별 파이프라인과 순서까지 같다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val legacyP = StatsDataProvider()
        val cfgs = canonicalConfigs(legacyP, s)
        val aug = augOf(legacyP, s)

        val fa = p.computeFieldAnalysis(s)
        var compared = 0
        for (fd in analyzable(legacyP, s)) {
            val discrete = fd.fieldType == FieldType.SELECT || fd.fieldType == FieldType.GRADE ||
                fd.fieldType == FieldType.MULTI_TEXT || fd.fieldType == FieldType.TEXT
            if (!discrete) continue
            val values = aug[fd.id] ?: continue
            val legacyDist = ValueDistributions.of(
                values.filter { it.value.isNotBlank() }
                    .flatMap { fv -> keysOf(legacyP, s, fd, fv.value, cfgs.getValue(fd.id)) }
            )
            if (legacyDist.isEmpty()) continue
            val actual = fa.fieldValueDistributions.first { it.fieldDefId == fd.id }
            // 값 대조가 아니라 **순서 대조** — LinkedHashMap의 entries 순서가 화면 순서다
            assertEquals("def=${fd.key}", legacyDist.toList(), actual.distribution.toList())
            assertEquals(legacyDist.keys, actual.matchSpecs.keys)
            compared++
        }
        assertTrue("이산 분포 대조가 헛돌았다", compared >= 4)
    }

    // ===== 3. 옛 파이프라인 대조 — 사용자 구간 계수 =====

    @Test
    fun `사용자 구간 분포가 접기 도입 전 계수와 순서까지 같다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val legacyP = StatsDataProvider()
        val cfgs = canonicalConfigs(legacyP, s)
        val fd = s.fieldDefinitions.first { it.id == 12L }
        val cfg = cfgs.getValue(12L)

        // 접기 도입 전: 값마다 키를 재료화해 groupingBy로 세고, 구간 정의 순서로 조립
        val keys = augOf(legacyP, s).getValue(12L)
            .filter { it.value.isNotBlank() }
            .flatMap { fv -> keysOf(legacyP, s, fd, fv.value, cfg) }
        val counted = keys.groupingBy { it }.eachCount()
        val legacyDist = linkedMapOf<String, Int>()
        for (range in cfg.binning!!.parseRanges()) {
            legacyDist[range.label] = counted[range.label] ?: 0
        }
        counted[StatsDataProvider.OUT_OF_RANGE_LABEL]?.let {
            legacyDist[StatsDataProvider.OUT_OF_RANGE_LABEL] = it
        }
        for ((k, v) in counted) if (k !in legacyDist) legacyDist[k] = v

        val actual = p.computeFieldAnalysis(s).fieldValueDistributions.first { it.fieldDefId == 12L }
        assertEquals(legacyDist.toList(), actual.distribution.toList())
        // 구간 밖(R-17)과 접기 가중("10" 2건)이 실제로 실려 있는지
        assertEquals(2, actual.distribution["저"])
        assertEquals(1, actual.distribution[StatsDataProvider.OUT_OF_RANGE_LABEL])
    }

    // ===== 4. 수치 파싱 한 벌 — 전제와 결과를 함께 잠근다 =====

    @Test
    fun `augmented 값 표에 블랭크가 없다 - 수치 파싱 한 벌의 전제`() {
        // 자동구간 분포(종전: 블랭크 필터 후 파싱)와 수치 요약(종전: 필터 없이 파싱)이 파싱
        // 한 벌을 나눠 쓸 수 있는 근거다. augmented 조립이 블랭크를 걸러 싣지 않게 되면
        // 이 전제가 깨진다 — 그때는 이 시험이 먼저 갈린다.
        val s = richSnapshot()
        val p = StatsDataProvider()
        for ((defId, values) in augOf(p, s)) {
            assertFalse("def=$defId 에 블랭크 값이 실렸다", values.any { it.value.isBlank() })
        }
    }

    @Test
    fun `수치 자동구간 분포와 요약이 접기 도입 전 파이프라인과 같다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val legacyP = StatsDataProvider()
        val aug = augOf(legacyP, s)
        val fa = p.computeFieldAnalysis(s)

        var compared = 0
        for (fd in analyzable(legacyP, s)) {
            if (fd.fieldType != FieldType.NUMBER && fd.fieldType != FieldType.CALCULATED) continue
            val values = aug[fd.id] ?: continue
            // 접기 도입 전 분포: 블랭크 필터 → 파싱 → 자동 구간
            val legacyNums = NumericBinning.numericValuesOf(
                values.filter { it.value.isNotBlank() }.map { it.value }, "", 0
            )
            val legacyBins = NumericBinning.autoDistribution(legacyNums)
            val actualDist = fa.fieldValueDistributions.firstOrNull { it.fieldDefId == fd.id }
            if (canonicalConfigs(legacyP, s).getValue(fd.id).binning?.mode != "custom") {
                if (legacyBins.isEmpty()) {
                    assertTrue(actualDist == null || actualDist.distribution.isEmpty())
                } else {
                    assertEquals("def=${fd.key}",
                        legacyBins.map { it.bin.label to it.count }, actualDist!!.distribution.toList())
                }
            }
            // 접기 도입 전 요약: 필터 없이 파싱 — 같은 목록이어야 한다(위 전제 시험이 근거)
            val summaryNums = NumericBinning.numericValuesOf(values.map { it.value }, "", 0)
            assertEquals("def=${fd.key} — 분포와 요약의 파싱이 갈렸다", legacyNums, summaryNums)
            val actualSummary = fa.numberFieldSummaries.firstOrNull { it.fieldDefId == fd.id }
            if (summaryNums.isEmpty()) {
                assertEquals(null, actualSummary)
            } else {
                assertEquals("def=${fd.key}", summaryNums.size, actualSummary!!.count)
                assertEquals(summaryNums.min(), actualSummary.min)
                assertEquals(summaryNums.max(), actualSummary.max)
            }
            compared++
        }
        // NUMBER 둘(12·14) + CALCULATED 하나(13) — 비수치 "abc" 탈락 경로 포함
        assertTrue("수치 대조가 헛돌았다", compared >= 3)
    }

    // ===== 5. 필드별 완성도 — def×캐릭터 필터와 같은 답 =====

    /** 접기 도입 전 — def마다 작품·캐릭터 전수를 필터하던 그 모양. */
    private fun legacyCompletion(
        p: StatsDataProvider, s: StatsSnapshot
    ): List<Triple<String, Pair<Int, Int>, Float>> {
        val aug = augOf(p, s)
        return s.fieldDefinitions
            .filter { it.fieldType != FieldType.CALCULATED }
            .map { fd ->
                val universeNovels = s.novels.filter { it.universeId == fd.universeId }
                    .map { it.id }.toSet()
                val relevantChars = if (s.unassignedScope) s.characters
                    else s.characters.filter { it.novelId in universeNovels }
                val filled = aug[fd.id]?.count { it.value.isNotBlank() } ?: 0
                val total = relevantChars.size
                val rate = if (total > 0) filled.toFloat() / total * 100f else 0f
                Triple(fd.name, filled to total, rate)
            }.sortedBy { it.third }
    }

    @Test
    fun `필드별 완성도가 def×캐릭터 필터의 답과 같다`() {
        val s = richSnapshot()
        val expected = legacyCompletion(StatsDataProvider(), s)
        val actual = StatsDataProvider().computeFieldAnalysis(s).fieldCompletionByField
            .map { Triple(it.fieldName, it.filledCount to it.totalCount, it.completionRate) }
        assertEquals(expected, actual)
        // 갈래가 실제로 실렸는지 — 무소속 세계관 def의 모수는 무소속 작품 캐릭터 1
        val global = StatsDataProvider().computeFieldAnalysis(s).fieldCompletionByField
            .first { it.fieldName == "전역메모" }
        assertEquals(1, global.filledCount)
        assertEquals(1, global.totalCount)
    }

    @Test
    fun `미배정 스코프에서도 필드별 완성도가 종전 모수와 같다`() {
        val s = richSnapshot().copy(unassignedScope = true)
        val expected = legacyCompletion(StatsDataProvider(), s)
        val actual = StatsDataProvider().computeFieldAnalysis(s).fieldCompletionByField
            .map { Triple(it.fieldName, it.filledCount to it.totalCount, it.completionRate) }
        assertEquals(expected, actual)
        // 미배정 모수 = 스냅샷 캐릭터 전체(9) — novelId가 없는 캐릭터도 든다
        assertTrue(actual.all { it.second.second == s.characters.size })
    }

    // ===== 6. 패턴 편중 — R-15 그룹 병합 + 접기 가중 =====

    @Test
    fun `패턴 편중이 접기 도입 전 집계 산술과 같다`() {
        val s = richSnapshot()
        val legacyP = StatsDataProvider()
        val cfgs = canonicalConfigs(legacyP, s)
        val aug = augOf(legacyP, s)

        // 접기 도입 전: 별칭 그룹(20+21)의 값 전부를 기준 def(20)의 규칙으로 건별 파싱해 센다
        val primary = s.fieldDefinitions.first { it.id == 20L }
        val merged = aug.getValue(20L) + aug.getValue(21L)
        val allValues = merged.flatMap { keysOf(legacyP, s, primary, it.value, cfgs.getValue(20L)) }
        val legacyDist = ValueDistributions.of(allValues)
        val legacyTotal = allValues.size
        val legacyTop = legacyDist.entries.first()
        val legacyPct = legacyTop.value * 100f / legacyTotal
        val ownerCount = merged.mapTo(HashSet()) { it.characterId }.size

        // 편중 문턱을 낮춰 카드가 반드시 뜨게 한다 — 대조 대상은 문턱이 아니라 산술이다
        val card = StatsDataProvider().detectPatterns(
            s, setOf(PatternType.DOMINANCE), PatternThresholds(dominancePercent = 10f)
        ).first { it.fieldDefId == 20L }
        assertEquals(listOf(legacyTop.key), card.drilldownValues)
        assertEquals(ownerCount, card.population)
        assertTrue("pct가 옛 산술과 다르다: ${card.description}",
            card.description.contains("${String.format("%.0f", legacyPct)}%"))
        // 그룹 파싱(R-15)이 실제로 실렸는지 — 형제(21)의 "그림자, 밤"도 콤마로 접혀 "그림자"가 3
        assertEquals(3, legacyDist["그림자"])
    }

    // ===== 7. 작품별 편중 — 소유 def 파싱 + 접힌 표 =====

    @Test
    fun `작품별 편중 경향이 접기 도입 전 집계 산술과 같다`() {
        val s = richSnapshot()
        val legacyP = StatsDataProvider()
        val cfgs = canonicalConfigs(legacyP, s)
        val aug = augOf(legacyP, s)

        // 접기 도입 전: def마다 **자기 config**로 건별 파싱해 주요값(과반)을 찾는다
        val expected = listOf(20L, 21L).mapNotNull { defId ->
            val fd = s.fieldDefinitions.first { it.id == defId }
            val values = aug.getValue(defId).flatMap { keysOf(legacyP, s, fd, it.value, cfgs.getValue(defId)) }
            val top = ValueDistributions.of(values).entries.firstOrNull() ?: return@mapNotNull null
            val pct = top.value * 100f / values.size
            if (pct >= 50f) "${top.key}(${String.format("%.0f", pct)}%)" else null
        }
        assertEquals("fixture가 과반 주요값 두 개를 만들지 못했다", 2, expected.size)

        val card = StatsDataProvider().detectPatterns(s, setOf(PatternType.CROSS_NOVEL))
            .first { it.title.contains("작품별 편중 경향") }
        for (majority in expected) {
            assertTrue("주요값이 옛 산술과 다르다: ${card.description}", card.description.contains(majority))
        }
    }

    // ===== 8. 공유 사본 계약 — 접힌 표의 내용·순서가 소비처를 다 돌린 뒤에도 그대로다 =====

    @Test
    fun `모든 소비처를 돌린 뒤에도 접힌 값 표가 새로 지은 것과 순서까지 같다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        p.computeSummary(s); p.computeFieldInsights(s); p.computeFieldAnalysis(s); p.detectPatterns(s)

        val f = StatsDataProvider::class.java.getDeclaredField("valueCountsCache")
        f.isAccessible = true
        val store = f.get(p) as java.util.concurrent.ConcurrentHashMap<*, *>
        assertTrue("접힌 표 캐시가 비어 있다 — 대조가 헛돌았다", store.isNotEmpty())

        // 새로 짓는다 — augmented 값 표를 def별 (원문 → 건수)로, 첫 등장 순서 그대로
        val fresh = HashMap<Long, LinkedHashMap<String, Int>>()
        for ((defId, values) in augOf(p, s)) {
            val m = fresh.getOrPut(defId) { LinkedHashMap() }
            for (fv in values) {
                if (fv.value.isBlank()) continue
                m.merge(fv.value, 1) { a, b -> a + b }
            }
        }
        for (stored in store.values) {
            @Suppress("UNCHECKED_CAST")
            val byDef = stored as Map<Long, Map<String, Int>>
            assertEquals(fresh.keys, byDef.keys)
            for ((defId, freshCounts) in fresh) {
                // 값만이 아니라 **순서까지** — 사용자 구간의 잔여 키 순서가 이 순서에 기댄다
                assertEquals("def=$defId", freshCounts.toList(), byDef.getValue(defId).toList())
            }
        }
    }

    // ===== 9. 격리 · 상한 · 동시성 =====

    @Test
    fun `다른 스냅샷을 지나도 접힌 표의 답이 바뀌지 않는다`() {
        val p = StatsDataProvider()
        val a = richSnapshot()
        val b = richSnapshot(salt = "B")
        val before = p.computeSummary(a).topFieldValues
        p.computeSummary(b); p.computeFieldAnalysis(b)
        assertEquals(before, p.computeSummary(a).topFieldValues)
        assertEquals(StatsDataProvider().computeSummary(a).topFieldValues, before)
    }

    @Test
    fun `서로 다른 스냅샷을 계속 넣어도 접힌 표 캐시가 상한 안에 머문다`() {
        val p = StatsDataProvider()
        repeat(7) { i -> p.computeSummary(richSnapshot(salt = "s$i")) }
        val f = StatsDataProvider::class.java.getDeclaredField("valueCountsCache")
        f.isAccessible = true
        val size = (f.get(p) as java.util.concurrent.ConcurrentHashMap<*, *>).size
        // MAX_CACHED_SNAPSHOTS(4)를 넘으면 비우고 새로 담으므로 5를 넘을 수 없다
        assertTrue("캐시가 $size 칸 — 상한 규칙이 죽었다", size <= 5)
    }

    @Test
    fun `동시 8스레드의 접힌 집계가 전부 콜드 기준과 같다`() {
        val s = richSnapshot()
        val reference = listOf<Any>(
            StatsDataProvider().computeSummary(s),
            StatsDataProvider().computeFieldAnalysis(s),
            StatsDataProvider().detectPatterns(s)
        )
        val p = StatsDataProvider()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val results = java.util.concurrent.ConcurrentHashMap<Int, List<Any>>()
        val threads = (0 until 8).map { i ->
            Thread {
                try {
                    // 접힌 표의 computeIfAbsent 경합과 소비처 셋의 동시 접기를 함께 시험한다
                    results[i] = listOf(
                        p.computeSummary(s), p.computeFieldAnalysis(s), p.detectPatterns(s)
                    )
                } catch (t: Throwable) { errors.add(t) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }
        assertTrue(errors.isEmpty())
        assertEquals(8, results.size)
        results.values.forEach { assertEquals(reference, it) }
    }
}
