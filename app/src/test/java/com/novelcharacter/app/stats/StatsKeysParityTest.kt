package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot
import com.novelcharacter.app.util.FieldValueResolver
import com.novelcharacter.app.util.FieldValueTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 통계 키 메모([StatsDataProvider.getFieldValues]의 스냅샷 단위 (파싱 def, 원문) 캐시 — B-215)의
 * 동작 무변경 대조. [StatsMemoParityTest]가 헬퍼 5종의 메모를 잠그는 그 규약을 값 파싱에 잇는다.
 *
 * 잠그는 것 다섯:
 * 1. **옛 파이프라인과 같은 답** — 캐시 도입 전 파싱(토큰화 → 라벨/카테고리 → 구간)을 이
 *    하네스가 그대로 들고 대조한다. 해석기·폴백·구간·복수값 갈래 전부.
 * 2. **정본 밖 config는 메모를 지나지도, 오염시키지도 않는다** — 순위 빈도 모드가 statsGroupBy를
 *    "value"로 덮은 사본을 쓰는데(R-13), 그 결과가 정본 메모에 섞이면 분포와 순위가 같은
 *    필드에 다른 수를 센다(R-16). 이 갈림이 이 캐시 설계의 요점이다(착수 조건 ⓑ).
 * 3. **그룹 파싱(R-15)의 보존** — 인사이트·패턴은 형제 def의 값도 기준 def의 규칙으로 파싱한다.
 *    메모 키가 (파싱 def, 원문)이라 같은 원문이 소유 def와 기준 def에서 **각자** 접히는지 본다.
 * 4. **공개 소비처의 콜드=웜** — [StatsMemoParityTest]의 10계산 밖에 있는 소비처
 *    (교차분석 · 순위 · 드릴다운)도 캐시 유무와 무관하게 같은 답을 낸다.
 * 5. **공유 사본·격리·상한** — 전 소비처를 돌린 뒤 메모 내용 = 새로 계산한 것, 스냅샷 간
 *    비누출, 캐시 상한.
 */
class StatsKeysParityTest {

    private val uniA = 1L
    private val uniB = 2L

    private fun def(
        id: Long, key: String, name: String, universeId: Long = uniA,
        type: String = "TEXT", config: String = "{}"
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type, config = config,
        entityType = FieldDefinition.ENTITY_CHARACTER
    )

    /**
     * 파싱의 모든 갈래가 실리는 스냅샷:
     * - 10 성별(SELECT): 라이브러리 해석기 + statsGroupBy="category" — 해석기 경로
     * - 11 특성(MULTI_TEXT): 콤마 분리 — 다값 토큰화
     * - 12 힘(NUMBER): 사용자 구간(custom binning) + 구간 밖 값 — 구간 경로
     * - 13 힘x2(CALCULATED): 수식 — augmented 합성 경로
     * - 20 별칭(TEXT, uniA): displayFormat=comma_list — **그룹 기준 def**
     * - 21 별칭(TEXT, uniB): 같은 (key, type)의 형제, 포맷 없음 — R-15 대조용
     * - 30 기질(TEXT): 해석기 없음 + valueLabels/valueCategories + statsGroupBy="both" — 폴백 경로
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
            def(20, "alias", "별칭", universeId = uniA, config = """{"displayFormat":"comma_list"}"""),
            def(21, "alias", "별칭", universeId = uniB),
            def(30, "temper", "기질",
                config = """{"stats":{"statsGroupBy":"both","valueLabels":{"불$salt":"화염$salt"},"valueCategories":{"화염$salt":"원소"}}}""")
        )
        val chars = (1L..6L).map {
            Character(id = it, name = "c$it", novelId = if (it <= 4L) 1L else 2L,
                updatedAt = 1_700_000_000_000L)
        }
        val values = listOf(
            CharacterFieldValue(id = 1, characterId = 1, fieldDefinitionId = 10, value = "남$salt"),
            CharacterFieldValue(id = 2, characterId = 2, fieldDefinitionId = 10, value = "여$salt"),
            CharacterFieldValue(id = 3, characterId = 3, fieldDefinitionId = 10, value = "남$salt"),
            CharacterFieldValue(id = 4, characterId = 1, fieldDefinitionId = 11, value = "용감, 신중"),
            CharacterFieldValue(id = 5, characterId = 2, fieldDefinitionId = 11, value = "용감"),
            CharacterFieldValue(id = 6, characterId = 1, fieldDefinitionId = 12, value = "10"),
            CharacterFieldValue(id = 7, characterId = 2, fieldDefinitionId = 12, value = "20"),
            CharacterFieldValue(id = 8, characterId = 3, fieldDefinitionId = 12, value = "-5"),
            CharacterFieldValue(id = 9, characterId = 1, fieldDefinitionId = 20, value = "그림자, 밤"),
            CharacterFieldValue(id = 10, characterId = 5, fieldDefinitionId = 21, value = "그림자, 밤"),
            CharacterFieldValue(id = 11, characterId = 2, fieldDefinitionId = 30, value = "불$salt"),
            CharacterFieldValue(id = 12, characterId = 3, fieldDefinitionId = 30, value = "물")
        )
        val entries = listOf(
            FieldValueEntry(id = 1, fieldDefinitionId = 10, value = "남$salt", displayLabel = "남성$salt",
                aliasesJson = "[]", category = "인간", isHidden = false,
                source = FieldValueEntry.SOURCE_AUTO, usageCount = 2),
            FieldValueEntry(id = 2, fieldDefinitionId = 10, value = "여$salt", displayLabel = "여성$salt",
                aliasesJson = "[]", category = "인간", isHidden = false,
                source = FieldValueEntry.SOURCE_AUTO, usageCount = 1)
        )
        return StatsSnapshot(
            characters = chars,
            novels = listOf(
                Novel(id = 1, title = "A작품", universeId = uniA),
                Novel(id = 2, title = "B작품", universeId = uniB)
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

    // ── 리플렉션 헬퍼 — private 파싱 자리를 직접 본다 (StatsMemoParityTest 규약) ──

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

    /**
     * 캐시 도입 **전**의 파싱 파이프라인 — 토큰화 → 해석기/폴백 → 사용자 구간.
     * 걷어낸 구현을 하네스가 그대로 드는 것이 이 시험의 요점이다([StatsMemoParityTest] 1번 규약).
     */
    private fun legacyKeys(
        fd: FieldDefinition, rawValue: String, cfg: FieldStatsConfig,
        resolver: FieldValueResolver?
    ): List<String> {
        val split = FieldValueTokenizer.splitForStats(fd, rawValue)
        val categorized = if (resolver != null && !resolver.isEmpty) {
            split.flatMap { resolver.statsKeys(it, cfg.statsGroupBy) }
        } else {
            val labeled = split.map { cfg.applyLabel(it) }
            if (cfg.valueCategories.isNotEmpty()) labeled.flatMap { cfg.resolveStatsKeys(it) }
            else labeled
        }
        val binnable = fd.fieldType == FieldType.NUMBER || fd.fieldType == FieldType.CALCULATED
        if (binnable && cfg.binning?.mode == "custom") {
            return categorized.map { v ->
                v.toFloatOrNull()?.let { cfg.applyBinning(it) } ?: StatsDataProvider.OUT_OF_RANGE_LABEL
            }
        }
        return categorized
    }

    // ===== 1. 옛 파이프라인 대조 — 모든 갈래 × 모든 값 =====

    @Test
    fun `모든 def의 모든 값이 캐시 도입 전 파이프라인과 같다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val cfgs = canonicalConfigs(p, s)
        val resolvers = s.valueEntries.groupBy { it.fieldDefinitionId }
            .mapValues { (_, e) -> FieldValueResolver(e) }
        val defById = s.fieldDefinitions.associateBy { it.id }
        var compared = 0
        for (fv in s.fieldValues) {
            if (fv.value.isBlank()) continue
            val fd = defById.getValue(fv.fieldDefinitionId)
            val cfg = cfgs.getValue(fd.id)
            assertEquals(
                "def=${fd.key} 값=${fv.value}",
                legacyKeys(fd, fv.value, cfg, resolvers[fd.id]),
                keysOf(p, s, fd, fv.value, cfg)
            )
            compared++
        }
        assertTrue(compared >= 12)
        // 구간 밖 값(R-17)이 실제로 대조에 들어 있는지 — "-5"는 어느 구간에도 안 든다
        assertEquals(listOf(StatsDataProvider.OUT_OF_RANGE_LABEL),
            keysOf(p, s, defById.getValue(12), "-5", cfgs.getValue(12L)))
        // 해석기 경로가 실제로 카테고리를 냈는지 — statsGroupBy="category"
        assertEquals(listOf("인간"), keysOf(p, s, defById.getValue(10), "남", cfgs.getValue(10L)))
        // 폴백 경로(라벨→카테고리, "both")가 실제로 돌았는지
        assertEquals(listOf("화염", "원소"), keysOf(p, s, defById.getValue(30), "불", cfgs.getValue(30L)))
    }

    // ===== 2. 메모이즈 증명 + 정본 밖 config의 우회 =====

    @Test
    fun `정본 config의 같은 (def, 원문) 두 번째 호출은 같은 객체다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val cfgs = canonicalConfigs(p, s)
        val fd = s.fieldDefinitions.first { it.id == 11L }
        val first = keysOf(p, s, fd, "용감, 신중", cfgs.getValue(11L))
        assertSame(first, keysOf(p, s, fd, "용감, 신중", cfgs.getValue(11L)))
    }

    @Test
    fun `정본 밖 config는 메모를 지나지 않고 오염시키지도 않는다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val cfgs = canonicalConfigs(p, s)
        val fd = s.fieldDefinitions.first { it.id == 10L }
        val canonical = cfgs.getValue(10L)

        // 정본: statsGroupBy="category" → 카테고리 키
        val canonicalKeys = keysOf(p, s, fd, "남", canonical)
        assertEquals(listOf("인간"), canonicalKeys)

        // 순위 빈도 모드의 그 사본(R-13) — 값 축이므로 결과가 갈려야 한다
        val override = canonical.copy(statsGroupBy = "value")
        assertEquals(listOf("남성"), keysOf(p, s, fd, "남", override))

        // 사본 호출 뒤에도 정본 결과는 그대로다 — 메모가 오염됐다면 여기서 갈린다
        assertSame(canonicalKeys, keysOf(p, s, fd, "남", canonical))
    }

    // ===== 3. R-15 그룹 파싱 보존 — 같은 원문, 기준 def와 소유 def가 각자 접힌다 =====

    @Test
    fun `같은 원문이 기준 def와 소유 def에서 서로 다른 규칙으로 각자 접힌다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val cfgs = canonicalConfigs(p, s)
        val primary = s.fieldDefinitions.first { it.id == 20L }   // comma_list — 콤마 분리
        val sibling = s.fieldDefinitions.first { it.id == 21L }   // 포맷 없음 — 원문 그대로

        // 인사이트·패턴 경로: 형제(21)의 값도 기준 def(20)의 규칙으로 — 두 토큰
        val viaPrimary = keysOf(p, s, primary, "그림자, 밤", cfgs.getValue(20L))
        assertEquals(listOf("그림자", "밤"), viaPrimary)
        // 요약 TOP5 경로: 같은 원문을 소유 def(21)의 규칙으로 — 한 토큰
        val viaOwner = keysOf(p, s, sibling, "그림자, 밤", cfgs.getValue(21L))
        assertEquals(listOf("그림자, 밤"), viaOwner)

        // 메모가 (파싱 def, 원문) 키로 둘을 각자 들고 있다 — 서로를 밀어내지 않는다
        assertSame(viaPrimary, keysOf(p, s, primary, "그림자, 밤", cfgs.getValue(20L)))
        assertSame(viaOwner, keysOf(p, s, sibling, "그림자, 밤", cfgs.getValue(21L)))
    }

    // ===== 4. 공개 소비처 콜드=웜 — 10계산 밖의 소비처 =====

    private fun onDemandResults(p: StatsDataProvider, s: StatsSnapshot): List<Any?> = listOf(
        p.computeCrossAnalysis(s, 10L, 30L, null, null),
        p.computeEventCrossAnalysis(s, 10L, 30L, null, null),
        p.computeRanking(s, listOf(20L, 21L)),           // 빈도 모드 — 정본 밖 config 경로
        p.computeRanking(s, listOf(12L)),                // 수치 모드
        p.computeSubgroupAnalysis(s, setOf(1L, 2L, 3L), listOf(10L)),
        p.getCharactersByFieldValue(s, listOf(20L, 21L), "그림자"),
        p.computeCrossNovelComparison(s)
    )

    @Test
    fun `교차분석·순위·드릴다운·작품비교가 콜드와 웜에서 같다`() {
        val s = richSnapshot()
        val cold = onDemandResults(StatsDataProvider(), s)
        val shared = StatsDataProvider()
        // 10계산으로 메모를 가득 채운 뒤(웜) 같은 답인지 — 소비처 간 공유가 답을 바꾸면 갈린다
        shared.computeSummary(s); shared.computeFieldInsights(s)
        shared.computeFieldAnalysis(s); shared.detectPatterns(s)
        val warm = onDemandResults(shared, s)
        assertEquals(cold, warm)
        assertEquals(warm, onDemandResults(shared, s))
    }

    @Test
    fun `순위 빈도 모드는 값 축이다 - 카테고리 정본과 섞이지 않는다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        // 분포(정본, category)를 먼저 돌려 메모를 채운다
        val insights = p.computeFieldInsights(s)
        val genderCard = insights.first { it.fieldDefinition.id == 10L }
        val dist = genderCard.analysisResults.first().distributionData!!
        assertEquals(setOf("인간"), dist.keys)           // 분포는 카테고리로 접는다
        // 순위 빈도는 값 축이다 — 메모를 섞었다면 여기가 "인간"이 된다
        val ranking = p.computeRanking(s, listOf(10L))
        assertTrue(ranking.entries.isNotEmpty())
        assertTrue(ranking.entries.none { it.displayValue == "인간" })
    }

    // ===== 5. 공유 사본 · 격리 · 상한 =====

    @Test
    fun `모든 소비처를 돌린 뒤에도 메모 내용이 새로 계산한 것과 같다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        p.computeSummary(s); p.computeFieldInsights(s); p.computeFieldAnalysis(s)
        p.detectPatterns(s); onDemandResults(p, s)

        val f = StatsDataProvider::class.java.getDeclaredField("statsParseCaches")
        f.isAccessible = true
        val store = f.get(p) as java.util.concurrent.ConcurrentHashMap<*, *>
        val resolvers = s.valueEntries.groupBy { it.fieldDefinitionId }
            .mapValues { (_, e) -> FieldValueResolver(e) }
        val defById = s.fieldDefinitions.associateBy { it.id }
        var checked = 0
        for (cache in store.values) {
            val keysByDef = cache.javaClass.getDeclaredField("keysByDef")
                .apply { isAccessible = true }.get(cache) as java.util.concurrent.ConcurrentHashMap<*, *>
            val configs = cache.javaClass.getDeclaredField("configs")
                .apply { isAccessible = true }.get(cache) as Map<*, *>
            for ((defId, perDef) in keysByDef) {
                val fd = defById[defId as Long] ?: continue
                val cfg = configs[defId] as FieldStatsConfig
                for ((raw, keys) in perDef as java.util.concurrent.ConcurrentHashMap<*, *>) {
                    assertEquals(
                        "def=${fd.key} 원문=$raw — 공유 사본이 변조됐다",
                        legacyKeys(fd, raw as String, cfg, resolvers[fd.id]), keys
                    )
                    checked++
                }
            }
        }
        assertTrue("메모가 비어 있다 — 대조가 헛돌았다", checked > 0)
    }

    @Test
    fun `다른 스냅샷은 서로의 메모를 보지 않는다`() {
        val p = StatsDataProvider()
        val a = richSnapshot()
        val b = richSnapshot(salt = "B")
        val cfgA = canonicalConfigs(p, a).getValue(30L)
        val cfgB = canonicalConfigs(p, b).getValue(30L)
        val fdA = a.fieldDefinitions.first { it.id == 30L }
        val fdB = b.fieldDefinitions.first { it.id == 30L }
        // 같은 defId·다른 config(라벨이 salt로 갈린다) — 누출되면 한쪽이 틀린 라벨을 낸다
        assertEquals(listOf("화염", "원소"), keysOf(p, a, fdA, "불", cfgA))
        assertEquals(listOf("화염B", "원소"), keysOf(p, b, fdB, "불B", cfgB))
        assertEquals(listOf("화염", "원소"), keysOf(p, a, fdA, "불", cfgA))
    }

    @Test
    fun `서로 다른 스냅샷을 계속 넣어도 파싱 캐시가 상한 안에 머문다`() {
        val p = StatsDataProvider()
        repeat(7) { i ->
            val s = richSnapshot(salt = "s$i")
            keysOf(p, s, s.fieldDefinitions.first { it.id == 11L },
                "용감, 신중", canonicalConfigs(p, s).getValue(11L))
        }
        val f = StatsDataProvider::class.java.getDeclaredField("statsParseCaches")
        f.isAccessible = true
        val size = (f.get(p) as java.util.concurrent.ConcurrentHashMap<*, *>).size
        // MAX_CACHED_SNAPSHOTS(4)를 넘으면 비우고 새로 담으므로 5를 넘을 수 없다
        assertTrue("캐시가 $size 칸 — 상한 규칙이 죽었다", size <= 5)
    }

    @Test
    fun `동시 8스레드의 혼합 호출이 전부 콜드 기준과 같다`() {
        val s = richSnapshot()
        val reference = listOf(
            StatsDataProvider().computeSummary(s),
            StatsDataProvider().computeFieldInsights(s),
            StatsDataProvider().computeRanking(s, listOf(10L))
        )
        val p = StatsDataProvider()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val results = java.util.concurrent.ConcurrentHashMap<Int, List<Any?>>()
        val threads = (0 until 8).map { i ->
            Thread {
                try {
                    // 정본 경로와 정본 밖 경로(순위 빈도)를 한 스냅샷에 섞는다 —
                    // computeIfAbsent 경합과 우회 경로의 동시성이 함께 시험된다
                    results[i] = listOf(
                        p.computeSummary(s), p.computeFieldInsights(s), p.computeRanking(s, listOf(10L))
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

    // ===== 6. 값이 실제로 갈리는 대조 하나 — 위 시험들이 헛돌지 않음을 증명 =====

    @Test
    fun `comma_list 기준 def와 무포맷 형제 def는 실제로 다른 답을 낸다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val cfgs = canonicalConfigs(p, s)
        assertNotEquals(
            keysOf(p, s, s.fieldDefinitions.first { it.id == 20L }, "그림자, 밤", cfgs.getValue(20L)),
            keysOf(p, s, s.fieldDefinitions.first { it.id == 21L }, "그림자, 밤", cfgs.getValue(21L))
        )
    }
}
