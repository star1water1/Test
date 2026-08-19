package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.PatternAxis
import com.novelcharacter.app.ui.stats.PatternInsight
import com.novelcharacter.app.ui.stats.PatternSeverity
import com.novelcharacter.app.ui.stats.PatternThresholds
import com.novelcharacter.app.ui.stats.PatternType
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.util.StatsSnapshot
import com.novelcharacter.app.ui.stats.TypeMismatchedValue
import com.novelcharacter.app.util.CompletionRate
import com.novelcharacter.app.util.CompletionWeights
import com.novelcharacter.app.util.FieldValueTypeMismatch
import com.novelcharacter.app.util.NumericBinning
import com.novelcharacter.app.util.ValueDistributions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 행 훑기 접기(S6 5차 — 인사이트 조립·패턴 그룹 집계·타입 전수 판정·그룹 완성도 분해가
 * 행별 재료화 대신 **접힌 표·선계수·동일성 분해** 위에서 도는 것)의 동작 무변경 대조.
 * [StatsFoldParityTest]가 요약·레거시 분석의 접기를 잠갔고, 이 시험은 그 판이 남긴 상위 셋
 * (detectPatterns · computeDataHealth · computeFieldInsights)의 걷어낸 모양을 하네스가
 * 그대로 들고 대조한다([StatsMemoParityTest] 1번 규약).
 *
 * 잠그는 것 다섯:
 * 1. **인사이트가 건별 조립과 같은 답** — 분포(entries 순서까지)·수치 요약·모수·채움 건수를
 *    그룹마다 값 행을 재료화하던 옛 파이프라인과 대조한다. 형제 병합(R-15)·수식 합성(R-16)·
 *    미배정 스코프·같은 세계관 두 def(모수 중복 셈 함정)가 실린 스냅샷으로.
 * 2. **패턴 카드가 건별 파이프라인과 같다** — 편중·균형·희소 카드 전부(문구·심각도·드릴다운·
 *    모집단). 특히 **형제 def에 겹쳐 실린 대상은 모집단에 한 번만 센다** — def별 계수 합으로
 *    바꾸는 순간 이 대조가 갈린다(합집합만이 옛 답이다).
 * 3. **타입 불일치 목록이 행별 판정과 같다** — 판정을 고유 (정의, 원문) 위에서 먼저 내고 행은
 *    걸린 def만 지나는 새 모양이, 행마다 판정하던 옛 모양과 세 축 모두에서 목록·순서까지 같다.
 * 4. **그룹 완성도의 동일성 분해** — 같은 정의 목록을 캐릭터마다 다시 분해하던 것과 같은 답.
 *    캐릭터마다 **새 목록**을 지어 오는 호출부에서도 같다(동일성 캐시의 퇴화 방향까지 잠근다).
 * 5. **병합·수치의 접힌 판 계약** — 그룹 병합 표는 건별 연결의 첫 등장 순서를 보존하고, 단일
 *    def 그룹은 공유 표 **그 객체**를 그대로 쓰며(사본 없음), 수치 추출의 접힌 판은 목록 판과
 *    같은 다중집합을 낸다(판정 단일 소스 — B-39).
 */
class StatsScanParityTest {

    private val uniA = 1L
    private val uniB = 2L

    private fun def(
        id: Long, key: String, name: String, universeId: Long? = uniA,
        type: String = "TEXT", config: String = "{}",
        entityType: String = FieldDefinition.ENTITY_CHARACTER
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type, config = config,
        entityType = entityType
    )

    private fun cfv(id: Long, charId: Long, defId: Long, value: String) =
        CharacterFieldValue(id = id, characterId = charId, fieldDefinitionId = defId, value = value)

    /**
     * 상위 셋의 모든 갈래에 접기 가중이 실리는 스냅샷:
     * - 20/21 별칭(TEXT): comma_list 기준 def + 무포맷 형제(R-15) — **c1이 두 def 모두에 값을
     *   가진다**(세계관 이동이 남긴 모양) → 그룹 모집단의 합집합 갈래
     * - 30 등급(GRADE): 표에 없는 라벨 "X"가 두 캐릭터에 — 판정 접기 가중 + 불일치 2행
     * - 31 힘(NUMBER, numeric+ranking 분석): "abc" 불일치 + 수치 요약
     * - 32 체형(BODY_SIZE, numeric 분석): 파트 파싱 + 어느 파트도 수치가 아닌 "측정불가"
     * - 40/41 쌍둥이(TEXT, 같은 세계관 같은 키): 인사이트 모수가 세계관을 **한 번만** 세는 갈래
     * - 50 부류(TEXT): 희소(1건 값 넷) / 51 기질(TEXT): 균형(3값 동수) / 52 직업(SELECT): 편중
     * - 60 힘x2(CALCULATED): augmented 합성이 접힌 표에 실리는 갈래(R-16)
     * - 사건 축 70(TEXT)·71(NUMBER "많음" 불일치), 작품 축 80(TEXT)·81(NUMBER "미상" 불일치)
     * - 값 행 999번: 주인 없는 캐릭터(#999) — 이름 폴백 갈래
     */
    private fun richSnapshot(): StatsSnapshot {
        val defs = listOf(
            def(20, "alias", "별칭", universeId = uniA, config = """{"displayFormat":"comma_list"}"""),
            def(21, "alias", "별칭", universeId = uniB),
            def(30, "rank", "등급", type = "GRADE", config = """{"grades":{"S":5,"A":3,"B":1}}"""),
            def(31, "power", "힘", type = "NUMBER",
                config = """{"stats":{"analyses":[{"type":"numeric"},{"type":"ranking"}]}}"""),
            def(32, "body", "체형", type = "BODY_SIZE",
                config = """{"stats":{"analyses":[{"type":"numeric"}]}}"""),
            def(40, "twin", "쌍둥이A", universeId = uniA),
            def(41, "twin", "쌍둥이B", universeId = uniA),
            def(50, "kinds", "부류"),
            def(51, "temper", "기질"),
            def(52, "job", "직업", type = "SELECT"),
            def(60, "power2", "힘x2", type = "CALCULATED",
                config = """{"formula":"field('power') * 2"}""")
        )
        val chars = (1L..7L).map {
            val novelId = if (it <= 6L) 1L else 2L
            Character(id = it, name = "c$it", novelId = novelId, updatedAt = 1_700_000_000_000L)
        } + listOf(
            Character(id = 8, name = "c8", novelId = 99L, updatedAt = 1_700_000_000_000L),
            Character(id = 9, name = "c9", novelId = null, updatedAt = 1_700_000_000_000L)
        )
        val values = mutableListOf(
            // 별칭 그룹 — c1은 형제(21)에도 값이 있다(모집단 합집합 갈래).
            // "달"은 기준 def에만, "새벽"은 형제에만 있다 — 병합을 def 역순으로 도는 순간
            // 첫 등장 순서가 갈리는 짝(순서 계약의 양방향 확인이 이 짝으로 선다).
            cfv(1, 1, 20, "그림자, 밤"),
            cfv(2, 2, 20, "그림자, 밤"),
            cfv(5, 3, 20, "달"),
            cfv(3, 1, 21, "그림자, 밤"),
            cfv(4, 7, 21, "새벽"),
            // 등급 — "X"는 표에 없다(불일치 접기 가중 2행), "S"는 정상
            cfv(10, 1, 30, "S"),
            cfv(11, 2, 30, "X"),
            cfv(12, 3, 30, "X"),
            // 힘 — "abc" 불일치 + 수치
            cfv(20, 1, 31, "10"),
            cfv(21, 2, 31, "12"),
            cfv(22, 3, 31, "abc"),
            cfv(23, 4, 31, "10"),
            // 체형 — 파트 수치 + 어느 파트도 수치가 아닌 값
            cfv(30, 1, 32, "90-60-88"),
            cfv(31, 2, 32, "85-58-84"),
            cfv(32, 3, 32, "측정불가"),
            // 쌍둥이 — 같은 세계관 두 def가 한 그룹
            cfv(40, 1, 40, "동"),
            cfv(41, 2, 41, "동"),
            cfv(42, 3, 41, "서"),
            // 부류 — 희소: "왕" 8건 + 1건짜리 넷 (총 12, 5종)
            cfv(50, 1, 50, "왕"), cfv(51, 2, 50, "왕"), cfv(52, 3, 50, "왕"),
            cfv(53, 4, 50, "왕"), cfv(54, 5, 50, "왕"), cfv(55, 6, 50, "왕"),
            cfv(56, 8, 50, "왕"), cfv(57, 9, 50, "왕"),
            cfv(58, 4, 50, "장군"), cfv(59, 5, 50, "견습"),
            cfv(60, 6, 50, "암살자"), cfv(61, 7, 50, "시인"),
            // 기질 — 균형: 3값 동수
            cfv(70, 1, 51, "불"), cfv(71, 2, 51, "물"), cfv(72, 3, 51, "바람"),
            cfv(73, 4, 51, "불"), cfv(74, 5, 51, "물"), cfv(75, 6, 51, "바람"),
            // 직업 — 편중: "기사" 4/5
            cfv(80, 1, 52, "기사"), cfv(81, 2, 52, "기사"), cfv(82, 3, 52, "기사"),
            cfv(83, 4, 52, "기사"), cfv(84, 5, 52, "농부"),
            // 주인 없는 값 행 — 이름 폴백(#999). 등급 "X"라 불일치 목록에도 실린다.
            cfv(90, 999, 30, "X")
        )
        val eventDefs = listOf(
            def(70, "etype", "사건종류", entityType = FieldDefinition.ENTITY_EVENT),
            def(71, "ecount", "규모", type = "NUMBER", entityType = FieldDefinition.ENTITY_EVENT)
        )
        val novelDefs = listOf(
            def(80, "genre", "장르", entityType = FieldDefinition.ENTITY_NOVEL),
            def(81, "sales", "판매량", type = "NUMBER", entityType = FieldDefinition.ENTITY_NOVEL)
        )
        val events = listOf(
            TimelineEvent(id = 1, year = 1000, month = null, day = null, calendarType = "",
                description = "사건1", eventType = "", universeId = uniA, displayOrder = 1,
                isTemporary = false, code = null),
            TimelineEvent(id = 2, year = 1010, month = null, day = null, calendarType = "",
                description = "사건2", eventType = "", universeId = uniA, displayOrder = 2,
                isTemporary = false, code = null),
            TimelineEvent(id = 3, year = 1020, month = null, day = null, calendarType = "",
                description = "사건3", eventType = "", universeId = uniB, displayOrder = 3,
                isTemporary = false, code = null)
        )
        val eventValues = listOf(
            EventFieldValue(id = 1, eventId = 1, fieldDefinitionId = 70, value = "전투"),
            EventFieldValue(id = 2, eventId = 2, fieldDefinitionId = 70, value = "전투"),
            EventFieldValue(id = 3, eventId = 3, fieldDefinitionId = 70, value = "협상"),
            EventFieldValue(id = 4, eventId = 1, fieldDefinitionId = 71, value = "많음"),
            EventFieldValue(id = 5, eventId = 2, fieldDefinitionId = 71, value = "3")
        )
        val novelValues = listOf(
            NovelFieldValue(id = 1, novelId = 1, fieldDefinitionId = 80, value = "판타지"),
            NovelFieldValue(id = 2, novelId = 2, fieldDefinitionId = 80, value = "무협"),
            NovelFieldValue(id = 3, novelId = 1, fieldDefinitionId = 81, value = "미상")
        )
        return StatsSnapshot(
            characters = chars,
            novels = listOf(
                Novel(id = 1, title = "A작품", universeId = uniA),
                Novel(id = 2, title = "B작품", universeId = uniB),
                Novel(id = 3, title = "무소속작품", universeId = null)
            ),
            universes = listOf(Universe(id = uniA, name = "A"), Universe(id = uniB, name = "B")),
            events = events, relationships = emptyList(), relationshipChanges = emptyList(),
            tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
            fieldDefinitions = defs,
            fieldValues = values,
            crossRefs = emptyList(),
            eventFieldDefinitions = eventDefs,
            eventFieldValues = eventValues,
            novelFieldDefinitions = novelDefs,
            novelFieldValues = novelValues
        )
    }

    // ── 리플렉션 헬퍼 — private 파싱·조립 자리를 직접 본다 ([StatsKeysParityTest] 규약) ──

    private val cls = StatsDataProvider::class.java

    private val gfv = cls.getDeclaredMethod(
        "getFieldValues", StatsSnapshot::class.java, FieldDefinition::class.java,
        String::class.java, FieldStatsConfig::class.java
    ).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun keysOf(p: StatsDataProvider, s: StatsSnapshot, fd: FieldDefinition,
                       raw: String, cfg: FieldStatsConfig): List<String> =
        gfv.invoke(p, s, fd, raw, cfg) as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun canonicalConfigs(p: StatsDataProvider, s: StatsSnapshot): Map<Long, FieldStatsConfig> =
        cls.getDeclaredMethod("statsConfigsOf", StatsSnapshot::class.java)
            .apply { isAccessible = true }.invoke(p, s) as Map<Long, FieldStatsConfig>

    @Suppress("UNCHECKED_CAST")
    private fun augOf(p: StatsDataProvider, s: StatsSnapshot): Map<Long, List<CharacterFieldValue>> =
        cls.getDeclaredMethod("augmentedCharacterValues", StatsSnapshot::class.java)
            .apply { isAccessible = true }.invoke(p, s) as Map<Long, List<CharacterFieldValue>>

    @Suppress("UNCHECKED_CAST")
    private fun countsOf(p: StatsDataProvider, s: StatsSnapshot): Map<Long, Map<String, Int>> =
        cls.getDeclaredMethod("valueCountsOf", StatsSnapshot::class.java)
            .apply { isAccessible = true }.invoke(p, s) as Map<Long, Map<String, Int>>

    private fun charGroups(p: StatsDataProvider, s: StatsSnapshot): List<List<FieldDefinition>> {
        val cfgs = canonicalConfigs(p, s)
        return s.fieldDefinitions.filter { cfgs.getValue(it.id).enabled }
            .groupBy { it.key to it.type }.values.toList()
    }

    // ===== 1. 인사이트 — 건별 조립 대조 =====

    /** 걷어낸 모양 — 그룹마다 값 행 문자열을 재료화해 분포·수치·모수·건수를 내던 그 조립. */
    private fun legacyCharacterInsights(
        p: StatsDataProvider, s: StatsSnapshot
    ): List<LegacyInsight> {
        val cfgs = canonicalConfigs(p, s)
        val aug = augOf(p, s)
        return charGroups(p, s).map { fds ->
            val primaryFd = fds.first()
            val cfg = cfgs.getValue(primaryFd.id)
            val rawValues = fds.flatMap { fd -> aug[fd.id].orEmpty() }.map { it.value }
            val universeIds = fds.map { it.universeId }.toSet()
            val relevantNovelIds = s.novels.filter { it.universeId in universeIds }
                .map { it.id }.toSet()
            val totalCount = if (s.unassignedScope) s.characters.size
                else s.characters.count { it.novelId in relevantNovelIds }
            val dists = cfg.analyses
                .filter { it.type != FieldStatsConfig.StatsType.NUMERIC }
                .map { ValueDistributions.of(rawValues.flatMap { v -> keysOf(p, s, primaryFd, v, cfg) }).toList() }
            val numeric = cfg.analyses
                .filter { it.type == FieldStatsConfig.StatsType.NUMERIC }
                .map { NumericBinning.numericValuesOf(rawValues, "", 0).sorted() }
            LegacyInsight(primaryFd.id, fds.map { it.id }, totalCount, rawValues.size, dists, numeric)
        }
    }

    private data class LegacyInsight(
        val primaryId: Long,
        val mergedIds: List<Long>,
        val totalCount: Int,
        val filledCount: Int,
        val distributions: List<List<Pair<String, Int>>>,
        val numericSorted: List<List<Float>>
    )

    @Test
    fun `인사이트가 건별 조립과 같은 답을 낸다 - 분포 순서·모수·건수`() {
        val s = richSnapshot()
        val expected = legacyCharacterInsights(StatsDataProvider(), s)
        val actual = StatsDataProvider().computeFieldInsights(s)
            .filter { it.fieldDefinition.entityType == FieldDefinition.ENTITY_CHARACTER }

        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            assertEquals(e.primaryId, a.fieldDefinition.id)
            assertEquals(e.mergedIds, a.mergedFieldDefIds)
            assertEquals("def=${e.primaryId} 모수", e.totalCount, a.totalCount)
            assertEquals("def=${e.primaryId} 채움 건수", e.filledCount, a.filledCount)
            // 분포는 **entries 순서까지** — 합이 같은데 다른 것부터 그리면 화면이 다르다
            val actualDists = a.analysisResults.mapNotNull { r -> r.distributionData?.toList() }
            assertEquals("def=${e.primaryId} 분포", e.distributions, actualDists)
        }
        // 형제 병합(별칭 20+21)이 실제로 실렸는지 — 형제의 "그림자, 밤"도 기준 def의 콤마 규칙으로
        val alias = actual.first { it.fieldDefinition.id == 20L }
        assertEquals(3, alias.analysisResults.first().distributionData?.get("그림자"))
        // 같은 세계관 두 def(쌍둥이 40+41)의 모수가 세계관을 한 번만 센다 — A작품 캐릭터 6
        val twin = actual.first { it.fieldDefinition.id == 40L }
        assertEquals(6, twin.totalCount)
    }

    @Test
    fun `인사이트 수치 요약이 건별 파싱과 같은 다중집합 위에 선다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val legacy = legacyCharacterInsights(StatsDataProvider(), s)
        val actual = p.computeFieldInsights(s)
            .filter { it.fieldDefinition.entityType == FieldDefinition.ENTITY_CHARACTER }

        // 힘(31): numeric 분석 — count·min·max가 건별 목록과 같다 ("abc"는 양쪽 다 탈락)
        val power = actual.first { it.fieldDefinition.id == 31L }
        val powerNums = legacy.first { it.primaryId == 31L }.numericSorted.single()
        val powerSummary = power.analysisResults.mapNotNull { it.numericSummary }.single()
        assertEquals(3, powerNums.size)
        assertEquals(powerNums.first(), powerSummary.min)
        assertEquals(powerNums.last(), powerSummary.max)
        // 체형(32): 파트 셋이 전부 서고, 첫 파트 min·max가 건별 파싱과 같다("측정불가"는 탈락)
        val body = actual.first { it.fieldDefinition.id == 32L }
        val bodyParts = body.analysisResults.filter { it.numericSummary != null }
        assertEquals(3, bodyParts.size)
        val legacyPart0 = NumericBinning.numericValuesOf(
            augOf(p, s).getValue(32L).map { it.value }, "-", 0)
        assertEquals(legacyPart0.min(), bodyParts[0].numericSummary!!.min)
        assertEquals(legacyPart0.max(), bodyParts[0].numericSummary!!.max)
    }

    @Test
    fun `미배정 스코프에서도 인사이트가 건별 조립과 같다`() {
        val s = richSnapshot().copy(unassignedScope = true)
        val expected = legacyCharacterInsights(StatsDataProvider(), s)
        val actual = StatsDataProvider().computeFieldInsights(s)
            .filter { it.fieldDefinition.entityType == FieldDefinition.ENTITY_CHARACTER }
        assertEquals(expected.map { it.totalCount }, actual.map { it.totalCount })
        // 미배정 모수 = 스냅샷 캐릭터 전체(9) — novelId가 없는 캐릭터도 든다
        assertTrue(actual.all { it.totalCount == s.characters.size })
        assertEquals(expected.map { it.distributions },
            actual.map { a -> a.analysisResults.mapNotNull { it.distributionData?.toList() } })
    }

    // ===== 2. 패턴 카드 — 건별 파이프라인 대조 =====

    /** 걷어낸 모양 — 그룹마다 값 행을 flatMap으로 재료화하고 행마다 접어 카드를 내던 그 집계. */
    private fun legacyFieldPatternCards(
        p: StatsDataProvider, s: StatsSnapshot,
        enabledTypes: Set<PatternType>, thresholds: PatternThresholds
    ): List<Triple<String, PatternSeverity, Pair<List<String>, Int>>> {
        val cfgs = canonicalConfigs(p, s)
        val aug = augOf(p, s)
        val out = mutableListOf<Triple<PatternType, PatternSeverity, Triple<String, List<String>, Int>>>()
        for (fieldDefs in charGroups(p, s)) {
            val rawValues = fieldDefs.flatMap { aug[it.id].orEmpty() }
            if (rawValues.isEmpty()) continue
            val fd = fieldDefs.first()
            val cfg = cfgs.getValue(fd.id)
            val keys = rawValues.flatMap { keysOf(p, s, fd, it.value, cfg) }
            if (keys.isEmpty()) continue
            val dist = ValueDistributions.of(keys)
            val total = keys.size
            val ownerCount = rawValues.mapTo(HashSet()) { it.characterId }.size
            val topEntry = dist.entries.first()
            if (PatternType.DOMINANCE in enabledTypes) {
                val topPct = topEntry.value * 100f / total
                if (topPct >= thresholds.dominancePercent) {
                    val sev = if (topPct >= thresholds.dominanceHighPercent) PatternSeverity.HIGH
                        else PatternSeverity.MEDIUM
                    out.add(Triple(PatternType.DOMINANCE, sev,
                        Triple("${fd.name}: '${topEntry.key}' 편중", listOf(topEntry.key), ownerCount)))
                }
            }
            if (PatternType.BALANCE in enabledTypes && dist.size >= 3) {
                val pcts = dist.values.map { it * 100f / total }
                if (pcts.all { it in thresholds.balanceMinPercent..thresholds.balanceMaxPercent }) {
                    out.add(Triple(PatternType.BALANCE, PatternSeverity.LOW,
                        Triple("${fd.name}: 균형 양호", emptyList(), ownerCount)))
                }
            }
            if (PatternType.OUTLIER in enabledTypes && total >= 10) {
                val singletons = dist.entries.filter { it.value == 1 }
                val singletonPct = singletons.size * 100f / total
                if (singletons.isNotEmpty() && singletonPct <= thresholds.outlierSingletonPercent &&
                    dist.size > 3
                ) {
                    out.add(Triple(PatternType.OUTLIER, PatternSeverity.LOW,
                        Triple("${fd.name}: 희소 값 발견", singletons.map { it.key }, ownerCount)))
                }
            }
        }
        // 본문과 같은 안정 정렬(심각도) — 같은 심각도 안에서는 삽입 순서가 남는다
        return out.sortedBy { it.second.ordinal }
            .map { Triple(it.third.first, it.second, it.third.second to it.third.third) }
    }

    @Test
    fun `패턴 카드가 건별 파이프라인과 같다 - 편중·균형·희소·모집단 전부`() {
        val s = richSnapshot()
        // 세 유형이 전부 서는 문턱 — 대조 대상은 문턱이 아니라 집계 산술이다
        // (균형 하한은 손잡이가 아니라 계산 속성이다 — 상한 40이면 하한은 고정 하한 그대로)
        val thresholds = PatternThresholds(
            dominancePercent = 60f, balanceMaxPercent = 40f,
            outlierSingletonPercent = 40f
        )
        val enabled = setOf(PatternType.DOMINANCE, PatternType.BALANCE, PatternType.OUTLIER)
        val expected = legacyFieldPatternCards(StatsDataProvider(), s, enabled, thresholds)
        val actual = StatsDataProvider().detectPatterns(s, enabled, thresholds)
            .filter { it.fieldDefId != null && it.axis == PatternAxis.CHARACTER }
            .map { Triple(it.title, it.severity, it.drilldownValues to it.population) }
        assertEquals(expected, actual)
        // 세 유형이 실제로 전부 실렸는지 — 카드가 안 서면 이 대조는 아무것도 증명하지 않는다
        assertTrue(expected.any { it.first.contains("편중") })
        assertTrue(expected.any { it.first.contains("균형") })
        assertTrue(expected.any { it.first.contains("희소") })
    }

    @Test
    fun `형제 def에 겹쳐 실린 대상은 모집단에 한 번만 센다`() {
        val s = richSnapshot()
        // 별칭 그룹(20+21): c1이 두 def 모두에 값을 가진다 — 값 행 5개, 임자는 c1·c2·c3·c7 넷.
        // def별 계수 합(3+2=5)으로 바꾸는 순간 이 대조가 갈린다(합집합만이 옛 답이다).
        val card = StatsDataProvider().detectPatterns(
            s, setOf(PatternType.DOMINANCE), PatternThresholds(dominancePercent = 10f)
        ).first { it.fieldDefId == 20L }
        assertEquals(4, card.population)
    }

    @Test
    fun `사건 축 카드가 접힌 표 위에서도 같은 산술이다`() {
        val s = richSnapshot()
        // 사건종류(70): "전투" 2/3 = 67% — 임자는 사건 1·2·3 셋
        val card = StatsDataProvider().detectPatterns(
            s, setOf(PatternType.DOMINANCE), PatternThresholds(dominancePercent = 60f)
        ).first { it.fieldDefId == 70L }
        assertEquals(PatternAxis.EVENT, card.axis)
        assertEquals(listOf("전투"), card.drilldownValues)
        assertEquals(3, card.population)
        assertTrue(card.description.contains("67%"))
    }

    // ===== 3. 타입 불일치 — 행별 판정 대조 =====

    /** 걷어낸 모양 — 행마다 defById를 찾고 reasonFor를 내던 그 훑기(세 축 공통). */
    private fun legacyTypeMismatches(s: StatsSnapshot): List<TypeMismatchedValue> {
        val out = mutableListOf<TypeMismatchedValue>()
        fun <T> collect(
            defs: List<FieldDefinition>, values: List<T>, ownerType: String,
            ownerIdOf: (T) -> Long, fieldDefIdOf: (T) -> Long, valueOf: (T) -> String,
            ownerNames: Map<Long, String>
        ) {
            if (defs.isEmpty() || values.isEmpty()) return
            val defById = defs.associateBy { it.id }
            val found = mutableListOf<TypeMismatchedValue>()
            values.forEach { row ->
                val def = defById[fieldDefIdOf(row)] ?: return@forEach
                val raw = valueOf(row)
                val reason = FieldValueTypeMismatch.reasonFor(def, raw) ?: return@forEach
                val ownerId = ownerIdOf(row)
                found.add(TypeMismatchedValue(
                    ownerType = ownerType, ownerId = ownerId,
                    ownerName = ownerNames[ownerId] ?: "#$ownerId",
                    fieldDefId = fieldDefIdOf(row),
                    fieldName = def.name, fieldType = def.type, value = raw, reason = reason
                ))
            }
            out.addAll(found.sortedWith(compareBy({ it.ownerName }, { it.fieldName })))
        }
        collect(s.fieldDefinitions, s.fieldValues, FieldDefinition.ENTITY_CHARACTER,
            { it.characterId }, { it.fieldDefinitionId }, { it.value },
            s.characters.associate { it.id to it.name })
        collect(s.eventFieldDefinitions, s.eventFieldValues, FieldDefinition.ENTITY_EVENT,
            { it.eventId }, { it.fieldDefinitionId }, { it.value },
            s.events.associate { it.id to it.description })
        collect(s.novelFieldDefinitions, s.novelFieldValues, FieldDefinition.ENTITY_NOVEL,
            { it.novelId }, { it.fieldDefinitionId }, { it.value },
            s.novels.associate { it.id to it.title })
        return out
    }

    @Test
    fun `타입 불일치 목록이 행별 판정과 세 축 모두에서 목록·순서까지 같다`() {
        val s = richSnapshot()
        val expected = legacyTypeMismatches(s)
        val actual = StatsDataProvider().computeDataHealth(s).typeMismatchedValues
        assertEquals(expected, actual)
        // 갈래가 실제로 실렸는지 — 세 축 전부에서, 그리고 같은 (정의, 원문)의 행이 **둘 다** 잡힌다
        assertTrue(actual.count { it.value == "X" } == 3)   // 등급 "X": c2·c3·주인 없는 행
        assertTrue(actual.any { it.ownerName == "#999" })    // 주인 없는 행은 id로 말한다
        assertTrue(actual.any { it.ownerType == FieldDefinition.ENTITY_EVENT && it.value == "많음" })
        assertTrue(actual.any { it.ownerType == FieldDefinition.ENTITY_NOVEL && it.value == "미상" })
        assertTrue(actual.any { it.value == "측정불가" })     // BODY_SIZE — 어느 파트도 수치가 아니다
        assertTrue(actual.any { it.value == "abc" })         // NUMBER
    }

    // ===== 4. 그룹 완성도 — 동일성 분해 대조 =====

    @Test
    fun `그룹 완성도가 캐릭터별 분해와 같고 새 목록을 지어 와도 같다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val novelMap = s.novels.associateBy { it.id }
        val byUni = s.fieldDefinitions.groupBy { it.universeId }
        @Suppress("UNCHECKED_CAST")
        val filled = cls.getDeclaredMethod("filledCharacterDefIds", StatsSnapshot::class.java)
            .apply { isAccessible = true }.invoke(p, s) as Map<Long, Set<Long>>

        // 걷어낸 모양 — 캐릭터마다 fields.groupBy를 다시 짓던 그 분해
        val legacy = mutableMapOf<String, MutableList<Float>>()
        s.characters.forEach { char ->
            val fields = char.novelId?.let { novelMap[it] }?.let { byUni[it.universeId] }
                ?: return@forEach
            val f = filled[char.id].orEmpty()
            fields.groupBy { it.groupName }.forEach { (group, groupFields) ->
                val rate = CompletionRate.percentOf(groupFields, f, s.completionWeights)
                    ?: return@forEach
                legacy.getOrPut(group) { mutableListOf() }.add(rate)
            }
        }
        val expected = legacy.mapValues { (_, rates) -> rates.average().toFloat() }
        assertEquals(expected, p.computeDataHealth(s).fieldCompletionByGroup)

        // 캐릭터마다 **새 목록**을 지어 오는 호출부 — 동일성 캐시가 캐릭터별 분해로 퇴화해도 같다
        val m = cls.getDeclaredMethod(
            "groupCompletionAverages", List::class.java, kotlin.jvm.functions.Function1::class.java,
            Map::class.java, CompletionWeights::class.java
        ).apply { isAccessible = true }
        val freshLists: (Character) -> List<FieldDefinition>? = { char ->
            char.novelId?.let { novelMap[it] }?.let { byUni[it.universeId] }?.toList()
        }
        @Suppress("UNCHECKED_CAST")
        val degenerate = m.invoke(p, s.characters, freshLists, filled, s.completionWeights)
            as Map<String, Float>
        assertEquals(expected, degenerate)
    }

    // ===== 5. 병합·수치 — 접힌 판의 계약 =====

    @Test
    fun `그룹 병합 표가 건별 연결의 첫 등장 순서를 보존하고 단일 def는 공유 표 그 객체다`() {
        val s = richSnapshot()
        val p = StatsDataProvider()
        val counts = countsOf(p, s)
        val aug = augOf(p, s)
        val merge = cls.getDeclaredMethod("mergedRawCounts", Map::class.java, List::class.java)
            .apply { isAccessible = true }

        for (fds in charGroups(p, s)) {
            @Suppress("UNCHECKED_CAST")
            val merged = merge.invoke(p, counts, fds) as Map<String, Int>
            // 건별 연결(flatMap)을 행마다 접은 것과 값·순서 모두 같다
            val rows = fds.flatMap { aug[it.id].orEmpty() }
            val legacy = LinkedHashMap<String, Int>()
            for (v in rows) legacy.merge(v.value, 1) { a, b -> a + b }
            assertEquals("group=${fds.first().key}", legacy.toList(), merged.toList())
            // 단일 def 그룹은 공유 표 **그 객체**를 그대로 쓴다 — 사본을 만들기 시작하면
            // 이 판이 걷은 할당이 도로 생긴다(그리고 받은 쪽은 읽기만 한다는 계약이 흐려진다)
            if (fds.size == 1) {
                assertSame(counts.getValue(fds[0].id), merged)
            }
        }
    }

    @Test
    fun `수치 추출의 접힌 판이 목록 판과 같은 다중집합을 낸다`() {
        // U+00A0(줄바꿈 없는 공백)은 S-16이 잡은 갈림 자리 — 두 판이 같은 판정(partValue)을
        // 지나므로 여기서도 같이 살아남아야 한다. "abc"는 양쪽 다 탈락.
        val rawValues = listOf("10", "\u00A012\u00A0", "10", "abc", "7.5", "10")
        val rawCounts = linkedMapOf<String, Int>()
        for (v in rawValues) rawCounts.merge(v, 1) { a, b -> a + b }
        val fromList = NumericBinning.numericValuesOf(rawValues, "", 0)
        val fromCounts = NumericBinning.numericValuesOf(rawCounts, "", 0)
        assertEquals(fromList.sorted(), fromCounts.sorted())
        assertEquals(5, fromCounts.size)   // "abc"만 탈락 — NBSP 수치는 살아남는다
        // 파트 판도 같다 — 체형 구분자 경로
        val parts = listOf("90-60-88", "90-60-88", "85-58")
        val partCounts = linkedMapOf("90-60-88" to 2, "85-58" to 1)
        for (idx in 0..2) {
            assertEquals(
                NumericBinning.numericValuesOf(parts, "-", idx).sorted(),
                NumericBinning.numericValuesOf(partCounts, "-", idx).sorted()
            )
        }
    }
}
